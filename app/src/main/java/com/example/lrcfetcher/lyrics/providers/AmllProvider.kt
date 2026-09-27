package com.example.lrcfetcher.lyrics.providers

import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsProvider
import com.example.lrcfetcher.lyrics.Matching
import com.example.lrcfetcher.lyrics.Net
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.lyrics.TtmlParser
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * AMLL TTML DB (github.com/amll-dev/amll-ttml-db, licencia CC0-1.0): letras palabra por palabra
 * hechas por la comunidad, con cantantes (v1/v2), coros de fondo y traducciones.
 *
 * El repositorio publica un índice (metadata/raw-lyrics-index.jsonl) que se descarga una vez
 * al día y permite buscar por título/artista sin API.
 */
object AmllProvider : LyricsProvider {
    override val id = ProviderId.AMLL

    private const val BASE = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main"
    private const val INDEX_TTL_MS = 24L * 60 * 60 * 1000

    /** Carpeta de caché (la fija la app al arrancar); sin ella el índice sólo vive en memoria. */
    @Volatile var cacheDir: File? = null

    private class Entry(val names: List<String>, val artists: List<String>, val album: String?, val file: String)

    @Volatile private var index: List<Entry>? = null
    @Volatile private var indexLoadedAt = 0L

    private fun loadIndex(): List<Entry> {
        val now = System.currentTimeMillis()
        index?.takeIf { now - indexLoadedAt < INDEX_TTL_MS }?.let { return it }
        synchronized(this) {
            index?.takeIf { now - indexLoadedAt < INDEX_TTL_MS }?.let { return it }
            val file = cacheDir?.let { File(it, "amll-index.jsonl") }
            val text = if (file != null && file.exists() && now - file.lastModified() < INDEX_TTL_MS) {
                file.readText()
            } else {
                runCatching { Net.get("$BASE/metadata/raw-lyrics-index.jsonl") }
                    .onSuccess { t -> runCatching { file?.writeText(t) } }
                    .getOrElse { e -> file?.takeIf { it.exists() }?.readText() ?: throw e }
            }
            val parsed = parseIndex(text)
            index = parsed
            indexLoadedAt = now
            return parsed
        }
    }

    /** Cada revisión de una letra es una línea; nos quedamos con la más reciente por canción. */
    private fun parseIndex(text: String): List<Entry> {
        val latest = LinkedHashMap<String, Entry>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val meta = mutableMapOf<String, List<String>>()
            val arr = o.optJSONArray("metadata") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                val values = pair.optJSONArray(1) ?: continue
                meta[pair.optString(0)] = (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }
            }
            val file = o.optString("rawLyricFile")
            if (file.isBlank()) continue
            val names = meta["musicName"].orEmpty()
            if (names.isEmpty()) continue
            val artists = meta["artists"].orEmpty()
            val key = meta["ncmMusicId"]?.firstOrNull()
                ?: meta["appleMusicId"]?.firstOrNull()
                ?: meta["qqMusicId"]?.firstOrNull()
                ?: (names.first() + "|" + artists.joinToString())
            // Los nombres de archivo empiezan por la marca de tiempo: el orden del índice ya es cronológico.
            latest.remove(key)
            latest[key] = Entry(names, artists, meta["album"]?.firstOrNull(), file)
        }
        return latest.values.toList()
    }

    override fun search(query: String): List<SongCandidate> {
        val q = Matching.normalize(query)
        if (q.isEmpty()) return emptyList()
        return loadIndex().asSequence()
            .map { e ->
                val best = e.names.maxOf { n ->
                    maxOf(
                        Matching.similarity(query, n),
                        Matching.similarity(query, "${e.artists.joinToString(" ")} $n"),
                        Matching.similarity(query, "$n ${e.artists.joinToString(" ")}"),
                    )
                }
                e to best
            }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
            .take(10)
            .map { (e, _) -> e.toCandidate(e.names.first()) }
            .toList()
    }

    override fun search(query: TrackQuery): List<SongCandidate> {
        val entries = loadIndex()
        return entries.asSequence()
            .map { e ->
                // El índice puede tener varios nombres (p. ej. "Idol" y "アイドル"): usar el que mejor encaje.
                val name = e.names.maxBy { Matching.similarity(query.title, it) }
                val artist = e.artists.joinToString(", ")
                e to Matching.score(query, name, artist, null) to name
            }
            .filter { (pair, _) -> pair.second >= 0.5 }
            .sortedByDescending { (pair, _) -> pair.second }
            .take(5)
            .map { (pair, name) -> pair.first.toCandidate(name) }
            .toList()
    }

    private fun Entry.toCandidate(name: String) = SongCandidate(
        provider = id,
        id = file,
        title = name,
        artist = artists.joinToString(", "),
        album = album,
    )

    override fun fetch(candidate: SongCandidate): Lyrics? {
        val xml = Net.get("$BASE/raw-lyrics/${Net.enc(candidate.id)}")
        val parsed = TtmlParser.parse(xml)
        val lines = LyricsParsers.cleanCredits(parsed.lines, candidate.title, candidate.artist)
        return Lyrics(lines, parsed.sync, id, candidate.id).takeUnless { it.isEmpty }
    }
}
