package com.example.lrcfetcher.romanization

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Romanización de japonés:
 *  1. Kuromoji analiza la línea COMPLETA (el contexto decide la lectura de cada kanji).
 *  2. Se corrigen lecturas y cortes típicos de letras (一人 → hitori, 本当は → hontou wa…).
 *  3. La lectura de cada token se reparte carácter a carácter (furigana), para que al
 *     cortar la línea en segmentos de karaoke cada sílaba quede con su tiempo.
 *  4. Los tokens se agrupan en palabras como en el romaji habitual (estilo Apple Music):
 *     "tabeta", "shiritai", "nuketeru", "shizumidashita", pero "kimi wa", "ubawarete iku".
 *  5. Partículas は/へ/を → wa/e/o. っ, ー y ん se resuelven dentro de cada palabra.
 */
object JapaneseRomanizer : SegmentRomanizer {

    private val tokenizer: Tokenizer by lazy { Tokenizer() }

    fun warmUp() {
        tokenizer.tokenize("初期化")
    }

    /** Token propio: permite partir/unir/corregir lo que devuelve Kuromoji. */
    private data class Tok(
        val surface: String,
        val reading: String?,
        val pos1: String,
        val pos2: String,
        val pos3: String,
        val conj: String,
        val conjType: String,
        val base: String,
        val position: Int,
    )

    /** Lecturas que el diccionario resuelve mal en letras de canciones. */
    private val surfaceReadings = mapOf(
        "一人" to "ヒトリ", "二人" to "フタリ", "一人きり" to "ヒトリキリ", "二人きり" to "フタリキリ",
        "昏い" to "クライ", "明日" to "アシタ", "今日" to "キョウ", "大人" to "オトナ", "一日" to "イチニチ",
        "何処" to "ドコ", "此処" to "ココ", "其処" to "ソコ", "貴方" to "アナタ", "貴女" to "アナタ",
        "私達" to "ワタシタチ", "僕等" to "ボクラ", "僕ら" to "ボクラ", "君達" to "キミタチ", "身体" to "カラダ",
        "永遠" to "エイエン", "宇宙" to "ウチュウ", "運命" to "ウンメイ", "未来" to "ミライ", "瞬間" to "シュンカン",
    )

    /** Segundo verbo de un verbo compuesto (連用形 + V2): se escribe pegado. */
    private val compoundV2 = setOf(
        "出す", "だす", "切る", "きる", "合う", "あう", "合える", "得る", "える", "止む", "やむ", "込む", "こむ",
        "始める", "はじめる", "続ける", "つづける", "終わる", "おわる", "抜く", "ぬく", "抜ける", "付く", "つく",
        "付ける", "つける", "上がる", "あがる", "上げる", "あげる", "返す", "かえす", "直す", "なおす", "過ぎる",
        "すぎる", "尽くす", "尽きる", "回る", "まわる", "回す", "渡る", "去る", "寄せる", "立てる", "損なう",
        "かける", "掛ける", "放つ", "舞う", "消える", "忘れる", "落ちる", "続く", "果てる", "疲れる", "通す",
    )

    private val interrogatives = setOf("誰", "だれ", "何", "なに", "なん", "どこ", "何処", "いつ", "どれ", "どちら")

    override fun romanize(segments: List<String>): List<String> {
        val original = segments.joinToString("")
        if (original.isBlank()) return segments.map { "" }
        val text = TextNorm.normalizeCharwise(original)
        val tokens = rewrite(tokenize(text))

        val n = text.length
        val kana = arrayOfNulls<String>(n)
        val wordStart = BooleanArray(n)

        var prev: Tok? = null
        for (tok in tokens) {
            val start = tok.position
            val surface = tok.surface
            if (start < 0 || start + surface.length > n) continue
            if (surface.isBlank()) {
                prev = null
                continue
            }
            val readings = readingsFor(tok)
            for (k in surface.indices) kana[start + k] = readings[k]
            if (startsNewWord(tok, prev)) wordStart[start] = true
            prev = tok
        }

        // Caracteres que no cubrió ningún token (raro): se tratan como palabras sueltas.
        for (i in 0 until n) if (kana[i] == null && !text[i].isWhitespace()) {
            kana[i] = text[i].toString()
            if (i == 0 || text[i - 1].isWhitespace()) wordStart[i] = true
        }
        for (i in 1 until n) if (text[i - 1].isWhitespace() && !text[i].isWhitespace()) wordStart[i] = true

        val out = romajiPerChar(text, kana, wordStart)
        return TextNorm.assembleSegments(segments, out, wordStart)
    }

    private fun tokenize(text: String): List<Tok> = runCatching {
        tokenizer.tokenize(text).map {
            Tok(
                surface = it.surface,
                reading = it.reading?.takeIf { r -> r.isNotBlank() && r != "*" },
                pos1 = it.partOfSpeechLevel1.orEmpty(),
                pos2 = it.partOfSpeechLevel2.orEmpty(),
                pos3 = it.partOfSpeechLevel3.orEmpty(),
                conj = it.conjugationForm.orEmpty(),
                conjType = it.conjugationType.orEmpty(),
                base = it.baseForm.orEmpty(),
                position = it.position,
            )
        }
    }.getOrElse { emptyList() }

