package com.example.lrcfetcher.romanization

/**
 * Romanización Revisada del coreano, siguiendo la pronunciación real (como se canta):
 * enlace de consonante final (믿어 → mideo), nasalización (합니다 → hamnida),
 * asimilación de ㄹ (신라 → silla), aspiración con ㅎ (이렇게 → ireoke, 좋다 → jota)
 * y palatalización (같이 → gachi). Se respeta el espaciado original entre palabras.
 */
object KoreanRomanizer : SegmentRomanizer {

    private val INI = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MED = arrayOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
    )

    // Índices de consonante final
    private const val F_NONE = 0; private const val F_G = 1; private const val F_KK = 2; private const val F_GS = 3
    private const val F_N = 4; private const val F_NJ = 5; private const val F_NH = 6; private const val F_D = 7
    private const val F_L = 8; private const val F_LG = 9; private const val F_LM = 10; private const val F_LB = 11
    private const val F_LS = 12; private const val F_LT = 13; private const val F_LP = 14; private const val F_LH = 15
    private const val F_M = 16; private const val F_B = 17; private const val F_BS = 18; private const val F_S = 19
    private const val F_SS = 20; private const val F_NG = 21; private const val F_J = 22; private const val F_CH = 23
    private const val F_K = 24; private const val F_T = 25; private const val F_P = 26; private const val F_H = 27

    // Índices de consonante inicial
    private const val I_G = 0; private const val I_N = 2; private const val I_D = 3; private const val I_R = 5
    private const val I_M = 6; private const val I_S = 9; private const val I_SILENT = 11; private const val I_J = 12
    private const val I_H = 18
    private const val V_I = 20

    /** Sonido representativo de cada final al cerrar sílaba. */
    private val FIN = arrayOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l",
        "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t",
    )

    /** Consonante que pasa a la sílaba siguiente cuando ésta empieza por vocal: (queda, pasa). */
    private val LIAISON: Map<Int, Pair<String, String>> = mapOf(
        F_G to ("" to "g"), F_KK to ("" to "kk"), F_GS to ("k" to "s"), F_N to ("" to "n"), F_NJ to ("n" to "j"),
        F_NH to ("" to "n"), F_D to ("" to "d"), F_L to ("" to "r"), F_LG to ("l" to "g"), F_LM to ("l" to "m"),
        F_LB to ("l" to "b"), F_LS to ("l" to "s"), F_LT to ("l" to "t"), F_LP to ("l" to "p"), F_LH to ("" to "r"),
        F_M to ("" to "m"), F_B to ("" to "b"), F_BS to ("p" to "s"), F_S to ("" to "s"), F_SS to ("" to "ss"),
        F_J to ("" to "j"), F_CH to ("" to "ch"), F_K to ("" to "k"), F_T to ("" to "t"), F_P to ("" to "p"),
    )

    override fun romanize(segments: List<String>): List<String> {
        val text = TextNorm.normalizeCharwise(segments.joinToString(""))
        val n = text.length
        val out = Array(n) { "" }
        var i = 0
        while (i < n) {
            if (!TextNorm.isHangul(text[i])) {
                out[i] = if (text[i].isWhitespace()) " " else TextNorm.latinize(text[i].toString())
                i++
                continue
            }
            var j = i
            while (j < n && TextNorm.isHangul(text[j])) j++
            romanizeRun(text, i, j, out)
            i = j
        }
        return TextNorm.assembleSegments(segments, out, BooleanArray(n))
    }

    private fun romanizeRun(text: String, from: Int, to: Int, out: Array<String>) {
        val len = to - from
        val ini = IntArray(len)
        val med = IntArray(len)
        val fin = IntArray(len)
        for (k in 0 until len) {
            val code = text[from + k].code - 0xAC00
            ini[k] = code / 588
            med[k] = (code % 588) / 28
            fin[k] = code % 28
        }
        val iniR = Array(len) { INI[ini[it]] }
        val finR = Array(len) { FIN[fin[it]] }

        for (k in 0 until len - 1) {
            val f = fin[k]
            val nextIni = ini[k + 1]
            if (f == F_NONE) continue
            when {
                nextIni == I_SILENT -> when (f) {
                    F_NG -> Unit
                    F_H -> finR[k] = ""
                    else -> LIAISON[f]?.let { (stay, move) ->
                        finR[k] = stay
                        iniR[k + 1] = when {
                            med[k + 1] == V_I && f == F_D -> "j"
                            med[k + 1] == V_I && f == F_T -> "ch"
                            else -> move
                        }
                    }
                }
                nextIni == I_H && f != F_H && f != F_NH && f != F_LH -> when (FIN[f]) {
                    "k" -> { finR[k] = if (f == F_LG) "l" else ""; iniR[k + 1] = "k" }
                    "t" -> { finR[k] = ""; iniR[k + 1] = if (med[k + 1] == V_I || f == F_J || f == F_CH) "ch" else "t" }
                    "p" -> { finR[k] = if (f == F_LP || f == F_LB) "l" else ""; iniR[k + 1] = "p" }
                    else -> Unit // ㄴ/ㄹ/ㅁ/ㅇ + ㅎ: se conserva la h
                }
                f == F_H || f == F_NH || f == F_LH -> {
                    val keep = when (f) { F_NH -> "n"; F_LH -> "l"; else -> "" }
                    when (nextIni) {
                        I_G -> { finR[k] = keep; iniR[k + 1] = "k" }
                        I_D -> { finR[k] = keep; iniR[k + 1] = "t" }
                        I_J -> { finR[k] = keep; iniR[k + 1] = "ch" }
                        I_S -> { finR[k] = keep; iniR[k + 1] = "ss" }
                        I_N -> if (f == F_LH) { finR[k] = "l"; iniR[k + 1] = "l" } else { finR[k] = "n"; iniR[k + 1] = "n" }
                        else -> finR[k] = keep.ifEmpty { "t" }
                    }
                }
                else -> {
                    val sound = finR[k]
                    when (nextIni) {
                        I_N, I_M -> finR[k] = nasal(sound)
                        I_R -> when (sound) {
                            "n", "l" -> { finR[k] = "l"; iniR[k + 1] = "l" }
                            "k", "t", "p" -> { finR[k] = nasal(sound); iniR[k + 1] = "n" }
                            "m", "ng" -> iniR[k + 1] = "n"
                        }
                    }
                    if (sound == "l" && nextIni == I_N) iniR[k + 1] = "l"
                }
            }
        }
        for (k in 0 until len) out[from + k] = iniR[k] + MED[med[k]] + finR[k]
    }

    private fun nasal(sound: String) = when (sound) {
        "k" -> "ng"
        "t" -> "n"
        "p" -> "m"
        else -> sound
    }
}
