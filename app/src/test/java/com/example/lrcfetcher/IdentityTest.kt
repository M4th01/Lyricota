package com.example.lrcfetcher

import com.example.lrcfetcher.library.Identity
import com.example.lrcfetcher.library.IdentityResolver
import com.example.lrcfetcher.library.Track
import com.example.lrcfetcher.tags.TrackTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class IdentityTest {
    private fun tagged(uri: String, title: String, artist: String) = Track(
        uri, uri, "p", "$uri.mp3", "mp3", 1, 1, title, artist, null, null, metadataLoaded = true,
        tags = TrackTags(title = title, artists = listOf(artist)),
    )

    private fun untagged(name: String) = Track(
        name, name, "p", name, "mp3", 1, 1, name, null, null, null, metadataLoaded = true, identity = Identity.GUESSED,
    )

    private val library = listOf(
        tagged("a", "Buddy Holly", "Weezer"),
        tagged("b", "Say It Ain't So", "Weezer"),
        tagged("c", "Creep", "Radiohead"),
        untagged("Canción Uno - Banda Equis.mp3"),
        untagged("Canción Dos - Banda Equis.mp3"),
    )

    @Test
    fun splitsHandleTrackNumbersAndDashedTitles() {
        val s = IdentityResolver.splits("05 - Undone - The Sweater Song - Weezer.flac")
        assertEquals(listOf("Undone", "Undone - The Sweater Song"), s.map { it.left })
        assertEquals("Weezer", s.last().right)
    }

    @Test
    fun offlineUsesArtistsKnownFromTheLibrary() {
        val index = IdentityResolver.ArtistIndex(library + untagged("Undone - The Sweater Song - Weezer.mp3"))
        // "Título - Artista" (al revés de lo que se asumía antes)
        assertEquals("Undone - The Sweater Song" to "Weezer", IdentityResolver.resolveOffline(untagged("Undone - The Sweater Song - Weezer.mp3"), index))
        // "Artista - Título"
        assertEquals("Island in the Sun" to "Weezer", IdentityResolver.resolveOffline(untagged("Weezer - Island in the Sun.mp3"), index))
        // Artista que sólo se repite en nombres de archivo
        assertEquals("Canción Uno" to "Banda Equis", IdentityResolver.resolveOffline(untagged("Canción Uno - Banda Equis.mp3"), index))
        // Sin pistas: se deja para la comprobación en línea
        assertNull(IdentityResolver.resolveOffline(untagged("Algo - Otra Cosa.mp3"), index))
    }

    @Test
    fun swappedTagsAreDetected() {
        val swapped = tagged("d", "Weezer", "Hash Pipe")
        val index = IdentityResolver.ArtistIndex(library + swapped)
        assertTrue(IdentityResolver.looksSwapped(swapped, index))
        library.filter { it.hasTaggedIdentity }.forEach { assertFalse(it.title, IdentityResolver.looksSwapped(it, index)) }
        // Un título que coincide con un artista de UNA sola canción no basta.
        val one = tagged("e", "Radiohead", "Fan Song")
        assertFalse(IdentityResolver.looksSwapped(one, IdentityResolver.ArtistIndex(library + one)))
    }

    @Test
    fun onlineDecidesTheOrder() {
        assumeTrue(System.getenv("LIVE") == "true")
        assertEquals("Buddy Holly" to "Weezer", IdentityResolver.resolveOnline("Buddy Holly - Weezer.mp3"))
        assertEquals("Buddy Holly" to "Weezer", IdentityResolver.resolveOnline("Weezer - Buddy Holly.mp3"))
        assertEquals("Undone - The Sweater Song" to "Weezer", IdentityResolver.resolveOnline("Weezer - Undone - The Sweater Song.flac"))
        assertEquals("Blinding Lights" to "The Weeknd", IdentityResolver.resolveOnline("01. Blinding Lights - The Weeknd.mp3"))
    }
}
