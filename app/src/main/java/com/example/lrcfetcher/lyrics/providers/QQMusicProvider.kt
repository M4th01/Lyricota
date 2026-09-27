package com.example.lrcfetcher.lyrics.providers

import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.buildLyrics
import org.json.JSONArray
import org.json.JSONObject

/** API oficial de QQ Music: letras QRC cifradas, con tiempo por sílaba. */
object QQMusicProvider : LyricsProvider {
    override val id = ProviderId.QQ_MUSIC

    private val headers = mapOf("Referer" to "https://y.qq.com/", "Origin" to "https://y.qq.com")

    override fun search(query: String): List<SongCandidate> {
        val primary = runCatching { searchCp(query) }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) return primary
        return searchSmartbox(query)
    }

    private fun searchCp(query: String): List<SongCandidate> {
        val url = "https://shc.y.qq.com/soso/fcgi-bin/search_for_qq_cp?format=json&w=${Net.enc(query)}" +
            "&n=15&p=1&remoteplace=txt.mqq.all&t=0"
        val list = JSONObject(Net.get(url, headers)).optJSONObject("data")
            ?.optJSONObject("song")?.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            val songId = s.optLong("songid", -1)
            if (songId <= 0) return@mapNotNull null
            SongCandidate(
                provider = id,
                id = songId.toString(),
                title = s.optString("songname"),
                artist = singers(s.optJSONArray("singer")),
                album = s.optString("albumname").ifBlank { null },
                durationMs = s.optLong("interval").takeIf { it > 0 }?.times(1000),
                extra = mapOf("mid" to s.optString("songmid")),
            )
        }
    }

    private fun searchSmartbox(query: String): List<SongCandidate> {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&key=${Net.enc(query)}"
        val items = JSONObject(Net.get(url, headers)).optJSONObject("data")
            ?.optJSONObject("song")?.optJSONArray("itemlist") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val s = items.optJSONObject(i) ?: return@mapNotNull null
            val songId = s.optString("id").ifBlank { return@mapNotNull null }
            SongCandidate(id, songId, s.optString("name"), s.optString("singer"), extra = mapOf("mid" to s.optString("mid")))
        }
    }

    private fun singers(arr: JSONArray?): String {
        if (arr == null) return ""
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            .filter { it.isNotBlank() }.joinToString(", ")
    }

    override fun fetch(candidate: SongCandidate): Lyrics? {
        val param = JSONObject()
            .put("songID", candidate.id.toLong())
            .put("songMID", candidate.extra["mid"].orEmpty())
            .put("qrc", 1).put("trans", 1).put("roma", 0).put("crypt", 1)
            .put("ct", 19).put("cv", 1873).put("lrc_t", 0).put("qrc_t", 0).put("roma_t", 0).put("trans_t", 0)
            .put("type", -1)
        val body = JSONObject()
            .put("comm", JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"))
            .put(
                "req",
                JSONObject().put("method", "GetPlayLyricInfo")
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put("param", param),
            )
        val resp = JSONObject(Net.post("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), "application/json", headers))
        val data = resp.optJSONObject("req")?.optJSONObject("data") ?: return null

        val lyricHex = data.optString("lyric")
        if (lyricHex.isBlank()) return null
        val decoded = decode(lyricHex) ?: return null
        val parsed = if (decoded.contains("LyricContent=")) LyricsParsers.parseQrc(decoded) else LyricsParsers.parseLrc(decoded)
        val trans = data.optString("trans").takeIf { it.isNotBlank() }?.let { decode(it) }
        return buildLyrics(parsed, candidate, translations = LyricsParsers.translationsFromLrc(trans))
    }

    /** Los campos pueden venir cifrados (hex) o en base64 plano según la canción. */
    private fun decode(value: String): String? {
        val isHex = value.length % 2 == 0 && value.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
        return runCatching {
            if (isHex) QrcDecrypter.decryptHex(value)
            else String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
