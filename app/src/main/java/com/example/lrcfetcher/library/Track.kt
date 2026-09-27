package com.example.lrcfetcher.library

import android.net.Uri
import com.example.lrcfetcher.lyrics.Matching
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.tags.TrackTags
import org.json.JSONArray
import org.json.JSONObject

/** Una canción de la carpeta elegida. Se guarda en caché entre escaneos. */
data class Track(
    val uri: String,
    val docId: String,
    val parentDocId: String,
    val fileName: String,
    val ext: String,
    val size: Long,
    val lastModified: Long,
    /** Título a mostrar: el de la etiqueta; si no hay, el deducido del nombre de archivo. */
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    /** documentId del .lrc con el mismo nombre en la misma carpeta, si existe. */
    val lrcDocId: String? = null,
    val hasEmbeddedLyrics: Boolean = false,
    /** false mientras sólo conocemos el nombre de archivo (metadatos pendientes). */
    val metadataLoaded: Boolean = false,
    /** Etiquetas tal como están en el archivo. */
    val tags: TrackTags = TrackTags(),
    val hasCover: Boolean = false,
    /** El lote de metadatos no encontró coincidencia segura: requiere aceptación del usuario. */
    val needsReview: Boolean = false,
    /** De dónde salen título/artista (etiquetas o nombre de archivo, comprobado o no). */
    val identity: Identity = Identity.TAGS,
    /** Las etiquetas del archivo parecen tener título y artista intercambiados. */
    val swapSuspected: Boolean = false,
) {
    val hasLyrics: Boolean get() = lrcDocId != null || hasEmbeddedLyrics
    val androidUri: Uri get() = Uri.parse(uri)
    val baseName: String get() = fileName.substringBeforeLast('.')
    /** Título y artista vienen de las etiquetas (no del nombre de archivo). */
    val hasTaggedIdentity: Boolean get() = !tags.title.isNullOrBlank() && tags.artists.isNotEmpty()
    val metadataIncomplete: Boolean get() = metadataLoaded && tags.isIncomplete

    fun toQuery() = TrackQuery(title = title, artist = artist, album = album, durationMs = durationMs)

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri).put("docId", docId).put("parent", parentDocId).put("name", fileName).put("ext", ext)
        .put("size", size).put("mtime", lastModified).put("title", title)
        .putOpt("artist", artist).putOpt("album", album).putOpt("dur", durationMs)
        .put("emb", hasEmbeddedLyrics).put("meta", metadataLoaded)
        .put("cover", hasCover).put("review", needsReview)
        .put("identity", identity.name).put("swap", swapSuspected)
        .put("tags", tagsToJson(tags))

    companion object {
        fun fromJson(o: JSONObject) = Track(
            uri = o.getString("uri"),
            docId = o.getString("docId"),
            parentDocId = o.optString("parent"),
            fileName = o.getString("name"),
            ext = o.optString("ext"),
            size = o.optLong("size"),
            lastModified = o.optLong("mtime"),
            title = o.optString("title"),
            artist = o.optString("artist").ifBlank { null }.takeUnless { o.isNull("artist") },
            album = o.optString("album").ifBlank { null }.takeUnless { o.isNull("album") },
            durationMs = o.optLong("dur").takeIf { it > 0 },
            hasEmbeddedLyrics = o.optBoolean("emb"),
            metadataLoaded = o.optBoolean("meta"),
            tags = o.optJSONObject("tags")?.let(::tagsFromJson) ?: TrackTags(),
            hasCover = o.optBoolean("cover"),
            needsReview = o.optBoolean("review"),
            identity = Identity.entries.firstOrNull { it.name == o.optString("identity") } ?: Identity.TAGS,
            swapSuspected = o.optBoolean("swap"),
        )

        private fun tagsToJson(t: TrackTags): JSONObject = JSONObject()
            .putOpt("title", t.title).putOpt("album", t.album).put("artists", JSONArray(t.artists))
            .putOpt("albumArtist", t.albumArtist).put("composers", JSONArray(t.composers)).put("genres", JSONArray(t.genres))
            .putOpt("year", t.year).putOpt("track", t.trackNumber).putOpt("trackTotal", t.trackTotal)
            .putOpt("disc", t.discNumber).putOpt("discTotal", t.discTotal)

        private fun tagsFromJson(o: JSONObject): TrackTags {
            fun s(k: String) = if (o.isNull(k)) null else o.optString(k).ifBlank { null }
            fun i(k: String) = if (o.isNull(k) || !o.has(k)) null else o.optInt(k).takeIf { it > 0 }
            fun l(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return TrackTags(
                title = s("title"), album = s("album"), artists = l("artists"), albumArtist = s("albumArtist"),
                composers = l("composers"), genres = l("genres"), year = s("year"),
                trackNumber = i("track"), trackTotal = i("trackTotal"), discNumber = i("disc"), discTotal = i("discTotal"),
            )
        }

        /**
         * Sólo si el archivo no tiene etiquetas: título/artista a partir del nombre.
         * "01. Artista - Título.mp3" → (Título, Artista). Es una suposición; el título real
         * puede llevar guiones ("Undone - The Sweater Song"), por eso siempre mandan las etiquetas.
         */
        fun guessFromFileName(fileName: String): Pair<String, String?> {
            val base = fileName.substringBeforeLast('.')
                .replace('_', ' ')
                .replace(Regex("""^\s*\d{1,3}\s*[.\-)]?\s+"""), "")
                .trim()
            val parts = base.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                Matching.cleanTitle(parts[1]) to parts[0].trim()
            } else {
                base to null
            }
        }
    }
}
