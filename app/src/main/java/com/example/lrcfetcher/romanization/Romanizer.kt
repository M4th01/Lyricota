package com.example.lrcfetcher.romanization

import com.example.lrcfetcher.lyrics.LyricWord
import com.example.lrcfetcher.lyrics.Lyrics
import com.ibm.icu.text.Transliterator

enum class Script { LATIN, JAPANESE, KOREAN, CHINESE, OTHER }

/** Punto de entrada: detecta el idioma de TODA la letra y romaniza línea a línea. */
object Romanizer {

    private object OtherScripts : SegmentRomanizer {
        private val tr: Transliterator by lazy { Transliterator.getInstance("Any-Latin; Latin-ASCII") }
        override fun romanize(segments: List<String>): List<String> =
            segments.map { runCatching { tr.transliterate(it) }.getOrDefault(it) }
    }

    /**
     * Se decide por canción, no por línea: un verso japonés sólo con kanji (p. ej. 一番星)
     * debe leerse en japonés aunque no tenga kana.
     */
    fun detect(lyrics: Lyrics): Script = detect(lyrics.plainText, lyrics.language)

    fun detect(text: String, language: String? = null): Script {
        var kana = 0; var hangul = 0; var han = 0; var other = 0
        for (c in text) {
            when {
                KanaRomaji.isKana(c) && c != 'ー' -> kana++
                TextNorm.isHangul(c) -> hangul++
                TextNorm.isHan(c) -> han++
                Character.isLetter(c) && c.code >= 0x250 -> other++
            }
        }
        val lang = language?.lowercase()?.substringBefore('-')
        return when {
            lang == "ja" && (kana + han) > 0 -> Script.JAPANESE
            lang == "ko" && hangul > 0 -> Script.KOREAN
            kana > 0 && kana * 5 >= hangul -> Script.JAPANESE
            hangul > 0 && hangul >= han -> Script.KOREAN
            han > 0 -> Script.CHINESE
            other > 0 -> Script.OTHER
            else -> Script.LATIN
        }
    }

    fun needsRomanization(lyrics: Lyrics): Boolean = detect(lyrics) != Script.LATIN

    private fun engine(script: Script): SegmentRomanizer = when (script) {
        Script.JAPANESE -> JapaneseRomanizer
        Script.KOREAN -> KoreanRomanizer
        Script.CHINESE -> ChineseRomanizer
        else -> OtherScripts
    }

    /** Romaniza un texto suelto (para búsquedas, títulos, pruebas). */
    fun romanizeText(text: String, language: String? = null): String {
        val script = detect(text, language)
        if (script == Script.LATIN) return text
        return text.lines().joinToString("\n") { engine(script).romanize(listOf(it)).first() }
    }

    /**
     * Rellena [com.example.lrcfetcher.lyrics.LyricLine.romanWords]. Si el proveedor ya trae
     * romanización oficial (Apple Music) se respeta tal cual.
     */
    fun romanize(lyrics: Lyrics): Lyrics {
        val script = detect(lyrics)
        if (script == Script.LATIN) return lyrics
        val engine = engine(script)

        fun roman(words: List<LyricWord>): List<LyricWord> {
            val texts = runCatching { engine.romanize(words.map { it.text }) }.getOrElse { words.map { w -> w.text } }
            return words.zip(texts) { w, t -> LyricWord(w.start, w.end, t) }
        }

        val lines = lyrics.lines.map { line ->
            // La romanización oficial (Apple) se respeta; sólo se completan los coros si faltan.
            val main = when {
                line.text.isBlank() -> line.romanWords
                lyrics.officialRomanization && line.romanWords != null -> line.romanWords
                else -> roman(line.words)
            }
            val bg = line.background?.takeIf { line.backgroundRoman == null && line.backgroundText != null }?.let(::roman)
                ?: line.backgroundRoman
            line.copy(romanWords = main, backgroundRoman = bg)
        }
        return lyrics.copy(lines = lines)
    }
}
