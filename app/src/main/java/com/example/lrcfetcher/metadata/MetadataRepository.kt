package com.example.lrcfetcher.metadata

import com.example.lrcfetcher.lyrics.Matching
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.tags.TrackTags
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resultado de buscar metadatos de una canción en todos los repositorios. */
data class MetadataResult(
    /** El mejor candidato de cada repositorio (ya con detalles). */
    val bySource: Map<MetaSource, MetadataCandidate>,
    /** Candidato principal: el que mejor coincide (título, artista y duración). */
    val primary: MetadataCandidate?,
    /** Metadatos combinados: los del principal, completados con los demás repositorios. */
    val merged: TrackTags?,
    val covers: List<CoverOption>,
    /** Título y artista del principal coinciden de forma estricta con los de la canción. */
    val strictMatch: Boolean,
    /** La coincidencia sólo se dio con título y artista intercambiados (etiquetas al revés). */
    val swapped: Boolean = false,
)

object MetadataRepository {
    private const val MIN_SCORE = 0.6
    private val sources = listOf(DeezerSource, ITunesSource, MusicBrainzSource)
    private val priority = listOf(MetaSource.DEEZER, MetaSource.ITUNES, MetaSource.MUSICBRAINZ)

    /**
     * Busca con título/artista tal cual y, si nada coincide, prueba intercambiándolos: así se
     * detectan archivos con las etiquetas al revés.
     */
    suspend fun search(query: TrackQuery): MetadataResult {
        val direct = searchOnce(query)
        if (direct.strictMatch || query.artist.isNullOrBlank()) return direct
        val swappedQuery = query.copy(title = query.artist!!, artist = query.title)
        val swapped = searchOnce(swappedQuery)
        return if (swapped.strictMatch) swapped.copy(swapped = true) else direct
    }

    private suspend fun searchOnce(query: TrackQuery): MetadataResult = coroutineScope {
        val found = sources.map { src ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(30_000) {
                    runCatching {
                        val ranked = src.search(query)
                            .map { it to rank(query, it) }
                            .filter { it.second >= MIN_SCORE }
                            .sortedByDescending { it.second }
                        val best = ranked.firstOrNull()?.first ?: return@runCatching null
                        runCatching { src.details(best) }.getOrDefault(best)
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
        combine(query, found)
    }

    /** Puntuación + pequeña preferencia por candidatos cuya duración confirma la canción. */
    private fun rank(query: TrackQuery, c: MetadataCandidate): Double {
        val base = Matching.score(query, c.tags.title.orEmpty(), c.tags.artist, c.durationMs)
        val q = query.durationMs ?: return base
        return when {
            c.durationMs == null -> base * 0.95
            abs(c.durationMs - q) <= 3_000 -> base + 0.05
            else -> base
        }
    }

    internal fun combine(query: TrackQuery, found: List<MetadataCandidate>): MetadataResult {
        val bySource = found.associateBy { it.source }
        // Principal: preferir el que pasa la coincidencia estricta, luego el de mejor puntuación.
        val primary = found.sortedWith(
            compareByDescending<MetadataCandidate> { isStrictMatch(query, it) }
                .thenByDescending { Matching.score(query, it.tags.title.orEmpty(), it.tags.artist, it.durationMs) }
                .thenBy { priority.indexOf(it.source) },
        ).firstOrNull()
        val merged = primary?.let { p ->
            var t = p.tags
            // Mismo álbum en otro repositorio → completar lo que falte (p. ej. total de discos).
            for (other in found.sortedBy { priority.indexOf(it.source) }) {
                if (other === p) continue
                val sameAlbum = t.album != null && other.tags.album != null &&
                    Matching.similarity(Matching.cleanTitle(t.album!!), Matching.cleanTitle(other.tags.album!!)) >= 0.85
                t = t.copy(
                    // Compositores y géneros no dependen de la edición.
                    composers = t.composers.ifEmpty { other.tags.composers },
                    genres = t.genres.ifEmpty { other.tags.genres },
                )
                if (sameAlbum) {
                    t = t.copy(
                        albumArtist = t.albumArtist ?: other.tags.albumArtist,
                        trackTotal = t.trackTotal ?: other.tags.trackTotal,
                        discNumber = t.discNumber ?: other.tags.discNumber,
                        discTotal = t.discTotal ?: other.tags.discTotal,
                        year = t.year ?: other.tags.year,
                    )
                }
            }
            t.normalized()
        }
        val covers = (listOfNotNull(primary) + found.filter { it !== primary })
            .flatMap { it.covers }.distinctBy { it.url }
        return MetadataResult(bySource, primary, merged, covers, primary?.let { isStrictMatch(query, it) } == true)
    }

    /**
     * Regla del lote: el título y el artista del resultado deben coincidir con los de la canción
     * (y la duración, si se conoce, no puede diferir más de 10 s: evita versiones en vivo o demos).
     */
    fun isStrictMatch(query: TrackQuery, c: MetadataCandidate): Boolean {
        val qArtist = query.artist?.takeIf { it.isNotBlank() } ?: return false
        val title = c.tags.title ?: return false
        val titleOk = Matching.normalize(query.title) == Matching.normalize(title) ||
            Matching.similarity(Matching.cleanTitle(query.title), Matching.cleanTitle(title)) >= 0.92 &&
            Matching.similarity(query.title, title) >= 0.8
        if (!titleOk) return false
        val artists = c.tags.artists + listOfNotNull(c.tags.artist)
        val artistOk = artists.any { a ->
            Matching.similarity(qArtist, a) >= 0.85 ||
                Matching.similarity(Matching.cleanArtist(qArtist), Matching.cleanArtist(a)) >= 0.9
        } || Matching.score(TrackQuery(title, qArtist), title, c.tags.artist, null) >= 0.9
        if (!artistOk) return false
        val d1 = query.durationMs
        val d2 = c.durationMs
        return d1 == null || d2 == null || abs(d1 - d2) <= 10_000
    }

    /**
     * Cambios para el lote: se conservan título y artistas del archivo (ya coinciden) y, salvo
     * [overwrite], sólo se rellenan los campos vacíos. Una fecha más completa del mismo año no se pisa.
     */
    fun batchTags(current: TrackTags, found: TrackTags, overwrite: Boolean): TrackTags {
        val base = if (overwrite) {
            found.copy(title = current.title ?: found.title, artists = current.artists.ifEmpty { found.artists }).fillFrom(current)
        } else {
            current.fillFrom(found)
        }
        val year = if (current.yearNumber != null && current.yearNumber == found.yearNumber) current.year else base.year
        return base.copy(year = year).normalized()
    }

    suspend fun downloadImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", com.example.lrcfetcher.AppInfo.USER_AGENT)
            try {
                if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
