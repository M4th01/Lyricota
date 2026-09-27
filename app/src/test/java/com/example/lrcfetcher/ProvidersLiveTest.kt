package com.example.lrcfetcher

import com.example.lrcfetcher.lyrics.LyricsRepository
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.ProviderResult
import com.example.lrcfetcher.lyrics.TrackQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Prueba contra los servicios reales. Sólo corre con -Dlive=true (necesita internet):
 *   gradle testDebugUnitTest --tests "*ProvidersLiveTest*" -Dlive=true
 */
class ProvidersLiveTest {

    private val songs = listOf(
        TrackQuery("アイドル", "YOASOBI", durationMs = 213_000),
        TrackQuery("Blinding Lights", "The Weeknd", durationMs = 200_000),
        TrackQuery("Hype Boy", "NewJeans", durationMs = 179_000),
        TrackQuery("起风了", "买辣椒也用券", durationMs = 325_000),
        TrackQuery("HOPE", "NF", durationMs = 264_000),
        TrackQuery("INFERNO", "Sub Urban", durationMs = 176_000),
    )

    @Test
    fun allProviders() = runBlocking {
        assumeTrue(System.getProperty("live") == "true" || System.getenv("LIVE") == "true")
        for (q in songs) {
            println("=== ${q.artist} - ${q.title}")
            val results = LyricsRepository.findAll(q, ProviderId.entries)
            for (id in ProviderId.entries) {
                when (val r = results[id]) {
                    is ProviderResult.Found -> println(
                        "  ${id.label.padEnd(12)} ${r.lyrics.sync.name.padEnd(8)} lines=${r.lyrics.lines.size} " +
                            "score=${"%.2f".format(r.score)} trans=${r.lyrics.hasTranslation} " +
                            "v2=${r.lyrics.lines.count { it.isSecondaryVoice }} bg=${r.lyrics.lines.count { it.backgroundText != null }} → ${r.candidate.artist} - ${r.candidate.title} " +
                            "| ${r.lyrics.lines.firstOrNull { it.text.isNotBlank() }?.text?.take(40)}",
                    )
                    else -> println("  ${id.label.padEnd(12)} $r")
                }
            }
            val best = LyricsRepository.best(results, ProviderId.entries)
            println("  BEST: ${best?.lyrics?.source} ${best?.lyrics?.sync}")
            best?.lyrics?.takeIf { it.hasVoices }?.let { l ->
                val out = com.example.lrcfetcher.lyrics.LrcWriter.write(l, com.example.lrcfetcher.lyrics.OutputOptions()).lines()
                out.withIndex().filter { (_, t) -> t.contains("v2:") || t.startsWith("[bg:") }.take(4)
                    .forEach { (i, t) -> println("    " + out[i - 1].take(70)); println("    " + t.take(90)) }
            }
        }
    }
}
