package com.example.lrcfetcher.metadata

import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.tags.TrackTags
import org.json.JSONArray
import org.json.JSONObject

enum class MetaSource(val label: String) {
    DEEZER("Deezer"),
    ITUNES("Apple Music / iTunes"),
    MUSICBRAINZ("MusicBrainz"),
}

data class CoverOption(val source: String, val url: String, val size: Int)

/** Un resultado de un repositorio: los metadatos de una edición concreta de la canción. */
data class MetadataCandidate(
    val source: MetaSource,
    val id: String,
    val tags: TrackTags,
    val durationMs: Long?,
    val covers: List<CoverOption> = emptyList(),
)

interface MetadataSource {
    val id: MetaSource

    /** Candidatos (sin detalles caros). Bloqueante. */
    fun search(query: TrackQuery): List<MetadataCandidate>

    /** Completa un candidato con los datos que requieren más peticiones. Bloqueante. */
    fun details(candidate: MetadataCandidate): MetadataCandidate = candidate
}

// ====================================================================== Deezer

/** API pública de Deezer (sin clave): álbum, artista del álbum, géneros, pista/disco, fecha y portada 1000 px. */
object DeezerSource : MetadataSource {
    override val id = MetaSource.DEEZER

    override fun search(query: TrackQuery): List<MetadataCandidate> {
        val structured = if (!query.artist.isNullOrBlank()) {
            "artist:\"${query.artist}\" track:\"${query.title}\""
        } else null
        val first = structured?.let { runCatching { searchRaw(it) }.getOrDefault(emptyList()) }.orEmpty()
        return first.ifEmpty { searchRaw(query.searchText) }
    }

    private fun searchRaw(q: String): List<MetadataCandidate> {
        val arr = JSONObject(Net.get("https://api.deezer.com/search?limit=10&q=${Net.enc(q)}")).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val album = o.optJSONObject("album")
            MetadataCandidate(
                source = id,
                id = o.optLong("id").toString(),
                tags = TrackTags(
                    title = o.optString("title").ifBlank { null },
                    artists = listOfNotNull(o.optJSONObject("artist")?.optString("name")?.ifBlank { null }),
                    album = album?.optString("title")?.ifBlank { null },
                ),
                durationMs = o.optLong("duration").takeIf { it > 0 }?.times(1000),
                covers = listOfNotNull(album?.optString("cover_xl")?.ifBlank { null }?.let { CoverOption("Deezer", it, 1000) }),
            ).copy(id = "${o.optLong("id")}|${album?.optLong("id") ?: 0}")
        }
    }

    override fun details(candidate: MetadataCandidate): MetadataCandidate {
        val (trackId, albumId) = candidate.id.split('|').let { it[0] to it.getOrElse(1) { "0" } }
        val track = JSONObject(Net.get("https://api.deezer.com/track/$trackId"))
        val album = JSONObject(Net.get("https://api.deezer.com/album/$albumId"))
        val contributors = track.optJSONArray("contributors") ?: JSONArray()
        val artists = (0 until contributors.length()).mapNotNull { contributors.optJSONObject(it) }
            .filter { it.optString("role").let { r -> r == "Main" || r == "Featured" } }
            .map { it.optString("name") }.filter { it.isNotBlank() }.distinct()
        val genres = album.optJSONObject("genres")?.optJSONArray("data")?.let { g ->
            (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("name")?.ifBlank { null } }
        }.orEmpty()
        val disc = track.optInt("disk_number").takeIf { it > 0 }
        // Pistas por disco y número de discos: a partir de la lista de pistas del álbum.
        val albumTracks = runCatching {
            JSONObject(Net.get("https://api.deezer.com/album/$albumId/tracks?limit=500")).optJSONArray("data")
        }.getOrNull() ?: JSONArray()
        val discs = (0 until albumTracks.length()).mapNotNull { albumTracks.optJSONObject(it)?.optInt("disk_number") }
        val trackTotal = if (discs.isNotEmpty() && disc != null) discs.count { it == disc } else album.optInt("nb_tracks").takeIf { it > 0 }
        return candidate.copy(
            tags = candidate.tags.copy(
                title = track.optString("title").ifBlank { candidate.tags.title },
                artists = artists.ifEmpty { candidate.tags.artists },
                album = album.optString("title").ifBlank { candidate.tags.album },
                albumArtist = album.optJSONObject("artist")?.optString("name")?.ifBlank { null },
                genres = genres,
                year = track.optString("release_date").ifBlank { album.optString("release_date") }.ifBlank { null },
                trackNumber = track.optInt("track_position").takeIf { it > 0 },
                trackTotal = trackTotal,
                discNumber = disc,
                discTotal = discs.distinct().size.takeIf { it > 0 },
            ).normalized(),
            covers = listOfNotNull(album.optString("cover_xl").ifBlank { null }?.let { CoverOption("Deezer", it, 1000) })
                .ifEmpty { candidate.covers },
        )
    }
}

