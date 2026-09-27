package com.example.lrcfetcher

import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.metadata.MetadataRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Contra los servicios reales; sólo con LIVE=true. */
class MetadataLiveTest {

    @Test
    fun weezerMatchesTheFileTags() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "true")
        val r = MetadataRepository.search(TrackQuery("Undone - The Sweater Song", "Weezer", durationMs = 305_533))
        r.bySource.forEach { (s, c) -> println("$s → ${c.tags} dur=${c.durationMs} covers=${c.covers.map { it.url }}") }
        println("PRIMARY ${r.primary?.source} strict=${r.strictMatch}\nMERGED ${r.merged}")
        val m = r.merged!!
        assertTrue(r.strictMatch)
        assertEquals("Weezer (Blue Album)", m.album)
        assertEquals("Weezer", m.albumArtist)
        assertEquals("1994", m.yearNumber)
        assertEquals(5, m.trackNumber)
        assertEquals(10, m.trackTotal)
        assertEquals(1, m.discNumber)
        assertTrue(m.genres.contains("Rock"))
        assertTrue(m.composers.contains("Rivers Cuomo"))
        assertTrue(r.covers.isNotEmpty())
    }

    @Test
    fun otherSongsAreNotAcceptedAutomatically() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "true")
        // Artista equivocado: no debe pasar la regla estricta del lote.
        val wrongArtist = MetadataRepository.search(TrackQuery("Undone - The Sweater Song", "Nirvana", durationMs = 305_000))
        println("wrong artist → ${wrongArtist.primary?.tags} strict=${wrongArtist.strictMatch}")
        assertFalse(wrongArtist.strictMatch)
        // Canción inventada: sin resultados.
        val none = MetadataRepository.search(TrackQuery("Zzqxj Canción Inexistente", "Grupo Que No Existe 123"))
        println("none → ${none.primary?.tags} strict=${none.strictMatch}")
        assertFalse(none.strictMatch)
    }
}
