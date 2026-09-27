package com.example.lrcfetcher.tags

/**
 * Los metadatos que Lyricota lee y escribe. Las listas (artistas, compositores, géneros) se
 * guardan como varios valores en el archivo (FLAC/ID3v2.4) o separados por "; " (ID3v2.3/MP4).
 */
data class TrackTags(
    val title: String? = null,
    val album: String? = null,
    val artists: List<String> = emptyList(),
    val albumArtist: String? = null,
    val composers: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    /** Tal como viene ("1994" o "1994-05-10"). */
    val year: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
) {
    val artist: String? get() = artists.joinToString("; ").ifBlank { null }
    val yearNumber: String? get() = year?.let { Regex("""\d{4}""").find(it)?.value }

    /** Faltan campos importantes (para el filtro "sin metadatos"). */
    val isIncomplete: Boolean
        get() = title.isNullOrBlank() || artists.isEmpty() || album.isNullOrBlank() ||
            yearNumber == null || trackNumber == null || genres.isEmpty()

    fun normalized(): TrackTags = copy(
        title = title?.trim()?.ifBlank { null },
        album = album?.trim()?.ifBlank { null },
        artists = artists.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        albumArtist = albumArtist?.trim()?.ifBlank { null },
        composers = composers.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        genres = genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        year = year?.trim()?.ifBlank { null },
        trackNumber = trackNumber?.takeIf { it > 0 },
        trackTotal = trackTotal?.takeIf { it > 0 },
        discNumber = discNumber?.takeIf { it > 0 },
        discTotal = discTotal?.takeIf { it > 0 },
    )

    /** Completa lo que falte aquí con los valores de [other]. */
    fun fillFrom(other: TrackTags): TrackTags = copy(
        title = title ?: other.title,
        album = album ?: other.album,
        artists = artists.ifEmpty { other.artists },
        albumArtist = albumArtist ?: other.albumArtist,
        composers = composers.ifEmpty { other.composers },
        genres = genres.ifEmpty { other.genres },
        year = year ?: other.year,
        trackNumber = trackNumber ?: other.trackNumber,
        trackTotal = trackTotal ?: other.trackTotal,
        discNumber = discNumber ?: other.discNumber,
        discTotal = discTotal ?: other.discTotal,
    )

    companion object {
        /** "A; B" / "A;B" → [A, B]. */
        fun splitList(value: String?): List<String> =
            value?.split(';', '\u0000')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        /** "5/10" → (5, 10); "5" → (5, null). */
        fun parsePair(value: String?): Pair<Int?, Int?> {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null to null
            val parts = v.split('/')
            return parts[0].trim().toIntOrNull() to parts.getOrNull(1)?.trim()?.toIntOrNull()
        }
    }
}

/** Portada: bytes de la imagen y su tipo MIME. */
class Cover(val data: ByteArray, val mime: String) {
    companion object {
        fun detectMime(data: ByteArray): String = when {
            data.size > 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() -> "image/png"
            else -> "image/jpeg"
        }
    }
}

/** Lo que se leyó de un archivo. */
data class TagInfo(
    val tags: TrackTags = TrackTags(),
    val hasCover: Boolean = false,
    val hasLyrics: Boolean = false,
)

/** Cambios a escribir: null = no tocar esa parte. */
data class TagChanges(
    val tags: TrackTags? = null,
    val cover: Cover? = null,
    val lyrics: String? = null,
)
