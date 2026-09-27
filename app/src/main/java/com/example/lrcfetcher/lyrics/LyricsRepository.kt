package com.example.lrcfetcher.lyrics

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Estado de un proveedor para una canción concreta. */
sealed interface ProviderResult {
    data object Loading : ProviderResult
    data object NotFound : ProviderResult
    data class Error(val message: String) : ProviderResult
    data class Found(val candidate: SongCandidate, val lyrics: Lyrics, val score: Double) : ProviderResult
}

object LyricsRepository {
    private const val MIN_SCORE = 0.55
    private const val PROVIDER_TIMEOUT_MS = 20_000L

    private val lyricsCache = ConcurrentHashMap<String, Lyrics>()

    suspend fun fetch(candidate: SongCandidate): Lyrics? {
        lyricsCache[candidate.key]?.let { return it }
        val result = withContext(Dispatchers.IO) { LyricsProvider.of(candidate.provider).fetch(candidate) }
        if (result != null) lyricsCache[candidate.key] = result
        return result
    }

    /** Busca en un proveedor y descarga la letra del mejor candidato que supere el umbral. */
    suspend fun findIn(provider: ProviderId, query: TrackQuery): ProviderResult = withContext(Dispatchers.IO) {
        val outcome = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            runCatching {
                val p = LyricsProvider.of(provider)
                val ranked = p.search(query)
                    .map { it to Matching.score(query, it.title, it.artist, it.durationMs) }
                    .filter { it.second >= MIN_SCORE }
                    .sortedByDescending { it.second }
                    .take(3)
                for ((candidate, score) in ranked) {
                    val lyrics = runCatching { fetch(candidate) }.getOrNull()
                    if (lyrics != null) return@runCatching ProviderResult.Found(candidate, lyrics, score)
                }
                ProviderResult.NotFound
            }.getOrElse { ProviderResult.Error(it.message ?: it.javaClass.simpleName) }
        }
        outcome ?: ProviderResult.Error("Tiempo de espera agotado")
    }

    /**
     * Consulta todos los proveedores en paralelo. [onUpdate] se llama cada vez que uno termina,
     * así la interfaz puede mostrar resultados a medida que llegan.
     */
    suspend fun findAll(
        query: TrackQuery,
        providers: List<ProviderId>,
        onUpdate: (ProviderId, ProviderResult) -> Unit = { _, _ -> },
    ): Map<ProviderId, ProviderResult> = coroutineScope {
        providers.map { id ->
            async {
                val r = findIn(id, query)
                onUpdate(id, r)
                id to r
            }
        }.awaitAll().toMap()
    }

    /** El mejor resultado: más sincronía, luego con voces/coros, luego el orden del usuario. */
    fun best(results: Map<ProviderId, ProviderResult>, order: List<ProviderId>): ProviderResult.Found? =
        results.values.filterIsInstance<ProviderResult.Found>()
            .sortedWith(
                compareByDescending<ProviderResult.Found> { it.lyrics.sync.rank }
                    // Si una fuente marca dúos o coros, la canción los tiene: preferirla.
                    .thenByDescending { it.lyrics.voiceMarks.coerceAtMost(1) }
                    .thenByDescending { it.lyrics.voiceMarks }
                    .thenBy { order.indexOf(it.lyrics.source).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenByDescending { it.score },
            )
            .firstOrNull()

    /** Búsqueda en cascada para el modo por lotes: se detiene al encontrar palabra por palabra. */
    suspend fun findBestQuick(query: TrackQuery, order: List<ProviderId>): ProviderResult.Found? {
        // Ámbito desacoplado: si ya encontramos palabra por palabra no esperamos a los más lentos.
        val jobs = order.associateWith { id -> detached.async { findIn(id, query) } }
        var bestLine: ProviderResult.Found? = null
        try {
            for (id in order) {
                val r = jobs.getValue(id).await()
                if (r is ProviderResult.Found) {
                    if (r.lyrics.sync == SyncType.WORD) return r
                    if (bestLine == null || r.lyrics.sync.rank > bestLine.lyrics.sync.rank) bestLine = r
                }
            }
        } finally {
            jobs.values.forEach { it.cancel() }
        }
        return bestLine
    }

    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
