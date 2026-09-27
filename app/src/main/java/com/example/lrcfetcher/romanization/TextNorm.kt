package com.example.lrcfetcher.romanization

import com.ibm.icu.text.Transliterator
import java.text.Normalizer

/** Romaniza una línea ya cortada en segmentos (palabras/sílabas de karaoke). */
interface SegmentRomanizer {
    /** Devuelve un texto por segmento; los espacios entre palabras ya van incluidos. */
    fun romanize(segments: List<String>): List<String>
}

internal object TextNorm {

    private val anyLatin: Transliterator by lazy { Transliterator.getInstance("Any-Latin; Latin-ASCII") }

    private val punctuation = mapOf(
        '、' to ",", '。' to ".", '，' to ",", '．' to ".", '！' to "!", '？' to "?", '：' to ":", '；' to ";",
        '「' to "\"", '」' to "\"", '『' to "\"", '』' to "\"", '“' to "\"", '”' to "\"", '‘' to "'", '’' to "'",
        '（' to "(", '）' to ")", '【' to "[", '】' to "]", '〈' to "<", '〉' to ">", '《' to "\"", '》' to "\"",
        '・' to "", '…' to "...", '‥' to "..", '～' to "~", '〜' to "~", '〃' to "\"", '　' to " ",
    )

    private const val OPENING = "「『（(“‘【〈《[\"¿¡"

    fun isOpening(s: String): Boolean = s.isNotEmpty() && s.all { it in OPENING }

    fun isHan(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF || c == '々' || c == '〆' || c == 'ヶ'

    fun isJapaneseChar(c: Char): Boolean = KanaRomaji.isKana(c) || isHan(c)

    fun isHangul(c: Char): Boolean = c.code in 0xAC00..0xD7A3

    /** NFKC carácter a carácter: mantiene la longitud para no romper la alineación. */
    fun normalizeCharwise(s: String): String = buildString(s.length) {
        for (c in s) {
            val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFKC)
            append(if (n.length == 1) n[0] else c)
        }
    }

    /** Puntuación CJK → ASCII; letras/dígitos latinos tal cual; otros alfabetos vía ICU. */
    fun latinize(s: String): String = buildString {
        for (c in s) {
            val p = punctuation[c]
            when {
                p != null -> append(p)
                c.code < 0x250 || c.isWhitespace() -> append(c)
                Character.getType(c).let {
                    it == Character.OTHER_SYMBOL.toInt() || it == Character.MATH_SYMBOL.toInt()
                } -> append(c)
                else -> append(runCatching { anyLatin.transliterate(c.toString()) }.getOrDefault(c.toString()))
            }
        }
    }

    /**
     * Reparte la salida por carácter [out] entre los segmentos originales. Un espacio de
     * separación de palabra se pone al final del segmento anterior (como en el LRC de Apple:
     * "I <00:27.549>been"), o dentro del segmento si la palabra empieza a mitad de él.
     */
    fun assembleSegments(segments: List<String>, out: Array<String>, wordStart: BooleanArray): List<String> {
        val res = List(segments.size) { StringBuilder() }
        var base = 0
        var anyOutput = false
        var lastOwner = -1
        for ((s, seg) in segments.withIndex()) {
            for (k in seg.indices) {
                val i = base + k
                if (i >= out.size) break
                val piece = out[i]
                if (piece.isEmpty()) continue
                if (wordStart[i] && anyOutput && !piece.startsWith(' ')) {
                    // Si este segmento aún no tiene texto, el espacio va al final del anterior.
                    val target = if (res[s].isEmpty()) res[lastOwner] else res[s]
                    if (!target.endsWith(' ')) target.append(' ')
                }
                res[s].append(piece)
                anyOutput = true
                lastOwner = s
            }
            base += seg.length
        }
        return res.map { it.toString().replace(Regex(" {2,}"), " ") }.also { list ->
            // Sin espacio colgando al final de la línea.
            val lastIdx = list.indexOfLast { it.isNotBlank() }
            if (lastIdx >= 0) return list.mapIndexed { i, t -> if (i == lastIdx) t.trimEnd() else t }
        }
    }
}
