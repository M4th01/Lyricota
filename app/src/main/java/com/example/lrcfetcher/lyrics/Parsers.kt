package com.example.lrcfetcher.lyrics

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Resultado intermedio de un parser: líneas + tipo de sincronía detectado. */
data class ParsedLyrics(val lines: List<LyricLine>, val sync: SyncType)

object LyricsParsers {

    private val lineTimeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTimeTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val metaTag = Regex("""^\[([a-zA-Z#]+):(.*)]$""")
    private val bgTag = Regex("""^\[bg:\s*(.*)]$""", RegexOption.IGNORE_CASE)
    private val agentPrefix = Regex("""^(v\d+):\s?""")

    /** "v2:<00:01.00>hola" → ("v2", "<00:01.00>hola"). */
    private fun splitAgent(body: String): Pair<String?, String> {
        val m = agentPrefix.find(body) ?: return null to body
        return m.groupValues[1] to body.substring(m.range.last + 1)
    }

    private fun toMs(min: String, sec: String, frac: String?): Long {
        val f = when {
            frac.isNullOrEmpty() -> 0L
            frac.length == 1 -> frac.toLong() * 100
            frac.length == 2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + f
    }

    // ------------------------------------------------------------------ LRC / LRC mejorado

    /** LRC normal o mejorado (<mm:ss.xx> por palabra). También acepta texto sin tiempos. */
    fun parseLrc(text: String): ParsedLyrics {
        var offset = 0L
        val lines = mutableListOf<LyricLine>()
        var anyWord = false
        var anyTimed = false
        val plain = mutableListOf<String>()

        for (raw in text.lines()) {
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty() || line.startsWith("{")) continue
            // Coros: "[bg: v2:<t>palabra…]" pertenecen a la línea anterior.
            val bg = bgTag.matchEntire(line)
            if (bg != null) {
                val prev = lines.lastOrNull() ?: continue
                val body = splitAgent(bg.groupValues[1].trim()).second
                val ws = parseWordTags(body, prev.start) ?: listOf(LyricWord(prev.start, prev.end, body.trim()))
                if (parseWordTags(body, prev.start) != null) anyWord = true
                lines[lines.lastIndex] = prev.copy(background = prev.background.orEmpty() + ws)
                continue
            }
            val meta = metaTag.matchEntire(line)
            if (meta != null && !lineTimeTag.containsMatchIn(line)) {
                if (meta.groupValues[1].equals("offset", true)) {
                    offset = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                continue
            }
            var pos = 0
            val starts = mutableListOf<Long>()
            while (true) {
                val m = lineTimeTag.find(line, pos) ?: break
                if (m.range.first != pos) break
                starts += toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                pos = m.range.last + 1
            }
            if (starts.isEmpty()) {
                plain += line
                continue
            }
            anyTimed = true
            // Cantante: "v2:<00:01.00>…" (v1 = principal)
            val (agent, body) = splitAgent(line.substring(pos))
            val words = parseWordTags(body, starts.first())
            if (words != null) anyWord = true
            for (s in starts) {
                val shift = s - starts.first()
                val ws = words?.map { it.copy(start = it.start + shift, end = it.end + shift) }
                    ?: listOf(LyricWord(s, s, body.trim()))
                lines += LyricLine(s, ws.lastOrNull()?.end ?: s, ws, agent = agent)
            }
        }

        if (!anyTimed) {
            return ParsedLyrics(plain.map { LyricLine.plain(-1, -1, it) }, SyncType.PLAIN)
        }
        val sorted = lines.sortedBy { it.start }.map { l ->
            if (offset == 0L) l else l.shift(-offset)
        }
        return ParsedLyrics(fillLineEnds(sorted), if (anyWord) SyncType.WORD else SyncType.LINE)
    }

    private fun parseWordTags(body: String, lineStart: Long): List<LyricWord>? {
        val tags = wordTimeTag.findAll(body).toList()
        if (tags.isEmpty()) return null
        val words = mutableListOf<LyricWord>()
        val lead = body.substring(0, tags.first().range.first)
        if (lead.isNotBlank()) {
            val t0 = toMs(tags[0].groupValues[1], tags[0].groupValues[2], tags[0].groupValues[3])
            words += LyricWord(lineStart, t0, lead)
        }
        for (i in tags.indices) {
            val m = tags[i]
            val start = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            val t = body.substring(m.range.last + 1, textEnd)
            val end = if (i + 1 < tags.size) {
                val n = tags[i + 1]
                toMs(n.groupValues[1], n.groupValues[2], n.groupValues[3])
            } else start
            if (t.isNotEmpty()) words += LyricWord(start, end, t)
        }
        return words.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ QRC (QQ Music)

    /** Acepta el XML completo de QQ o sólo el contenido de LyricContent. */
    fun parseQrc(xmlOrContent: String): ParsedLyrics {
        val content = extractQrcContent(xmlOrContent) ?: return parseLrc(xmlOrContent)
        val lineHead = Regex("""^\[(\d+),(\d+)](.*)$""")
        val wordRe = Regex("""(.*?)\((\d+),(\d+)\)""")
        val lines = mutableListOf<LyricLine>()
        for (raw in content.lines()) {
            val m = lineHead.find(raw.trim()) ?: continue
            val start = m.groupValues[1].toLong()
            val dur = m.groupValues[2].toLong()
            val body = m.groupValues[3]
            val words = wordRe.findAll(body).mapNotNull { w ->
                val t = w.groupValues[1]
                if (t.isEmpty()) null
                else LyricWord(w.groupValues[2].toLong(), w.groupValues[2].toLong() + w.groupValues[3].toLong(), t)
            }.toList()
            lines += if (words.isEmpty()) LyricLine.plain(start, start + dur, body)
            else LyricLine(start, start + dur, words)
        }
        return ParsedLyrics(lines, SyncType.WORD)
    }

    private fun extractQrcContent(xml: String): String? {
        val m = Regex("""LyricContent="(.*?)"\s*/>""", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return unescapeXml(m.groupValues[1])
    }

    private fun unescapeXml(s: String): String = s
        .replace(Regex("""&#(\d+);""")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    // ------------------------------------------------------------------ KRC (Kugou)

    data class KrcResult(val lyrics: ParsedLyrics, val translations: List<String?>, val romanization: List<List<String>>?)

    fun parseKrc(text: String): KrcResult {
        val lineHead = Regex("""^\[(\d+),(\d+)](.*)$""")
        val wordRe = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
        val lines = mutableListOf<LyricLine>()
        var translations: List<String?> = emptyList()
        var roman: List<List<String>>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[language:")) {
                runCatching {
                    val json = String(Base64.getDecoder().decode(line.removePrefix("[language:").removeSuffix("]")), Charsets.UTF_8)
                    val arr = JSONObject(json).optJSONArray("content") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val lc = item.optJSONArray("lyricContent") ?: continue
                        val rows = (0 until lc.length()).map { r ->
                            val row = lc.optJSONArray(r) ?: JSONArray()
                            (0 until row.length()).map { row.optString(it) }
                        }
                        when (item.optInt("type", -1)) {
                            1 -> translations = rows.map { it.joinToString("").trim().ifBlank { null } }
                            0 -> roman = rows
                        }
                    }
                }
                continue
            }
            val m = lineHead.find(line) ?: continue
            val start = m.groupValues[1].toLong()
            val dur = m.groupValues[2].toLong()
            val words = wordRe.findAll(m.groupValues[3]).mapNotNull { w ->
                val t = w.groupValues[3]
                if (t.isEmpty()) null
                else {
                    val ws = start + w.groupValues[1].toLong()
                    LyricWord(ws, ws + w.groupValues[2].toLong(), t)
                }
            }.toList()
            lines += if (words.isEmpty()) LyricLine.plain(start, start + dur, m.groupValues[3]) else LyricLine(start, start + dur, words)
        }
        return KrcResult(ParsedLyrics(lines, SyncType.WORD), translations, roman)
    }

    // ------------------------------------------------------------------ YRC (NetEase)

    fun parseYrc(text: String): ParsedLyrics {
        val lineHead = Regex("""^\[(\d+),(\d+)](.*)$""")
        val tagRe = Regex("""\((\d+),(\d+),\d+\)""")
        val lines = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("{")) continue
            val m = lineHead.find(line) ?: continue
            val start = m.groupValues[1].toLong()
            val dur = m.groupValues[2].toLong()
            val body = m.groupValues[3]
            val tags = tagRe.findAll(body).toList()
            val words = tags.mapIndexedNotNull { i, tag ->
                val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
                val t = body.substring(tag.range.last + 1, textEnd)
                val ws = tag.groupValues[1].toLong()
                if (t.isEmpty()) null else LyricWord(ws, ws + tag.groupValues[2].toLong(), t)
            }
            if (words.isNotEmpty()) lines += LyricLine(start, start + dur, words)
        }
        return ParsedLyrics(lines, SyncType.WORD)
    }

