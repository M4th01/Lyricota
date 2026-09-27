package com.example.lrcfetcher.lyrics

import com.example.lrcfetcher.lyrics.providers.AmllProvider
import com.example.lrcfetcher.lyrics.providers.AppleMusicProvider
import com.example.lrcfetcher.lyrics.providers.KugouProvider
import com.example.lrcfetcher.lyrics.providers.LrclibProvider
import com.example.lrcfetcher.lyrics.providers.NetEaseProvider
import com.example.lrcfetcher.lyrics.providers.QQMusicProvider
import com.example.lrcfetcher.lyrics.providers.SyncLrcProvider

/**
 * [wordSync] = suele tener letra palabra por palabra.
 * [voices] = puede marcar cantantes (v1/v2) y coros de fondo.
 */
enum class ProviderId(val label: String, val wordSync: Boolean, val voices: Boolean = false) {
    APPLE_MUSIC("Apple Music", true, voices = true),
    AMLL("AMLL TTML DB", true, voices = true),
    QQ_MUSIC("QQ Music", true),
    KUGOU("Kugou", true),
    NETEASE("NetEase", true),
    SYNCLRC("SyncLRC", true),
    LRCLIB("LRCLIB", false),

    /** La letra que ya tiene la canción (incrustada o .lrc al lado). No es un proveedor de búsqueda. */
    LOCAL("In the song", false);

    val searchable: Boolean get() = this != LOCAL

    companion object {
        val SEARCHABLE: List<ProviderId> get() = entries.filter { it.searchable }
    }
}

data class SongCandidate(
    val provider: ProviderId,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Datos extra que el proveedor necesita para descargar (mid, accesskey…). */
    val extra: Map<String, String> = emptyMap(),
) {
    val key: String get() = "${provider.name}:$id"
}

interface LyricsProvider {
    val id: ProviderId

    /** Búsqueda de texto libre. Bloqueante. */
    fun search(query: String): List<SongCandidate>

    /** Búsqueda estructurada; por defecto usa texto libre. */
    fun search(query: TrackQuery): List<SongCandidate> {
        val primary = runCatching { search(query.searchText) }.getOrDefault(emptyList())
        if (primary.isNotEmpty() || query.artist.isNullOrBlank()) return primary
        return search(query.title)
    }

    /** Descarga la letra de un candidato. null si no tiene. Bloqueante. */
    fun fetch(candidate: SongCandidate): Lyrics?

    companion object {
        val all: Map<ProviderId, LyricsProvider> by lazy {
            listOf(
                AppleMusicProvider, AmllProvider, QQMusicProvider, KugouProvider,
                NetEaseProvider, SyncLrcProvider, LrclibProvider,
            ).associateBy { it.id }
        }

        fun of(id: ProviderId): LyricsProvider = all.getValue(id)
    }
}

/** Crea el objeto final aplicando limpieza de créditos y rellenando fines de línea. */
internal fun buildLyrics(
    parsed: ParsedLyrics,
    candidate: SongCandidate,
    language: String? = null,
    translations: List<Pair<Long, String>> = emptyList(),
): Lyrics? {
    var lines = LyricsParsers.fixCjkSpacing(parsed.lines)
    lines = LyricsParsers.cleanCredits(lines, candidate.title, candidate.artist)
    lines = LyricsParsers.attachTranslations(lines, translations)
    val lyrics = Lyrics(lines, parsed.sync, candidate.provider, candidate.id, language)
    return lyrics.takeUnless { it.isEmpty }
}