// ====================================================================== iTunes

/** API pública iTunes Search: todo en una sola petición, portada hasta 3000 px. Sin compositores. */
object ITunesSource : MetadataSource {
    override val id = MetaSource.ITUNES

    override fun search(query: TrackQuery): List<MetadataCandidate> {
        val q = query.searchText
        val stores = when {
            q.any { it.code in 0x3040..0x30FF } -> listOf("jp", "us")
            q.any { it.code in 0xAC00..0xD7AF } -> listOf("kr", "us")
            q.any { it.code in 0x4E00..0x9FFF } -> listOf("tw", "us")
            else -> listOf("us")
        }
        val out = LinkedHashMap<String, MetadataCandidate>()
        for (store in stores) {
            val body = runCatching {
                Net.get("https://itunes.apple.com/search?term=${Net.enc(q)}&media=music&entity=song&limit=15&country=$store")
            }.getOrNull() ?: continue
            val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val trackId = o.optLong("trackId").takeIf { it > 0 }?.toString() ?: continue
                out.getOrPut(trackId) { parse(o, trackId) }
            }
        }
        return out.values.toList()
    }

    private fun parse(o: JSONObject, trackId: String): MetadataCandidate {
        val artist = o.optString("artistName").ifBlank { null }
        val art = o.optString("artworkUrl100").ifBlank { null }
        return MetadataCandidate(
            source = id,
            id = trackId,
            tags = TrackTags(
                title = o.optString("trackName").ifBlank { null },
                artists = listOfNotNull(artist),
                album = o.optString("collectionName").ifBlank { null },
                albumArtist = o.optString("collectionArtistName").ifBlank { null } ?: artist,
                genres = listOfNotNull(o.optString("primaryGenreName").ifBlank { null }),
                year = o.optString("releaseDate").take(10).ifBlank { null },
                trackNumber = o.optInt("trackNumber").takeIf { it > 0 },
                trackTotal = o.optInt("trackCount").takeIf { it > 0 },
                discNumber = o.optInt("discNumber").takeIf { it > 0 },
                discTotal = o.optInt("discCount").takeIf { it > 0 },
            ).normalized(),
            durationMs = o.optLong("trackTimeMillis").takeIf { it > 0 },
            covers = listOfNotNull(art?.let { CoverOption("Apple Music", it.replace("100x100bb", "1200x1200bb"), 1200) }),
        )
    }
}

// ====================================================================== MusicBrainz

/**
 * MusicBrainz (datos CC0): compositores (a través de las "obras"), géneros y edición.
 * Límite de 1 petición por segundo y User-Agent identificable, como pide su API.
 */
object MusicBrainzSource : MetadataSource {
    override val id = MetaSource.MUSICBRAINZ

    private val headers = mapOf("User-Agent" to com.example.lrcfetcher.AppInfo.USER_AGENT, "Accept" to "application/json")
    private var lastRequest = 0L

    private fun get(url: String): JSONObject {
        var attempt = 0
        while (true) {
            synchronized(this) {
                val wait = lastRequest + 1100 - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastRequest = System.currentTimeMillis()
            }
            try {
                return JSONObject(Net.get(url, headers))
            } catch (e: com.example.lrcfetcher.lyrics.HttpException) {
                // 503 = servidor ocupado / límite de peticiones: esperar y reintentar.
                if (e.code != 503 || attempt >= 3) throw e
                Thread.sleep(2000L shl attempt)
                attempt++
            }
        }
    }

