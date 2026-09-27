package com.example.lrcfetcher.lyrics.providers

import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.lyrics.buildLyrics
import org.json.JSONArray
import org.json.JSONObject

/** LRCLIB: base de datos abierta y enorme, sincronizada por línea. */
object LrclibProvider : LyricsProvider {
    override val id = ProviderId.LRCLIB

    // LRCLIB pide que las apps se identifiquen.
    private val headers = mapOf("User-Agent" to com.example.lrcfetcher.AppInfo.USER_AGENT)

    override fun search(query: String): List<SongCandidate> =
        parseResults(Net.get("https://lrclib.net/api/search?q=${Net.enc(query)}", headers))

    override fun search(query: TrackQuery): List<SongCandidate> {
        if (!query.artist.isNullOrBlank()) {
            val structured = runCatching {
                parseResults(
                    Net.get(
                        "https://lrclib.net/api/search?track_name=${Net.enc(query.title)}&artist_name=${Net.enc(query.artist)}",
                        headers,
                    ),
                )
            }.getOrDefault(emptyList())
            if (structured.isNotEmpty()) return structured
        }
        return super.search(query)
    }

    private fun parseResults(body: String): List<SongCandidate> {
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("instrumental")) return@mapNotNull null
            val hasSynced = !o.isNull("syncedLyrics") && o.optString("syncedLyrics").isNotBlank()
            val hasPlain = !o.isNull("plainLyrics") && o.optString("plainLyrics").isNotBlank()
            if (!hasSynced && !hasPlain) return@mapNotNull null
            SongCandidate(
                provider = id,
                id = o.optLong("id").toString(),
                title = o.optString("trackName"),
                artist = o.optString("artistName"),
                album = o.optString("albumName").ifBlank { null },
                durationMs = (o.optDouble("duration", 0.0) * 1000).toLong().takeIf { it > 0 },
                extra = mapOf("synced" to hasSynced.toString()),
            )
        }.sortedByDescending { it.extra["synced"] == "true" }
    }

    override fun fetch(candidate: SongCandidate): Lyrics? {
        val o = JSONObject(Net.get("https://lrclib.net/api/get/${Net.enc(candidate.id)}", headers))
        val synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics")
        val plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics")
        val text = synced.ifBlank { plain }
        if (text.isBlank()) return null
        return buildLyrics(LyricsParsers.parseLrc(text), candidate)
    }
}
