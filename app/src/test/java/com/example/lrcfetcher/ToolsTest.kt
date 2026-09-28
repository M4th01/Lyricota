package com.example.lrcfetcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.lrcfetcher.library.BackupEntry
import com.example.lrcfetcher.library.BackupKind
import com.example.lrcfetcher.library.BatchBackup
import com.example.lrcfetcher.library.LyricsWriter
import com.example.lrcfetcher.library.RenamePattern
import com.example.lrcfetcher.library.Track
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.ProviderResult
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.tags.ByteSource
import com.example.lrcfetcher.tags.TagChanges
import com.example.lrcfetcher.tags.TagReader
import com.example.lrcfetcher.tags.TrackTags
import com.example.lrcfetcher.ui.FeedbackType
import com.example.lrcfetcher.ui.feedbackUrl
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun track(i: Int, title: String, artist: String, size: Long = 1) =
        Track("u$i", "$i", "0", "$i.mp3", "mp3", size, 1, title, artist, null, 200_000, metadataLoaded = true,
            tags = TrackTags(title = title, artists = listOf(artist)))

    @Test
    fun renamePatterns() {
        val t = TrackTags(title = "What's Up? / Live", artists = listOf("4 Non Blondes"), trackNumber = 3)
        assertEquals("4 Non Blondes - What's Up_ _ Live", RenamePattern.ARTIST_TITLE.format(t))
        assertEquals("What's Up_ _ Live - 4 Non Blondes", RenamePattern.TITLE_ARTIST.format(t))
        assertEquals("03 - What's Up_ _ Live", RenamePattern.TRACK_TITLE.format(t))
        assertEquals("03 - 4 Non Blondes - What's Up_ _ Live", RenamePattern.TRACK_ARTIST_TITLE.format(t))
        // Sin número de pista no se puede usar ese patrón.
        assertEquals("", RenamePattern.TRACK_TITLE.format(t.copy(trackNumber = null)))
        assertEquals("a b", RenamePattern.sanitize("  a \t b.. "))
    }

    @Test
    fun backupRoundTrip() {
        val b = BatchBackup(app)
        b.begin(BackupKind.METADATA)
        val tags = TrackTags(title = "Canción", artists = listOf("A", "B"), album = "Álbum", trackNumber = 2, trackTotal = 9)
        b.add(BackupEntry(uri = "u1", tags = tags))
        b.add(BackupEntry(uri = "u2", lyrics = "", sidecarDocId = "d", sidecarCreated = true))
        b.finish()
        val data = b.load()!!
        assertEquals(BackupKind.METADATA, data.kind)
        assertEquals(tags, data.entries[0].tags)
        assertEquals("", data.entries[1].lyrics)
        assertTrue(data.entries[1].sidecarCreated)
        b.clear()
        assertNull(b.load())
    }

    /** Deshacer una letra en una canción que no tenía: se quita del archivo. */
    @Test
    fun emptyLyricsRemovesThem() {
        val dir = System.getenv("LYR_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val writer = LyricsWriter(app)
        for (name in listOf("lyr_uslt24.mp3", "lyr_sylt.mp3", "lyr_both.flac", "lyr_song.ogg", "lyr_song.opus", "lyr_song.m4a")) {
            val src = File(dir, name)
            val out = File(dir, "rm_$name")
            out.outputStream().use { writer.applyFile(src, src.extension, TagChanges(lyrics = ""), it) }
            assertNull(name, RandomAccessFile(out, "r").use { TagReader.readLyrics(ByteSource.of(it), out.extension) })
        }
    }

    @Test
    fun duplicatesAreGrouped() {
        val vm = AppViewModel(app)
        vm.tracks = listOf(
            track(1, "Buddy Holly", "Weezer", 5_000_000),
            track(2, "buddy holly (Remastered)", "WEEZER", 30_000_000),
            track(3, "Say It Ain't So", "Weezer"),
        )
        vm.changeSection(Section.METADATA)
        vm.updateMetaFilter(MetaFilter.DUPLICATES)
        assertEquals(listOf("u2", "u1"), vm.visibleTracks().map { it.uri })
    }

    @Test
    fun importAndSync() {
        val vm = AppViewModel(app)
        val t = track(1, "Song", "Artist")
        // Sin canción que reproducir: el texto sin tiempos se usa tal cual.
        val online = LyricsSession(null, TrackQuery("Song", "Artist"), null)
        vm.importLyrics(online, "Línea uno\nLínea dos")
        val plain = online.results[ProviderId.MANUAL] as ProviderResult.Found
        assertEquals(SyncType.PLAIN, plain.lyrics.sync)
        assertEquals(ProviderId.MANUAL, online.selected)
        // Con tiempos: se usa directamente.
        val s2 = LyricsSession(t, TrackQuery("Song", "Artist"), null)
        vm.importLyrics(s2, "[00:01.00]Uno\n[00:02.50]Dos")
        assertEquals(SyncType.LINE, (s2.results[ProviderId.MANUAL] as ProviderResult.Found).lyrics.sync)
        // Sin tiempos y con canción: editor.
        val s3 = LyricsSession(t, TrackQuery("Song", "Artist"), null)
        vm.importLyrics(s3, "Uno\nDos\nTres")
        val sync = (vm.screen as Screen.SyncView).session
        assertEquals(3, sync.remaining)
        sync.lines[0].start = 1000; sync.lines[1].start = 2500; sync.lines[2].start = 4000
        vm.finishSync(sync)
        val manual = (s3.results[ProviderId.MANUAL] as ProviderResult.Found).lyrics
        assertEquals(SyncType.LINE, manual.sync)
        assertEquals(listOf(1000L, 2500L, 4000L), manual.lines.map { it.start })
        assertEquals(listOf("Uno", "Dos", "Tres"), manual.lines.map { it.text })
        assertTrue(vm.screen is Screen.LyricsView)
    }

    @Test
    fun feedbackLinkUsesTemplates() {
        val url = feedbackUrl(FeedbackType.BUG, "No guarda la letra\nen FLAC", "Lyricota 2.7")
        assertTrue(url, url.startsWith("https://github.com/M4th01/Lyricota/issues/new?template=bug_report.yml&title=%5BBug%5D%20No%20guarda%20la%20letra"))
        assertTrue(url, url.contains("&what=No%20guarda%20la%20letra%0Aen%20FLAC&device=Lyricota%202.7"))
        assertTrue(feedbackUrl(FeedbackType.PRAISE, "¡Gracias!", null).contains("template=feedback.yml"))
    }
}
