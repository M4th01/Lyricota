package com.example.lrcfetcher

import androidx.compose.ui.graphics.Color
import com.example.lrcfetcher.library.LyricsWriter
import com.example.lrcfetcher.lyrics.LyricLine
import com.example.lrcfetcher.lyrics.LyricWord
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.tags.ByteSource
import com.example.lrcfetcher.tags.TagReader
import com.example.lrcfetcher.ui.karaoke
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LocalLyricsTest {

    private fun readLyrics(f: File) = RandomAccessFile(f, "r").use { TagReader.readLyrics(ByteSource.of(it), f.extension) }

    /** Letras escritas con mutagen (herramienta independiente) en todos los formatos. */
    @Test
    fun readsLyricsWrittenByMutagen() {
        val dir = System.getenv("LYR_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val expected = JSONObject(File(dir, "lyrics_expected.json").readText())
        for (name in expected.keys()) {
            val want = if (expected.isNull(name)) null else expected.getString(name)
            assertEquals(name, want, readLyrics(File(dir, name)))
        }
    }

    /** Lo que Lyricota incrusta, Lyricota lo vuelve a leer igual (todos los formatos escribibles). */
    @Test
    fun readsBackWhatItEmbeds() {
        val dir = System.getenv("LYR_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val writer = LyricsWriter(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val lrc = "[00:01.000]v2:<00:01.000>Hola <00:01.500>mundo<00:02.000>\n[bg: <00:01.200>coro<00:01.800>]"
        for (name in listOf("lyr_uslt24.mp3", "lyr_uslt23.mp3", "lyr_song.flac", "lyr_song.ogg", "lyr_song.opus", "lyr_song.m4a")) {
            val src = File(dir, name)
            val out = File(dir, "rt_$name")
            out.outputStream().use { writer.embedFile(src, src.extension, lrc, it) }
            assertEquals(name, lrc, readLyrics(out))
        }
    }

    @Test
    fun allLinesAtZeroMeansUnsynced() {
        val parsed = LyricsParsers.parseLrc("[00:00.00]Uno\n[00:00.00]Dos\n[00:00.00]Tres")
        val l = Lyrics(parsed.lines, parsed.sync, ProviderId.APPLE_MUSIC, "x").withRealSync()
        assertEquals(SyncType.PLAIN, l.sync)
        assertEquals(listOf("Uno", "Dos", "Tres"), l.lines.map { it.text })
        // Letra sincronizada de verdad: no se toca.
        val ok = LyricsParsers.parseLrc("[00:01.00]Uno\n[00:02.00]Dos")
        assertEquals(SyncType.LINE, Lyrics(ok.lines, ok.sync, ProviderId.LRCLIB, "y").withRealSync().sync)
    }

    @Test
    fun karaokeFillsLetterByLetter() {
        val words = listOf(LyricWord(1000, 2000, "Hola "), LyricWord(2000, 3000, "mundo"))
        val sung = Color.Green
        val pending = Color.Gray
        fun sungText(pos: Long): String {
            val a = karaoke(words, pos, sung, pending)
            return a.spanStyles.filter { it.item.color == sung }.joinToString("") { a.text.substring(it.start, it.end) }
        }
        assertEquals("", sungText(500))
        assertEquals("Ho", sungText(1400)) // 40 % de "Hola " = 2 letras
        assertEquals("Hola ", sungText(2000))
        assertEquals("Hola mun", sungText(2600))
        assertEquals("Hola mundo", sungText(3500))
        assertEquals("Hola mundo", karaoke(words, 0, sung, pending).text)
    }

    @Test
    fun noLyricsReadsNull() {
        val f = File.createTempFile("empty", ".mp3").apply { writeBytes(ByteArray(256)) }
        assertNull(readLyrics(f))
        f.delete()
        // Una línea normal sin palabras no es karaoke.
        assertEquals("x", LyricLine.plain(0, 0, "x").text)
    }
}
