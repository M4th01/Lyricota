package com.example.lrcfetcher.lyrics.providers

import com.example.lrcfetcher.AppInfo
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.TtmlParser
import com.example.lrcfetcher.lyrics.text
import org.json.JSONArray
import org.json.JSONObject

/**
 * lrc.red: API pública y gratuita (https://lrc.red/api) con TTML por palabra o sílaba,
 * cantantes y coros de fondo. Sus condiciones de uso:
 *  1. identificarse con un User-Agent que nombre la app, con enlace y correo de contacto;
 *  2. dar crédito y enlace a lrc.red donde se muestren sus datos (lo hace la pantalla de letra).
 *
 * Búsqueda: GET /search.json?q=…  ·  Letra: GET /s/{isrc}.ttml
 */
object LrcRedProvider : LyricsProvider {
    override val id = ProviderId.LRC_RED

    private const val BASE = "https://lrc.red"
    private val headers get() = mapOf("User-Agent" to AppInfo.USER_AGENT, "Accept" to "application/json")

    /** Página de la canción en lrc.red (para el crédito con enlace). */
    fun pageUrl(isrc: String) = "$BASE/s/${Net.enc(isrc)}"

    override fun search(query: String): List<SongCandidate> {
        if (query.isBlank()) return emptyList()
        val body = Net.get("$BASE/search.json?q=${Net.enc(query)}", headers)
        return parseSearch(JSONObject(body))
    }

    internal fun parseSearch(json: JSONObject): List<SongCandidate> {
        val hits = json.optJSONArray("hits") ?: json.optJSONArray("results") ?: JSONArray()
        return (0 until hits.length()).mapNotNull { i ->
            val o = hits.optJSONObject(i) ?: return@mapNotNull null
            val isrc = o.text("isrc").ifBlank { o.text("id") }
            // La API antigua usaba track_name / artist_name; se aceptan las dos formas.
            val title = o.text("title").ifBlank { o.text("track_name") }
            if (isrc.isBlank() || title.isBlank()) return@mapNotNull null
            SongCandidate(
                provider = id,
                id = isrc,
                title = title,
                artist = o.text("artist").ifBlank { o.text("artist_name") },
                album = o.text("album").ifBlank { o.text("album_name") }.ifBlank { null },
                durationMs = o.optDouble("duration", 0.0).takeIf { it > 0 }?.let { (it * 1000).toLong() },
            )
        }
    }

    override fun fetch(candidate: SongCandidate): Lyrics? {
        val xml = Net.get("$BASE/s/${Net.enc(candidate.id)}.ttml", mapOf("User-Agent" to AppInfo.USER_AGENT))
        return parse(xml, candidate)
    }

    internal fun parse(xml: String, candidate: SongCandidate): Lyrics? {
        if (!xml.contains("<tt")) return null
        val parsed = TtmlParser.parse(xml)
        val lines = LyricsParsers.cleanCredits(parsed.lines, candidate.title, candidate.artist)
        val language = Regex("""xml:lang="([^"]+)"""").find(xml)?.groupValues?.get(1)
        return Lyrics(lines, parsed.sync, id, candidate.id, language).takeUnless { it.isEmpty }
    }
}
