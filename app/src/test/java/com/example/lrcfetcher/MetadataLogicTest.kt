package com.example.lrcfetcher

import com.example.lrcfetcher.library.Track
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.metadata.MetaSource
import com.example.lrcfetcher.metadata.MetadataCandidate
import com.example.lrcfetcher.metadata.MetadataRepository
import com.example.lrcfetcher.tags.TrackTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLogicTest {
    private val q = TrackQuery("Walking Home", "The Examples", durationMs = 200_000)

    private fun cand(title: String, artist: String, dur: Long? = 200_500, source: MetaSource = MetaSource.DEEZER, album: String = "Sample Album") =
        MetadataCandidate(source, title, TrackTags(title = title, artists = listOf(artist), album = album, year = "2001-02-03", trackNumber = 3, trackTotal = 12), dur)

    @Test
    fun strictRuleNeedsTitleArtistAndDuration() {
        assertTrue(MetadataRepository.isStrictMatch(q, cand("Walking Home", "The Examples")))
        assertTrue(MetadataRepository.isStrictMatch(q, cand("Walking Home (Remastered 2011)", "The Examples")))
        assertFalse(MetadataRepository.isStrictMatch(q, cand("Walking Home", "Other Band")))
        assertFalse(MetadataRepository.isStrictMatch(q, cand("Running Away", "The Examples")))
        // Misma canción pero versión de 4 minutos (en vivo/demo): no se acepta sola.
        assertFalse(MetadataRepository.isStrictMatch(q, cand("Walking Home", "The Examples", dur = 240_000)))
        // Sin artista en la canción no hay forma de verificar.
        assertFalse(MetadataRepository.isStrictMatch(q.copy(artist = null), cand("Walking Home", "The Examples")))
    }

    @Test
    fun combinePicksStrictCandidateAndFillsFromSameAlbum() {
        val deezer = cand("Walking Home", "The Examples").copy(tags = cand("Walking Home", "The Examples").tags.copy(discNumber = 1))
        val itunes = cand("Walking Home", "The Examples", source = MetaSource.ITUNES).let {
            it.copy(tags = it.tags.copy(discTotal = 2, genres = listOf("Rock"), albumArtist = "The Examples"))
        }
        val mb = cand("Walking Home", "The Examples", source = MetaSource.MUSICBRAINZ, album = "Greatest Hits").let {
            it.copy(tags = it.tags.copy(composers = listOf("Jane Doe")))
        }
        val r = MetadataRepository.combine(q, listOf(mb, itunes, deezer))
        assertTrue(r.strictMatch)
        assertEquals(MetaSource.DEEZER, r.primary!!.source)
        val m = r.merged!!
        assertEquals("Sample Album", m.album)
        assertEquals(2, m.discTotal)             // del mismo álbum en iTunes
        assertEquals("The Examples", m.albumArtist)
        assertEquals(listOf("Rock"), m.genres)
        assertEquals(listOf("Jane Doe"), m.composers) // compositores de otra edición: sí
        assertEquals(12, m.trackTotal)
    }

    @Test
    fun batchFillsOnlyEmptyFieldsUnlessOverwrite() {
        val current = TrackTags(title = "Walking Home", artists = listOf("The Examples"), album = "My Rip", year = "2001-02-03")
        val found = TrackTags(title = "WALKING HOME", artists = listOf("The Examples"), album = "Sample Album", year = "2001", trackNumber = 3, genres = listOf("Rock"))
        val fill = MetadataRepository.batchTags(current, found, overwrite = false)
        assertEquals("Walking Home", fill.title)      // se conserva el título del archivo
        assertEquals("My Rip", fill.album)             // no se pisa
        assertEquals(3, fill.trackNumber)              // se completa
        assertEquals("2001-02-03", fill.year)          // fecha más completa del mismo año
        val over = MetadataRepository.batchTags(current, found, overwrite = true)
        assertEquals("Sample Album", over.album)
        assertEquals("Walking Home", over.title)
        assertEquals("2001-02-03", over.year)
    }

    @Test
    fun incompleteDetection() {
        val t = Track("u", "d", "p", "f.mp3", "mp3", 1, 1, "x", "y", null, null, metadataLoaded = true,
            tags = TrackTags(title = "x", artists = listOf("y")))
        assertTrue(t.metadataIncomplete)
        val full = t.copy(tags = t.tags.copy(album = "a", year = "2000", trackNumber = 1, genres = listOf("Pop")))
        assertFalse(full.metadataIncomplete)
    }
}
