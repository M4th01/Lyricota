package com.example.lrcfetcher.library

import com.example.lrcfetcher.tags.TrackTags

/** Formas de nombrar los archivos a partir de sus etiquetas. */
enum class RenamePattern(val example: String) {
    ARTIST_TITLE("Weezer - Buddy Holly"),
    TITLE_ARTIST("Buddy Holly - Weezer"),
    TRACK_TITLE("04 - Buddy Holly"),
    TRACK_ARTIST_TITLE("04 - Weezer - Buddy Holly");

    /** Nombre sin extensión; "" si faltan datos. */
    fun format(t: TrackTags): String {
        val title = t.title?.trim().orEmpty()
        val artist = t.artists.joinToString(", ").trim()
        val track = t.trackNumber?.let { "%02d".format(it) }
        val parts = when (this) {
            ARTIST_TITLE -> listOf(artist, title)
            TITLE_ARTIST -> listOf(title, artist)
            TRACK_TITLE -> listOf(track ?: return "", title)
            TRACK_ARTIST_TITLE -> listOf(track ?: return "", artist, title)
        }
        if (parts.any { it.isBlank() }) return ""
        return sanitize(parts.joinToString(" - "))
    }

    companion object {
        private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

        /** Caracteres que Android/FAT no admiten en nombres; espacios repetidos; máx. 180 caracteres. */
        fun sanitize(name: String): String = name.replace(Regex("""\s+"""), " ")
            .replace(forbidden, "_")
            .trim().trimEnd('.')
            .take(180)
    }
}
