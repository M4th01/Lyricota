package com.example.lrcfetcher.lyrics.providers

import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.Matching
import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.buildLyrics
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** SyncLRC (api.synclrc.dev): gratis, sin key; a veces trae karaoke palabra por palabra. */
object SyncLrcProvider : LyricsProvider {
    override val id = ProviderId.SYNCLRC

    /** La búsqueda ya incluye la letra: la guardamos para no pedirla otra vez. */
    private val cache = ConcurrentHashMap<String, String>()

    override fun search(query: String): List<SongCandidate> {
        val body = Net.get("https://api.synclrc.dev/search?q=${Net.enc(query)}&limit=10")
        val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sid = o.optString("id").ifBlank { return@mapNotNull null }
            bestText(o)?.let { cache[sid] = it }
            SongCandidate(
                provider = id,
                id = sid,
                title = o.optString("track"),
                artist = o.optString("artist"),
                album = o.optString("album").ifBlank { null },
                durationMs = o.optLong("duration").takeIf { it > 0 }?.times(1000),
            )
        }
    }

    override fun fetch(candidate: SongCandidate): Lyrics? {
        val text = cache[candidate.id] ?: runCatching {
            val url = "https://api.synclrc.dev/lyrics?track=${Net.enc(candidate.title)}&artist=${Net.enc(candidate.artist)}"
            val o = JSONObject(Net.get(url))
            // La búsqueda directa a veces devuelve otra canción del mismo artista.
            if (Matching.similarity(o.optString("track"), candidate.title) < 0.7) null else bestText(o)
        }.getOrNull() ?: return null
        return buildLyrics(LyricsParsers.parseLrc(text), candidate)
    }

    /** Prioridad: karaoke → synced → plain. Los campos pueden venir sueltos o dentro de "lyrics". */
    private fun bestText(o: JSONObject): String? {
        val holder = o.optJSONObject("lyrics") ?: o
        for (field in listOf("karaoke", "synced", "plain")) {
            if (holder.isNull(field)) continue
            val v = holder.optString(field).trim()
            if (v.isNotEmpty() && !v.equals("null", true) && !v.equals("undefined", true)) return v
        }
        return null
    }
}
