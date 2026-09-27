package com.example.lrcfetcher

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.lrcfetcher.library.LyricsWriter
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Incrusta una letra en cada archivo de EMBED_DIR y deja "out_<nombre>" para validarlo con
 * una herramienta externa (mutagen). Se salta si EMBED_DIR no está definido.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbedFixtureTest {
    @Test
    fun embedAll() {
        val dir = System.getenv("EMBED_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val writer = LyricsWriter(ApplicationProvider.getApplicationContext<Application>())
        val lyrics = "[00:01.00]<00:01.00>静かな<00:01.50>夜 <00:02.00>ñandú<00:02.50>\n[00:03.00]Walking home 🎵"
        dir!!.listFiles()!!.filter { it.isFile && !it.name.startsWith("out_") && it.extension in LyricsWriter.EMBEDDABLE }
            .forEach { f ->
                File(dir, "out_${f.name}").outputStream().use { out -> writer.embedFile(f, f.extension, lyrics, out) }
                // Segunda pasada sobre el resultado: no debe duplicar la letra.
                val twice = File(dir, "out2_${f.name}")
                twice.outputStream().use { out -> writer.embedFile(File(dir, "out_${f.name}"), f.extension, lyrics, out) }
                println("ok ${f.name}")
            }

        // El detector (sólo lee cabeceras) debe ver la letra en los resultados y no en los vacíos.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        fun detect(name: String) = writer.readTags(Uri.fromFile(File(dir, name)), name.substringAfterLast('.')).hasLyrics
        for (name in listOf("out_plain.mp3", "out_tagged23.mp3", "out_tagged24.mp3", "out_song.flac", "out_song.m4a", "out_bare.m4a")) {
            assertTrue("detecta letra en $name", detect(name))
        }
        assertFalse(detect("plain.mp3"))
        assertFalse(detect("bare.m4a"))
        assertTrue(detect("tagged23.mp3")) // traía una letra vieja
    }
}
