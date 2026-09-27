package com.example.lrcfetcher.lyrics.providers

import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.lyrics.buildLyrics
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import org.json.JSONArray
import org.json.JSONObject

/** Kugou: formato KRC (tiempo por carácter), cifrado con XOR + zlib. */
object KugouProvider : LyricsProvider {
    override val id = ProviderId.KUGOU

    private val KRC_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    override fun search(query: String): List<SongCandidate> = searchLyrics(query, null)

    override fun search(query: TrackQuery): List<SongCandidate> {
        val keyword = if (!query.artist.isNullOrBlank()) "${query.artist} - ${query.title}" else query.title
        val first = runCatching { searchLyrics(keyword, query.durationMs) }.getOrDefault(emptyList())
        if (first.isNotEmpty()) return first
        return searchLyrics(query.title, query.durationMs)
    }

    private fun searchLyrics(keyword: String, durationMs: Long?): List<SongCandidate> {
        val dur = durationMs?.let { "&duration=$it" }.orEmpty()
        val url = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${Net.enc(keyword)}$dur"
        val arr = JSONObject(Net.get(url)).optJSONArray("candidates") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val cid = c.optString("id").ifBlank { return@mapNotNull null }
            SongCandidate(
                provider = id,
                id = cid,
                title = c.optString("song"),
                artist = c.optString("singer"),
                durationMs = c.optLong("duration").takeIf { it > 0 },
                extra = mapOf("accesskey" to c.optString("accesskey")),
            )
        }
    }

    override fun fetch(candidate: SongCandidate): Lyrics? {
        val key = candidate.extra["accesskey"] ?: return null
        val url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${Net.enc(candidate.id)}" +
            "&accesskey=${Net.enc(key)}&fmt=krc&charset=utf8"
        val content = JSONObject(Net.get(url)).optString("content")
        if (content.isBlank()) return null
        val krc = decryptKrc(Base64.getDecoder().decode(content))
        val result = LyricsParsers.parseKrc(krc)
        val translations = result.lyrics.lines.mapIndexedNotNull { i, line ->
            result.translations.getOrNull(i)?.let { line.start to it }
        }
        return buildLyrics(result.lyrics, candidate, translations = translations)
    }

    internal fun decryptKrc(bytes: ByteArray): String {
        val data = ByteArray(bytes.size - 4) { i -> (bytes[i + 4].toInt() xor KRC_KEY[i % KRC_KEY.size]).toByte() }
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toString("UTF-8").trimStart('﻿')
    }
}
