package com.example.lrcfetcher.romanization

/**
 * Kana → romaji (Hepburn, con vocales largas escritas como se deletrean: "kyou", "suupaa"),
 * que es el estilo que usa Apple Music para sus romanizaciones oficiales.
 *
 * [convert] devuelve, para cada kana de entrada, el romaji que "le toca", de modo que se
 * pueda repartir entre segmentos de karaoke sin perder la alineación.
 */
object KanaRomaji {

    private val single: Map<Char, String> = buildMap {
        val rows = listOf(
            "アイウエオ" to listOf("a", "i", "u", "e", "o"),
            "カキクケコ" to listOf("ka", "ki", "ku", "ke", "ko"),
            "サシスセソ" to listOf("sa", "shi", "su", "se", "so"),
            "タチツテト" to listOf("ta", "chi", "tsu", "te", "to"),
            "ナニヌネノ" to listOf("na", "ni", "nu", "ne", "no"),
            "ハヒフヘホ" to listOf("ha", "hi", "fu", "he", "ho"),
            "マミムメモ" to listOf("ma", "mi", "mu", "me", "mo"),
            "ヤユヨ" to listOf("ya", "yu", "yo"),
            "ラリルレロ" to listOf("ra", "ri", "ru", "re", "ro"),
            "ワヰヱヲン" to listOf("wa", "i", "e", "o", "n"),
            "ガギグゲゴ" to listOf("ga", "gi", "gu", "ge", "go"),
            "ザジズゼゾ" to listOf("za", "ji", "zu", "ze", "zo"),
            "ダヂヅデド" to listOf("da", "ji", "zu", "de", "do"),
            "バビブベボ" to listOf("ba", "bi", "bu", "be", "bo"),
            "パピプペポ" to listOf("pa", "pi", "pu", "pe", "po"),
            "ァィゥェォ" to listOf("a", "i", "u", "e", "o"),
            "ャュョヮ" to listOf("ya", "yu", "yo", "wa"),
            "ヴヵヶ" to listOf("vu", "ka", "ke"),
        )
        for ((chars, romas) in rows) chars.forEachIndexed { i, c -> put(c, romas[i]) }
    }

    private val digraph: Map<String, String> = buildMap {
        fun yoon(base: Char, stem: String) {
            put("${base}ャ", "${stem}a"); put("${base}ュ", "${stem}u"); put("${base}ョ", "${stem}o")
        }
        yoon('キ', "ky"); yoon('ギ', "gy"); yoon('ニ', "ny"); yoon('ヒ', "hy"); yoon('ビ', "by")
        yoon('ピ', "py"); yoon('ミ', "my"); yoon('リ', "ry")
        yoon('シ', "sh"); yoon('ジ', "j"); yoon('チ', "ch"); yoon('ヂ', "j")
        put("キェ", "kye"); put("ギェ", "gye"); put("ニェ", "nye"); put("ヒェ", "hye")
        put("シェ", "she"); put("ジェ", "je"); put("チェ", "che")
        put("ファ", "fa"); put("フィ", "fi"); put("フェ", "fe"); put("フォ", "fo"); put("フュ", "fyu")
        put("ヴァ", "va"); put("ヴィ", "vi"); put("ヴェ", "ve"); put("ヴォ", "vo"); put("ヴュ", "vyu")
        put("ティ", "ti"); put("ディ", "di"); put("トゥ", "tu"); put("ドゥ", "du")
        put("テュ", "tyu"); put("デュ", "dyu")
        put("ウィ", "wi"); put("ウェ", "we"); put("ウォ", "wo")
        put("ツァ", "tsa"); put("ツィ", "tsi"); put("ツェ", "tse"); put("ツォ", "tso")
        put("イェ", "ye"); put("スィ", "si"); put("ズィ", "zi")
        put("クァ", "kwa"); put("クィ", "kwi"); put("クェ", "kwe"); put("クォ", "kwo"); put("グァ", "gwa")
    }

    private const val SMALL = "ァィゥェォャュョヮ"
    private const val VOWELS = "aeiou"

    fun toKatakana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'ぁ'..'ゖ' || c in 'ゝ'..'ゞ') c + 0x60 else c)
    }

    fun isKana(c: Char): Boolean = c in 'ぁ'..'ゟ' || c in '゠'..'ヿ' || c == 'ー'

    /** Convierte kana (hiragana o katakana) a una sola cadena. */
    fun toRomaji(kana: String): String = convert(kana).joinToString("")

    /** Una salida por cada carácter de entrada (vacía para el 2.º carácter de un dígrafo). */
    fun convert(kana: String): Array<String> {
        val k = toKatakana(kana)
        val out = Array(k.length) { "" }
        var i = 0
        while (i < k.length) {
            val c = k[i]
            when {
                c == 'ッ' -> {
                    val next = syllableAt(k, i + 1)?.first
                    out[i] = when {
                        next == null -> ""
                        next.startsWith("ch") -> "t"
                        next[0] !in VOWELS && next[0] != 'n' -> next[0].toString()
                        else -> ""
                    }
                    i++
                }
                c == 'ー' -> {
                    val prev = (i - 1 downTo 0).firstNotNullOfOrNull { j -> out[j].lastOrNull { it in VOWELS } }
                    out[i] = prev?.toString() ?: ""
                    i++
                }
                c == 'ン' -> {
                    val next = syllableAt(k, i + 1)?.first
                    out[i] = if (next != null && (next[0] in VOWELS || next[0] == 'y')) "n'" else "n"
                    i++
                }
                else -> {
                    val syl = syllableAt(k, i)
                    if (syl == null) {
                        out[i] = c.toString()
                        i++
                    } else {
                        out[i] = syl.first
                        i += syl.second
                    }
                }
            }
        }
        return out
    }

    /** Sílaba que empieza en [i]: (romaji, cuántos caracteres consume). */
    private fun syllableAt(k: String, i: Int): Pair<String, Int>? {
        if (i >= k.length) return null
        val c = k[i]
        if (i + 1 < k.length && k[i + 1] in SMALL) {
            digraph["$c${k[i + 1]}"]?.let { return it to 2 }
        }
        if (c == 'ッ' || c == 'ー') return null
        return single[c]?.let { it to 1 }
    }
}
