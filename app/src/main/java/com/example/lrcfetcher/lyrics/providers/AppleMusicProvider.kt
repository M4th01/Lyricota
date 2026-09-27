package com.example.lrcfetcher.lyrics.providers

import com.example.lrcfetcher.lyrics.LyricLine
import com.example.lrcfetcher.lyrics.LyricWord
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.buildLyrics
import org.json.JSONArray
import org.json.JSONObject

/**
 * Búsqueda: API pública de iTunes. Letra: lyrics.paxsenix.org (el único endpoint suyo que sigue
 * abierto). Devuelve sílabas con tiempo, más romanización y traducción oficiales de Apple.
 */
object AppleMusicProvider : LyricsProvider {
    override val id = ProviderId.APPLE_MUSIC

    override fun search(query: String): List<SongCandidate> {
        // Los títulos japoneses/coreanos sólo aparecen tal cual en la tienda local; el ID es global.
        val stores = when {
            query.any { it.code in 0x3040..0x30FF } -> listOf("jp", "us")
            query.any { it.code in 0xAC00..0xD7AF } -> listOf("kr", "us")
            query.any { it.code in 0x4E00..0x9FFF } -> listOf("tw", "us")
            else -> listOf("us")
        }
        val merged = LinkedHashMap<String, SongCandidate>()
        for (store in stores) {
            val body = runCatching {
                Net.get("https://itunes.apple.com/search?term=${Net.enc(query)}&media=music&entity=song&limit=15&country=$store")
            }.getOrNull() ?: continue
            val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val trackId = o.optLong("trackId", -1)
                if (trackId <= 0) continue
                merged.getOrPut(trackId.toString()) {
                    SongCandidate(
                        provider = id,
                        id = trackId.toString(),
                        title = o.optString("trackName"),
                        artist = o.optString("artistName"),
                        album = o.optString("collectionName").ifBlank { null },
                        durationMs = o.optLong("trackTimeMillis").takeIf { it > 0 },
                    )
                }
            }
        }
        return merged.values.toList()
    }

    override fun fetch(candidate: SongCandidate): Lyrics? {
        val body = Net.get(
            "https://lyrics.paxsenix.org/apple-music/lyrics?id=${Net.enc(candidate.id)}&ttml=false&skip_cache=false&v=1",
        )
        return parse(JSONObject(body), candidate)
    }

    internal fun parse(json: JSONObject, candidate: SongCandidate): Lyrics? {
        val meta = json.optJSONObject("metadata")
        val language = meta?.optString("language")?.ifBlank { null }
        val content = json.optJSONArray("content")

        val base: Lyrics = if (content != null && content.length() > 0) {
            val isSyllable = json.optString("type").equals("Syllable", true)
            val agents = agentMap(meta?.optJSONArray("agents"))
            val lines = (0 until content.length()).mapNotNull { i ->
                val l = content.optJSONObject(i) ?: return@mapNotNull null
                val words = syllables(l.optJSONArray("text"))
                if (words.isEmpty()) return@mapNotNull null
                val line = LyricLine(
                    start = l.optLong("timestamp"),
                    end = l.optLong("endtime"),
                    words = words,
                    agent = agents[l.optString("agent")],
                    background = syllables(l.optJSONArray("backgroundText")).takeIf { it.isNotEmpty() },
                )
                line to l.optString("key")
            }
            val translations = lineTexts(meta?.optJSONArray("translations"))
            val roman = romanizations(meta?.optJSONArray("transliterations"))
            val merged = lines.map { (line, key) ->
                line.copy(
                    translation = translations[key],
                    romanWords = roman[key]?.let { alignSpans(it) },
                )
            }
            val cleaned = LyricsParsers.cleanCredits(merged, candidate.title, candidate.artist)
            Lyrics(
                lines = cleaned,
                sync = if (isSyllable) SyncType.WORD else SyncType.LINE,
                source = id,
                sourceId = candidate.id,
                language = language,
                officialRomanization = roman.isNotEmpty(),
            )
        } else {
            val lrc = json.optString("elrc").ifBlank { json.optString("lrc") }.ifBlank { json.optString("plain") }
            if (lrc.isBlank()) return null
            buildLyrics(LyricsParsers.parseLrc(lrc), candidate, language) ?: return null
        }
        return base.takeUnless { it.isEmpty }
    }

    private fun syllables(parts: JSONArray?): List<LyricWord> {
        if (parts == null) return emptyList()
        return (0 until parts.length()).mapNotNull { j ->
            val w = parts.optJSONObject(j) ?: return@mapNotNull null
            val t = w.optString("text")
            if (t.isEmpty()) null
            else LyricWord(w.optLong("timestamp"), w.optLong("endtime"), if (w.optBoolean("part")) t else "$t ")
        }
    }

    /**
     * Apple nombra a los cantantes "v1", "v2", "v2000" (grupo)… Se renumeran en orden de
     * aparición a v1, v2, v3 para el LRC (v1 = cantante principal, sin prefijo).
     */
    private fun agentMap(arr: JSONArray?): Map<String, String> {
        if (arr == null) return emptyMap()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
            .filter { it.isNotBlank() }
            .mapIndexed { i, id -> id to "v${i + 1}" }
            .toMap()
    }

    private fun lineTexts(arr: JSONArray?): Map<String, String> {
        val first = arr?.optJSONObject(0)?.optJSONArray("lines") ?: return emptyMap()
        return (0 until first.length()).mapNotNull { i ->
            val l = first.optJSONObject(i) ?: return@mapNotNull null
            l.optString("key") to l.optString("text")
        }.filter { it.second.isNotBlank() }.toMap()
    }

    private class RomanLine(val text: String, val spans: List<Triple<Long, Long, String>>)

    private fun romanizations(arr: JSONArray?): Map<String, RomanLine> {
        if (arr == null || arr.length() == 0) return emptyMap()
        // Preferir la romanización en alfabeto latino ("ja-Latn", "ko-Latn", "zh-Latn"…).
        val chosen = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .firstOrNull { it.optString("lang").endsWith("Latn", true) } ?: arr.optJSONObject(0) ?: return emptyMap()
        val lines = chosen.optJSONArray("lines") ?: return emptyMap()
        return (0 until lines.length()).mapNotNull { i ->
            val l = lines.optJSONObject(i) ?: return@mapNotNull null
            val spansArr = l.optJSONArray("spans") ?: JSONArray()
            val spans = (0 until spansArr.length()).mapNotNull span@{ j ->
                val s = spansArr.optJSONObject(j) ?: return@span null
                Triple(s.optLong("begin"), s.optLong("end"), s.optString("text"))
            }
            l.optString("key") to RomanLine(l.optString("text"), spans)
        }.toMap()
    }

    /** Reparte los espacios del texto romanizado entre los spans con tiempo. */
    private fun alignSpans(r: RomanLine): List<LyricWord> {
        if (r.spans.isEmpty()) return listOf(LyricWord(0, 0, r.text))
        var cursor = 0
        return r.spans.map { (b, e, t) ->
            val idx = r.text.indexOf(t, cursor)
            var text = t
            if (idx >= 0) {
                cursor = idx + t.length
                if (cursor < r.text.length && r.text[cursor] == ' ') text = "$t "
            } else {
                text = "$t "
            }
            LyricWord(b, e, text)
        }
    }
}
