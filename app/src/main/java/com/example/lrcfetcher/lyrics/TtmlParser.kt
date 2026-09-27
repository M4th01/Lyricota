package com.example.lrcfetcher.lyrics

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * TTML de letras (Apple Music / AMLL TTML DB):
 *   <p begin end ttm:agent="v2"><span begin end>palabra</span> <span ttm:role="x-bg">…</span>
 *   <span ttm:role="x-translation">…</span></p>
 * Los espacios entre <span> marcan el final de palabra.
 */
object TtmlParser {

    fun parse(xml: String): ParsedLyrics {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        val agentIds = doc.getElementsByTagName("ttm:agent").let { nl ->
            (0 until nl.length).mapNotNull { (nl.item(it) as? Element)?.getAttribute("xml:id")?.ifBlank { null } }
        }
        val agentMap = agentIds.mapIndexed { i, id -> id to "v${i + 1}" }.toMap()

        val ps = doc.getElementsByTagName("p")
        val lines = mutableListOf<LyricLine>()
        var anyWordTiming = false
        for (i in 0 until ps.length) {
            val p = ps.item(i) as Element
            val start = parseTime(p.getAttribute("begin")) ?: continue
            val end = parseTime(p.getAttribute("end")) ?: start
            val acc = Acc()
            collect(p, acc, background = false)
            var words = acc.words
            if (words.isEmpty()) {
                val text = acc.looseText.toString().trim()
                if (text.isEmpty()) continue
                words = mutableListOf(LyricWord(start, end, text))
            } else {
                anyWordTiming = true
            }
            lines += LyricLine(
                start = start,
                end = end,
                words = words,
                translation = acc.translation,
                agent = agentMap[p.getAttribute("ttm:agent")] ?: p.getAttribute("ttm:agent").ifBlank { null },
                background = stripParens(acc.bg).takeIf { it.isNotEmpty() },
            )
        }
        return ParsedLyrics(lines.sortedBy { it.start }, if (anyWordTiming) SyncType.WORD else SyncType.LINE)
    }

    private class Acc {
        val words = mutableListOf<LyricWord>()
        val bg = mutableListOf<LyricWord>()
        var translation: String? = null
        val looseText = StringBuilder()
    }

    private fun collect(node: Node, acc: Acc, background: Boolean) {
        val target = if (background) acc.bg else acc.words
        val children = node.childNodes
        for (k in 0 until children.length) {
            val child = children.item(k)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val t = child.nodeValue ?: ""
                    if (t.isBlank()) {
                        // Espacio entre spans = fin de palabra.
                        val last = target.lastOrNull()
                        if (t.isNotEmpty() && last != null && !last.text.endsWith(' ')) {
                            target[target.lastIndex] = last.copy(text = last.text + " ")
                        }
                    } else {
                        acc.looseText.append(t)
                    }
                }
                Node.ELEMENT_NODE -> {
                    val e = child as Element
                    when (e.getAttribute("ttm:role")) {
                        "x-translation" -> if (acc.translation == null) acc.translation = e.textContent?.trim()?.ifBlank { null }
                        "x-roman" -> Unit
                        "x-bg" -> collect(e, acc, background = true)
                        else -> {
                            val hasSpans = (0 until e.childNodes.length).any { e.childNodes.item(it).nodeType == Node.ELEMENT_NODE }
                            val b = parseTime(e.getAttribute("begin"))
                            if (hasSpans || b == null) {
                                collect(e, acc, background)
                            } else {
                                val text = e.textContent ?: ""
                                if (text.isNotEmpty()) target += LyricWord(b, parseTime(e.getAttribute("end")) ?: b, text)
                            }
                        }
                    }
                }
            }
        }
    }

    /** AMLL suele escribir los coros entre paréntesis; en "[bg: …]" sobran. */
    private fun stripParens(words: List<LyricWord>): List<LyricWord> {
        if (words.isEmpty()) return words
        val first = words.first().text
        val last = words.last().text.trimEnd()
        if (!first.startsWith("(") || !last.endsWith(")")) return words
        val out = words.toMutableList()
        out[0] = out[0].copy(text = out[0].text.removePrefix("("))
        val l = out.lastIndex
        out[l] = out[l].copy(text = out[l].text.trimEnd().removeSuffix(")"))
        return out.filter { it.text.isNotEmpty() }
    }

    /** "01:23.456", "1:02:03.4", "83.902", "12.5s" → ms. */
    fun parseTime(value: String?): Long? {
        val v = value?.trim()?.ifEmpty { null } ?: return null
        return runCatching {
            if (v.endsWith("ms")) return@runCatching v.removeSuffix("ms").toDouble().toLong()
            if (v.endsWith("s")) return@runCatching (v.removeSuffix("s").toDouble() * 1000).toLong()
            val parts = v.split(':')
            var seconds = 0.0
            for (p in parts) seconds = seconds * 60 + p.toDouble()
            Math.round(seconds * 1000)
        }.getOrNull()
    }
}