    private fun esc(s: String) = s.replace(Regex("""([+\-&|!(){}\[\]^"~*?:\\/])"""), """\\$1""")

    override fun search(query: TrackQuery): List<MetadataCandidate> {
        val q = buildString {
            append("recording:\"").append(esc(query.title)).append('"')
            if (!query.artist.isNullOrBlank()) append(" AND artist:\"").append(esc(query.artist)).append('"')
        }
        val arr = get("https://musicbrainz.org/ws/2/recording?fmt=json&limit=8&query=${Net.enc(q)}").optJSONArray("recordings") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val release = pickRelease(r.optJSONArray("releases"))
            val medium = release?.optJSONArray("media")?.optJSONObject(0)
            MetadataCandidate(
                source = id,
                id = r.optString("id") + "|" + (release?.optString("id") ?: ""),
                tags = TrackTags(
                    title = r.optString("title").ifBlank { null },
                    artists = credits(r.optJSONArray("artist-credit")),
                    album = release?.optString("title")?.ifBlank { null },
                    albumArtist = release?.optJSONArray("artist-credit")?.let { credits(it).joinToString(", ") }?.ifBlank { null },
                    year = release?.optString("date")?.ifBlank { null },
                    trackNumber = medium?.optJSONArray("track")?.optJSONObject(0)?.optString("number")?.toIntOrNull(),
                    trackTotal = medium?.optInt("track-count")?.takeIf { it > 0 },
                    discNumber = medium?.optInt("position")?.takeIf { it > 0 },
                ).normalized(),
                durationMs = r.optLong("length").takeIf { it > 0 },
                covers = listOfNotNull(release?.optString("id")?.ifBlank { null }?.let {
                    CoverOption("Cover Art Archive", "https://coverartarchive.org/release/$it/front-1200", 1200)
                }),
            )
        }
    }

    private fun credits(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.ifBlank { null } }
    }

    /** Preferir un álbum oficial (no recopilatorio), y el más antiguo. */
    private fun pickRelease(arr: JSONArray?): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        fun rank(r: JSONObject): Int {
            val group = r.optJSONObject("release-group")
            val secondary = group?.optJSONArray("secondary-types")?.length() ?: 0
            var s = 0
            if (r.optString("status") == "Official") s += 4
            if (group?.optString("primary-type") == "Album") s += 2
            if (secondary == 0) s += 3
            return s
        }
        return all.sortedWith(compareByDescending<JSONObject> { rank(it) }.thenBy { it.optString("date").ifBlank { "9999" } }).first()
    }

    override fun details(candidate: MetadataCandidate): MetadataCandidate {
        val recordingId = candidate.id.substringBefore('|')
        val r = get(
            "https://musicbrainz.org/ws/2/recording/$recordingId?fmt=json&inc=genres+work-rels+work-level-rels+artist-rels",
        )
        val composers = mutableListOf<String>()
        val lyricists = mutableListOf<String>()
        val rels = r.optJSONArray("relations") ?: JSONArray()
        for (i in 0 until rels.length()) {
            val work = rels.optJSONObject(i)?.optJSONObject("work") ?: continue
            val wrels = work.optJSONArray("relations") ?: continue
            for (k in 0 until wrels.length()) {
                val w = wrels.optJSONObject(k) ?: continue
                val name = w.optJSONObject("artist")?.optString("name")?.ifBlank { null } ?: continue
                when (w.optString("type")) {
                    "composer", "writer" -> composers += name
                    "lyricist" -> lyricists += name
                }
            }
        }
        val genres = r.optJSONArray("genres")?.let { g ->
            (0 until g.length()).mapNotNull { g.optJSONObject(it) }.sortedByDescending { it.optInt("count") }
                .mapNotNull { it.optString("name").ifBlank { null } }.take(3)
                .map { n -> n.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }
        }.orEmpty()
        return candidate.copy(
            tags = candidate.tags.copy(
                composers = composers.ifEmpty { lyricists }.distinct(),
                genres = genres.ifEmpty { candidate.tags.genres },
            ).normalized(),
        )
    }
}
