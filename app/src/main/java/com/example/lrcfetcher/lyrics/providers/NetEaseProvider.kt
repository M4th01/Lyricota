package com.example.lrcfetcher.lyrics.providers

import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.buildLyrics
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** NetEase Cloud Music. La letra palabra por palabra (YRC) sólo sale por la API "eapi" cifrada. */
object NetEaseProvider : LyricsProvider {
    override val id = ProviderId.NETEASE

    private const val EAPI_KEY = "e82ckenh8dichen8"
    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "os=pc; appver=2.10.2; osver=Microsoft-Windows-10",
    )

    override fun search(query: String): List<SongCandidate> {
        val body = Net.get("https://music.163.com/api/search/get?s=${Net.enc(query)}&type=1&limit=15", headers)
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        return (0 until songs.length()).mapNotNull { i ->
            val s = songs.optJSONObject(i) ?: return@mapNotNull null
            val artists = s.optJSONArray("artists") ?: JSONArray()
            SongCandidate(
                provider = id,
                id = s.optLong("id").toString(),
                title = s.optString("name"),
                artist = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.joinToString(", "),
                album = s.optJSONObject("album")?.optString("name")?.ifBlank { null },
                durationMs = s.optLong("duration").takeIf { it > 0 },
            )
        }
    }

    override fun fetch(candidate: SongCandidate): Lyrics? {
        val path = "/api/song/lyric/v1"
        val params = JSONObject()
            .put("id", candidate.id).put("cp", "false")
            .put("lv", "0").put("tv", "0").put("rv", "0").put("kv", "0")
            .put("yv", "0").put("ytv", "0").put("yrv", "0")
            .put("csrf_token", "").put("e_r", "false")
        val form = "params=" + eapiEncrypt(path, params.toString())
        val json = JSONObject(
            Net.post("https://interface3.music.163.com/eapi/song/lyric/v1", form, "application/x-www-form-urlencoded", headers),
        )
        val yrc = json.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val tlyric = json.optJSONObject("tlyric")?.optString("lyric")
        val ytlrc = json.optJSONObject("ytlrc")?.optString("lyric")

        val parsed = if (yrc.isNotBlank()) LyricsParsers.parseYrc(yrc).takeIf { it.lines.isNotEmpty() } else null
        val final = parsed ?: LyricsParsers.parseLrc(lrc)
        val translation = if (parsed != null && !ytlrc.isNullOrBlank()) ytlrc else tlyric
        return buildLyrics(final, candidate, translations = LyricsParsers.translationsFromLrc(translation))
            ?.takeUnless { it.lines.size == 1 && it.lines[0].text.contains("暂无歌词") }
    }

    private fun eapiEncrypt(path: String, text: String): String {
        val digest = md5Hex("nobody${path}use${text}md5forencrypt")
        val data = "$path-36cd479b6b5-$text-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(), "AES"))
        return cipher.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