    // ------------------------------------------------------------------ utilidades

    fun fillLineEnds(lines: List<LyricLine>): List<LyricLine> = lines.mapIndexed { i, l ->
        val next = lines.getOrNull(i + 1)?.start
        if (l.words.size == 1 && l.words[0].end <= l.start) {
            val end = next ?: (l.start + 5000)
            l.copy(end = end, words = listOf(l.words[0].copy(end = end)))
        } else if (l.end <= l.start) {
            l.copy(end = l.words.lastOrNull()?.end ?: next ?: l.start)
        } else l
    }

    private fun LyricLine.shift(delta: Long) = copy(
        start = start + delta, end = end + delta,
        words = words.map { it.copy(start = it.start + delta, end = it.end + delta) },
        background = background?.map { it.copy(start = it.start + delta, end = it.end + delta) },
    )

    private val creditRe = Regex(
        """^\s*(作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|製作人|监制|監製|混音|录音|錄音|和声|和聲|吉他|贝斯|貝斯|鼓|母带|母帶|""" +
            """出品|发行|發行|OP|SP|策划|企划|统筹|弦乐|人声|配唱|Lyrics? by|Composed by|Arranged by|Written by|Produced by|Producer)""" +
            """\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )
    private val copyrightRe = Regex("""(享有本翻译作品的著作权|著作权|未经.*许可|版权所有)""")

    /** "键盘：平畑彻也", "Mixing Engineer: …": etiqueta corta + dos puntos al principio. */
    private val genericCreditRe = Regex("""^\s*[\p{IsHan}A-Za-z &/.]{1,16}\s*[:：]\s*\S""")

    /**
     * Quita créditos ("作词：…"), la línea de título que ponen QQ/Kugou al inicio y avisos de
     * copyright. Los créditos genéricos sólo se quitan en el bloque inicial y final de la letra.
     */
    fun cleanCredits(lines: List<LyricLine>, title: String?, artist: String?): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val compactTitle = title?.let { Matching.normalize(it).replace(" ", "") }.orEmpty()
        val compactArtist = artist?.let { Matching.normalize(it).replace(" ", "") }.orEmpty()
        val drop = BooleanArray(lines.size)

        fun isTitleLine(i: Int, t: String): Boolean {
            if (i > 1 || compactTitle.isEmpty()) return false
            val c = Matching.normalize(t).replace(" ", "")
            return c.startsWith(compactTitle) && (t.contains(" - ") || t.contains("(") || t.contains("（")) &&
                (compactArtist.isEmpty() || c.contains(compactArtist) || lines[i].start < 1000)
        }

        for (i in lines.indices) {
            val t = lines[i].text
            if (creditRe.containsMatchIn(t) && i < 15) drop[i] = true
            if (copyrightRe.containsMatchIn(t)) drop[i] = true
            if (isTitleLine(i, t)) drop[i] = true
        }
        // Bloque inicial y final: cualquier "Etiqueta: nombre".
        var i = 0
        while (i < lines.size && (drop[i] || genericCreditRe.containsMatchIn(lines[i].text) || lines[i].text.isBlank())) {
            if (lines[i].text.isNotBlank()) drop[i] = true
            i++
        }
        var j = lines.lastIndex
        while (j > i && (drop[j] || genericCreditRe.containsMatchIn(lines[j].text) || lines[j].text.isBlank())) {
            if (lines[j].text.isNotBlank()) drop[j] = true
            j--
        }
        return lines.filterIndexed { k, _ -> !drop[k] }
    }

    private fun isCjk(c: Char) = c.code in 0x3040..0x30FF || c.code in 0x3400..0x4DBF || c.code in 0x4E00..0x9FFF || c == '々'

    private val cjkGap = Regex("""(?<=[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF々])\s+(?=[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF々])""")

    /**
     * Algunas bases (SyncLRC) separan cada carácter japonés/chino con espacios: "素 晴 ら し き".
     * Si ese patrón domina en la canción, se quitan los espacios entre caracteres CJK
     * (los espacios reales entre frases, "淡々と だけど", son la excepción y no dominan).
     */
    fun fixCjkSpacing(lines: List<LyricLine>): List<LyricLine> {
        var spaced = 0
        var joined = 0
        for (l in lines) {
            val t = l.text
            for (k in 1 until t.length) {
                if (isCjk(t[k - 1]) && isCjk(t[k])) joined++
                if (k >= 2 && t[k - 1] == ' ' && isCjk(t[k - 2]) && isCjk(t[k])) spaced++
            }
        }
        if (spaced < 8 || spaced < joined) return lines
        return lines.map { l ->
            val words = l.words.mapIndexed { idx, w ->
                val next = l.words.getOrNull(idx + 1)?.text?.firstOrNull()
                var text = w.text.replace(cjkGap, "")
                val last = text.trimEnd().lastOrNull()
                if (text.endsWith(' ') && last != null && isCjk(last) && next != null && isCjk(next)) text = text.trimEnd()
                w.copy(text = text)
            }
            l.copy(words = words)
        }
    }

    /** Asigna traducciones (lista de tiempo → texto) a la línea con inicio más cercano. */
    fun attachTranslations(lines: List<LyricLine>, translations: List<Pair<Long, String>>, toleranceMs: Long = 600): List<LyricLine> {
        if (translations.isEmpty()) return lines
        val clean = translations.filter { (_, t) ->
            t.isNotBlank() && t.trim() != "//" && !copyrightRe.containsMatchIn(t)
        }.sortedBy { it.first }
        return lines.map { line ->
            if (line.text.isBlank()) return@map line
            val best = clean.minByOrNull { kotlin.math.abs(it.first - line.start) } ?: return@map line
            if (kotlin.math.abs(best.first - line.start) <= toleranceMs) line.copy(translation = best.second.trim()) else line
        }
    }

    fun translationsFromLrc(text: String?): List<Pair<Long, String>> {
        if (text.isNullOrBlank()) return emptyList()
        return parseLrc(text).lines.filter { it.start >= 0 }.map { it.start to it.text }
    }
}
