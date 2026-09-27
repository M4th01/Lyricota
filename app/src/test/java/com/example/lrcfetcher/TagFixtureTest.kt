package com.example.lrcfetcher

import com.example.lrcfetcher.tags.ByteSource
import com.example.lrcfetcher.tags.Cover
import com.example.lrcfetcher.tags.TagChanges
import com.example.lrcfetcher.tags.TagReader
import com.example.lrcfetcher.tags.TagWriter
import com.example.lrcfetcher.tags.TrackTags
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Lee y escribe metadatos + portada sobre los archivos de EMBED_DIR y deja "meta_<nombre>"
 * para validarlos con mutagen. Se salta si EMBED_DIR no está definido.
 */
class TagFixtureTest {

    private val dir = System.getenv("EMBED_DIR")?.let(::File)

    private fun read(f: File) = RandomAccessFile(f, "r").use { TagReader.read(ByteSource.of(it), f.extension) }
    private fun cover(f: File) = RandomAccessFile(f, "r").use { TagReader.readCover(ByteSource.of(it), f.extension) }

    @Test
    fun readsRealFlacLikeMutagen() {
        val f = dir?.let { File(it, "weezer.flac") }
        assumeTrue(f != null && f.exists())
        val info = read(f!!)
        println(info)
        val t = info.tags
        assertEquals("Undone - The Sweater Song", t.title)
        assertEquals(listOf("Weezer"), t.artists)
        assertEquals("Weezer (Blue Album)", t.album)
        assertEquals("Weezer", t.albumArtist)
        assertEquals(listOf("Rivers Cuomo"), t.composers)
        assertEquals(listOf("Rock"), t.genres)
        assertEquals("1994-05-10", t.year)
        assertEquals(5, t.trackNumber)
        assertEquals(10, t.trackTotal)
        assertEquals(1, t.discNumber)
        assertTrue(info.hasCover)
        assertTrue(info.hasLyrics)
        assertEquals("image/jpeg", cover(f)!!.mime)
    }

    @Test
    fun writesAllFieldsAndCover() {
        assumeTrue(dir != null && dir.isDirectory)
        val art = cover(File(dir!!, "weezer.flac")) ?: Cover(ByteArray(64) { 1 }, "image/jpeg")
        val tags = TrackTags(
            title = "Canción de prueba", album = "Álbum ñ", artists = listOf("Artista A", "Artista B"),
            albumArtist = "Artista A", composers = listOf("Compositor 1", "Compositor 2"), genres = listOf("Rock", "Pop"),
            year = "1994-05-10", trackNumber = 5, trackTotal = 10, discNumber = 1, discTotal = 2,
        )
        for (name in listOf("plain.mp3", "tagged23.mp3", "tagged24.mp3", "song.flac", "song.m4a", "bare.m4a", "song.ogg", "song.opus")) {
            val src = File(dir, name)
            val out = File(dir, "meta_$name")
            RandomAccessFile(src, "r").use { raf -> out.outputStream().use { TagWriter.write(raf, src.extension, TagChanges(tags, art), it) } }
            val back = read(out)
            val expected = if (name.endsWith(".mp3") && name != "tagged24.mp3") tags.copy(year = "1994") else tags
            assertEquals(name, expected, back.tags)
            assertTrue(name, back.hasCover)
            assertArrayEquals(name, art.data, cover(out)!!.data)
            // Una segunda escritura (sólo letra) no debe tocar los metadatos.
            val again = File(dir, "meta2_$name")
            RandomAccessFile(out, "r").use { raf -> again.outputStream().use { TagWriter.write(raf, src.extension, TagChanges(lyrics = "[00:01.00]hola"), it) } }
            val back2 = read(again)
            assertEquals(name, expected, back2.tags)
            assertTrue(name, back2.hasLyrics && back2.hasCover)
            println("ok $name")
        }
    }

    @Test
    fun readsWavAiffApe() {
        assumeTrue(dir != null && File(dir, "song.wav").exists())
        val wav = read(File(dir!!, "song.wav")).tags
        assertEquals("Título WAV", wav.title)
        assertEquals(listOf("Artista WAV"), wav.artists)
        assertEquals("Álbum WAV", wav.album)
        val aiff = read(File(dir, "song.aiff")).tags
        assertEquals("Título AIFF", aiff.title)
        assertEquals(listOf("Artista AIFF"), aiff.artists)
        val ape = read(File(dir, "song.ape")).tags
        assertEquals("Título APE", ape.title)
        assertEquals(listOf("Artista APE"), ape.artists)
        assertEquals(4, ape.trackNumber)
        assertEquals(9, ape.trackTotal)
        assertEquals("2003", ape.year)
    }
}
