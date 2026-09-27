package com.example.lrcfetcher.lyrics

/**
 * Modelo interno de una letra. Todos los proveedores se convierten a esto, y desde aquí
 * se genera cualquier salida (LRC palabra por palabra, LRC por línea o texto plano).
 */

enum class SyncType(val rank: Int) {
    WORD(3),
    LINE(2),
    PLAIN(1);
}

/** [text] incluye el espacio final cuando la palabra termina ahí (como hace Apple Music). */
data class LyricWord(val start: Long, val end: Long, val text: String)

data class LyricLine(
    val start: Long,
    val end: Long,
    val words: List<LyricWord>,
    val translation: String? = null,
    /** Romanización alineada (mismos cortes de tiempo que [words], o palabras propias). */
    val romanWords: List<LyricWord>? = null,
    /** Cantante: "v1" (principal), "v2", "v3"… null = principal. */
    val agent: String? = null,
    /** Voces de fondo / coros que acompañan a esta línea. */
    val background: List<LyricWord>? = null,
    val backgroundRoman: List<LyricWord>? = null,
) {
    val text: String get() = words.joinToString("") { it.text }.trim()
    val romanText: String? get() = romanWords?.joinToString("") { it.text }?.trim()
    val backgroundText: String? get() = background?.joinToString("") { it.text }?.trim()?.ifBlank { null }
    val backgroundRomanText: String? get() = backgroundRoman?.joinToString("") { it.text }?.trim()?.ifBlank { null }

    /** true si canta alguien distinto del cantante principal. */
    val isSecondaryVoice: Boolean get() = agent != null && agent != "v1"

    companion object {
        fun plain(start: Long, end: Long, text: String) =
            LyricLine(start, end, listOf(LyricWord(start, end, text)))
    }
}

data class Lyrics(
    val lines: List<LyricLine>,
    val sync: SyncType,
    val source: ProviderId,
    val sourceId: String,
    val language: String? = null,
    /** true si [LyricLine.romanWords] vienen del proveedor (romanización oficial). */
    val officialRomanization: Boolean = false,
) {
    val isEmpty: Boolean get() = lines.none { it.text.isNotBlank() }
    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrBlank() }
    /** Tiene dúos (v2…) o coros de fondo. */
    val hasVoices: Boolean get() = voiceMarks > 0
    /** Cuántas líneas marcan otro cantante o coros (para elegir la fuente más completa). */
    val voiceMarks: Int get() = lines.count { it.isSecondaryVoice || it.backgroundText != null }
    val plainText: String get() = lines.joinToString("\n") { l -> listOfNotNull(l.text, l.backgroundText).joinToString("\n") }
}

enum class TextMode { ORIGINAL, ROMANIZED, BOTH }

data class OutputOptions(
    val format: SyncType = SyncType.WORD,
    val textMode: TextMode = TextMode.ORIGINAL,
    val includeTranslation: Boolean = false,
    val offsetMs: Long = 0,
    /** Marcar cantantes ("v2:") y coros ("[bg: …]"), como Apple Music / AMLL. */
    val includeVoices: Boolean = true,
    /** true: [mm:ss.mmm]; false: [mm:ss.cc] (LRC clásico). */
    val millis: Boolean = true,
)

object LrcWriter {

    fun formatTime(ms: Long, millis: Boolean = false): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        return if (millis) {
            String.format(java.util.Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, safe % 1000)
        } else {
            String.format(java.util.Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, (safe % 1000) / 10)
        }
    }

    fun write(lyrics: Lyrics, options: OutputOptions): String {
        val format = if (options.format.rank > lyrics.sync.rank) lyrics.sync else options.format
        val out = StringBuilder()
        for (line in lyrics.lines) {
            val voice = if (options.includeVoices && line.isSecondaryVoice) "${line.agent}:" else ""
            val roman = line.romanWords?.takeIf { ws -> ws.any { it.text.isNotBlank() } }
            val bg = if (options.includeVoices) line.background?.takeIf { ws -> ws.any { it.text.isNotBlank() } } else null
            val bgRoman = line.backgroundRoman?.takeIf { ws -> ws.any { it.text.isNotBlank() } }

            fun main(words: List<LyricWord>) = out.append(renderLine(line.start, words, format, options, voice)).append('\n')
            fun background(words: List<LyricWord>) = out.append(renderBackground(words, format, options, voice)).append('\n')

            when (options.textMode) {
                TextMode.ORIGINAL -> {
                    main(line.words)
                    bg?.let(::background)
                }
                TextMode.ROMANIZED -> {
                    main(roman ?: line.words)
                    bg?.let { background(bgRoman ?: it) }
                }
                TextMode.BOTH -> {
                    main(line.words)
                    bg?.let(::background)
                    if (roman != null && roman.joinToString("") { it.text }.trim() != line.text) {
                        main(roman)
                        if (bg != null && bgRoman != null) background(bgRoman)
                    }
                }
            }
            if (options.includeTranslation && !line.translation.isNullOrBlank()) {
                val f = if (format == SyncType.WORD) SyncType.LINE else format
                out.append(renderLine(line.start, listOf(LyricWord(line.start, line.end, line.translation)), f, options, "")).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    /** " my" → el espacio pasa al final de la palabra anterior ("Oh " + "my"), como en el LRC de Apple. */
    private fun moveLeadingSpaces(words: List<LyricWord>): List<LyricWord> {
        val out = words.toMutableList()
        for (i in 1 until out.size) {
            val w = out[i]
            if (w.text.isNotEmpty() && w.text.first().isWhitespace()) {
                out[i] = w.copy(text = w.text.trimStart())
                if (!out[i - 1].text.endsWith(' ')) out[i - 1] = out[i - 1].copy(text = out[i - 1].text + " ")
            }
        }
        return out
    }

    private fun wordTags(words: List<LyricWord>, options: OutputOptions): String {
        val visible = moveLeadingSpaces(words.filter { it.text.isNotEmpty() }).filter { it.text.isNotEmpty() }
        if (visible.isEmpty()) return ""
        val sb = StringBuilder()
        visible.forEachIndexed { i, w ->
            val t = if (i == visible.lastIndex) w.text.trimEnd() else w.text
            sb.append('<').append(formatTime(w.start + options.offsetMs, options.millis)).append('>').append(t)
        }
        sb.append('<').append(formatTime(visible.last().end + options.offsetMs, options.millis)).append('>')
        return sb.toString()
    }

    private fun renderLine(start: Long, words: List<LyricWord>, format: SyncType, options: OutputOptions, voice: String): String {
        val text = words.joinToString("") { it.text }.trim()
        val stamp = "[${formatTime(start + options.offsetMs, options.millis)}]"
        return when (format) {
            SyncType.PLAIN -> text
            SyncType.LINE -> "$stamp$voice$text"
            SyncType.WORD -> stamp + voice + wordTags(words, options)
        }
    }

    /** Coros: "[bg: <tiempos>texto]" (o "[bg: v2:…]" si son de otro cantante). */
    private fun renderBackground(words: List<LyricWord>, format: SyncType, options: OutputOptions, voice: String): String {
        val text = words.joinToString("") { it.text }.trim()
        return when (format) {
            SyncType.PLAIN -> "($text)"
            SyncType.LINE -> "[bg: $voice$text]"
            SyncType.WORD -> "[bg: $voice${wordTags(words, options)}]"
        }
    }
}
