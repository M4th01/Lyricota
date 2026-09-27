package com.example.lrcfetcher

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.lrcfetcher.library.LibraryScanner
import com.example.lrcfetcher.library.LibraryScanner.Entry
import com.example.lrcfetcher.library.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDiffTest {
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
    private val scanner = LibraryScanner(ApplicationProvider.getApplicationContext<Application>())

    private fun cachedTrack(e: Entry, title: String) = Track(
        uri = scanner.documentUri(tree, e.docId).toString(), docId = e.docId, parentDocId = e.parentDocId,
        fileName = e.name, ext = "mp3", size = e.size, lastModified = e.lastModified,
        title = title, artist = "Tag Artist", album = null, durationMs = 1000, metadataLoaded = true,
    )

    @Test
    fun incremental_reuses_unchanged_and_rescans_only_new_or_modified() {
        val same = Entry("primary:Music/a.mp3", "a.mp3", 100, 1, "primary:Music")
        val modifiedOld = Entry("primary:Music/b.mp3", "b.mp3", 100, 1, "primary:Music")
        val modifiedNow = modifiedOld.copy(size = 120, lastModified = 2)
        val removed = Entry("primary:Music/c.mp3", "c.mp3", 100, 1, "primary:Music")
        val added = Entry("primary:Music/Sub/05. Artista - Canción Nueva.flac", "05. Artista - Canción Nueva.flac", 5, 5, "primary:Music/Sub")

        val cache = listOf(cachedTrack(same, "A (tags)"), cachedTrack(modifiedOld, "B (tags)"), cachedTrack(removed, "C"))
            .associateBy { it.uri }
        val listing = LibraryScanner.Listing(
            audio = listOf(same, modifiedNow, added),
            lrcByKey = mapOf(LibraryScanner.lrcKey("primary:Music", "a") to "primary:Music/a.lrc"),
        )

        val (ready, pending) = scanner.diff(tree, listing, cache)

        assertEquals(listOf("A (tags)"), ready.map { it.title })        // se reutiliza sin releer etiquetas
        assertEquals("primary:Music/a.lrc", ready[0].lrcDocId)            // .lrc detectado
        assertTrue(ready[0].hasLyrics)
        assertEquals(setOf("b.mp3", "05. Artista - Canción Nueva.flac"), pending.map { it.fileName }.toSet())
        assertTrue(pending.none { it.metadataLoaded })
        val newOne = pending.first { it.ext == "flac" }
        assertEquals("Canción Nueva", newOne.title)                      // título provisional del nombre
        assertEquals("Artista", newOne.artist)
        assertFalse((ready + pending).any { it.fileName == "c.mp3" })     // borrado
    }

    @Test
    fun cache_json_roundtrip() {
        val t = cachedTrack(Entry("id", "x.mp3", 1, 2, "p"), "Título").copy(hasEmbeddedLyrics = true, album = "Álbum")
        assertEquals(t, Track.fromJson(t.toJson()))
    }
}