    /** Correcciones sobre la salida de Kuromoji. */
    private fun rewrite(input: List<Tok>): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < input.size) {
            val t = input[i]
            val next = input.getOrNull(i + 1)

            // 一 + 人 → 一人 (hitori); 二 + 人 → 二人 (futari)
            if (next != null && t.pos2 == "数" && next.surface == "人" && (t.surface == "一" || t.surface == "二")) {
                val s = t.surface + next.surface
                out += t.copy(surface = s, reading = surfaceReadings[s], pos1 = "名詞", pos2 = "一般", pos3 = "")
                i += 2
                continue
            }
            // Símbolos ASCII que Kuromoji etiqueta como sustantivo ("?" tras NFKC).
            if (t.surface.isNotEmpty() && t.surface.none { it.isLetterOrDigit() }) {
                out += t.copy(pos1 = "記号", pos2 = "一般")
                i++
                continue
            }
            // いつか + 日(ビ, sufijo) → 日 (hi) como palabra propia.
            if (t.surface == "日" && t.pos2 == "接尾" && out.lastOrNull()?.pos2 != "数") {
                out += t.copy(reading = "ヒ", pos2 = "一般")
                i++
                continue
            }
            // Adverbios que llevan la partícula pegada: 本当は, 本当に, 未だに, 心から.
            val split = splitAdverb(t)
            if (split != null) {
                out += split
                i++
                continue
            }
            val fixed = surfaceReadings[t.surface]
            out += if (fixed != null) t.copy(reading = fixed) else t
            i++
        }
        return out
    }

    private fun splitAdverb(t: Tok): List<Tok>? {
        if (t.pos1 != "副詞" || t.surface.length < 3) return null
        val particle = listOf("から", "は", "に").firstOrNull { t.surface.endsWith(it) } ?: return null
        val stem = t.surface.dropLast(particle.length)
        if (stem.none { TextNorm.isHan(it) }) return null
        val particleKana = KanaRomaji.toKatakana(particle)
        val reading = t.reading
        val stemReading = if (reading != null && reading.endsWith(particleKana)) reading.dropLast(particleKana.length) else null
        return listOf(
            t.copy(surface = stem, reading = stemReading, pos1 = "名詞", pos2 = "一般"),
            t.copy(
                surface = particle, reading = particleKana, pos1 = "助詞",
                pos2 = if (particle == "は") "係助詞" else "格助詞", position = t.position + stem.length,
            ),
        )
    }

    private fun readingsFor(tok: Tok): Array<String> {
        val surface = tok.surface
        if (tok.pos1 == "助詞") {
            when (surface) {
                "は" -> return arrayOf("ワ")
                "へ" -> return arrayOf("エ")
                "を" -> return arrayOf("オ")
            }
        }
        if (surface.none { TextNorm.isJapaneseChar(it) }) {
            return Array(surface.length) { surface[it].toString() }
        }
        val reading = tok.reading ?: fallbackReading(surface)
        return alignReading(surface, reading)
    }

    /** Si Kuromoji no conoce la palabra, se lee kanji por kanji. */
    private fun fallbackReading(surface: String): String = buildString {
        for (c in surface) {
            if (KanaRomaji.isKana(c)) {
                append(KanaRomaji.toKatakana(c.toString()))
                continue
            }
            val r = runCatching { tokenizer.tokenize(c.toString()).firstOrNull()?.reading }.getOrNull()
            append(if (r.isNullOrBlank() || r == "*") c.toString() else r)
        }
    }

    /**
     * Reparte la lectura entre los caracteres de la superficie. Los kana se leen a sí mismos y
     * funcionan como anclas; cada bloque de kanji se queda con lo que haya entre anclas.
     * Ej.: 食べ + タベ → [タ, ベ]; 抜けてる → [ヌ, ケ, テ, ル]; 一番星 → [イチバンボシ, "", ""].
     */
    internal fun alignReading(surface: String, reading: String): Array<String> {
        val result = Array(surface.length) { "" }
        val runs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < surface.length) {
            val kanaRun = KanaRomaji.isKana(surface[i])
            var j = i + 1
            while (j < surface.length && KanaRomaji.isKana(surface[j]) == kanaRun) j++
            runs += i to j
            i = j
        }
        val pattern = StringBuilder("^")
        for ((s, e) in runs) {
            if (KanaRomaji.isKana(surface[s])) pattern.append(Regex.escape(KanaRomaji.toKatakana(surface.substring(s, e))))
            else pattern.append("(.+?)")
        }
        pattern.append("$")
        val match = runCatching { Regex(pattern.toString()).find(reading) }.getOrNull()
        if (match == null) {
            result[0] = reading
            return result
        }
        var group = 1
        for ((s, e) in runs) {
            if (KanaRomaji.isKana(surface[s])) {
                for (k in s until e) result[k] = KanaRomaji.toKatakana(surface[k].toString())
            } else {
                result[s] = match.groupValues[group++]
            }
        }
        return result
    }

    /** Auxiliares contraídos que se escriben pegados: 抜け+てる, 言っ+ちゃう, し+とく. */
    private val contractedAux = listOf("て", "で", "ちゃ", "じゃ", "ちま", "じま", "とく", "とい", "とか", "どく", "どい")

    /** ¿El token empieza una palabra nueva o se pega a la anterior? */
    private fun startsNewWord(tok: Tok, prev: Tok?): Boolean {
        if (prev == null) return true
        val surface = tok.surface
        val prevIsVerbal = prev.pos1 == "動詞" || prev.pos1 == "形容詞" || prev.pos1 == "助動詞"
        val prevIsTe = prev.pos1 == "助詞" && prev.pos2 == "接続助詞" && (prev.surface == "て" || prev.surface == "で")

        // La puntuación de cierre se pega a lo anterior; la de apertura empieza palabra.
        if (tok.pos1 == "記号") return TextNorm.isOpening(surface)
        if (prev.pos1 == "記号" && TextNorm.isOpening(prev.surface)) return false
        if (prev.pos1 == "接頭詞") return false

        return when (tok.pos1) {
            // 食べ+た, 見え+ない; pero だ/です/なら/でしょう van aparte (iku nara, yokatta deshou).
            "助動詞" -> when {
                // (el だ de pasado, 繋い+だ, es 特殊・タ y sí se pega)
                tok.conjType == "特殊・ダ" || tok.conjType == "特殊・デス" || tok.base == "らしい" || tok.base == "みたい" -> true
                prevIsTe -> !(surface == "た" || surface == "だ")
                else -> !prevIsVerbal
            }
            "動詞" -> when {
                tok.pos2 == "接尾" -> false
                prev.pos1 == "動詞" && prev.conj.startsWith("連用") && tok.base in compoundV2 -> false
                tok.pos2 == "非自立" -> contractedAux.none { surface.startsWith(it) }
                // 愛 + してる → "aishiteru" (sustantivo サ変 de un kanji + する)
                prev.pos1 == "名詞" && prev.pos2 == "サ変接続" && prev.surface.length == 1 && tok.base == "する" -> false
                else -> true
            }
            "形容詞" -> !(tok.pos2 == "接尾" || (tok.pos2 == "非自立" && prev.pos1 == "動詞"))
            "助詞" -> when {
                tok.pos2 == "接続助詞" && surface in setOf("て", "で", "ば", "ちゃ", "じゃ") && prevIsVerbal -> false
                // 誰か, 何か, どこか, 誰も
                surface == "か" && prev.surface in interrogatives -> false
                surface == "も" && (prev.surface == "誰" || prev.surface == "だれ") -> false
                else -> true
            }
            "名詞" -> when {
                tok.pos2 != "接尾" || tok.pos3 == "人名" -> true
                // 何度, 何回 (pero 一人/3回 ya vienen unidos o son dígitos)
                tok.pos3 == "助数詞" -> !(prev.pos2 == "数" || prev.surface == "何" || prev.surface == "なん")
                // 天才+的, 寂し+さ, 見え+そう
                else -> !(prev.pos1 == "名詞" || prev.pos1 == "形容詞" || prev.pos1 == "動詞")
            }
            else -> true
        }
    }

    private fun romajiPerChar(text: String, kana: Array<String?>, wordStart: BooleanArray): Array<String> {
        val n = text.length
        val out = Array(n) { "" }
        var i = 0
        while (i < n) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            var j = i + 1
            while (j < n && !wordStart[j] && !text[j].isWhitespace()) j++
            // Palabra [i, j): concatenar kana y convertir de una vez (resuelve っ, ー, ん).
            val sb = StringBuilder()
            val owner = mutableListOf<Int>()
            for (k in i until j) {
                val piece = kana[k].orEmpty()
                if (kana[k] != null && piece.isEmpty()) continue // resto de un bloque de kanji
                if (piece.all { KanaRomaji.isKana(it) }) {
                    for (ch in piece) {
                        sb.append(ch); owner += k
                    }
                } else {
                    flush(sb, owner, out)
                    out[k] += TextNorm.latinize(piece.ifEmpty { text[k].toString() })
                }
            }
            flush(sb, owner, out)
            i = j
        }
        return out
    }

    private fun flush(sb: StringBuilder, owner: MutableList<Int>, out: Array<String>) {
        if (sb.isEmpty()) return
        val conv = KanaRomaji.convert(sb.toString())
        conv.forEachIndexed { idx, r -> out[owner[idx]] += r }
        sb.clear()
        owner.clear()
    }
}
