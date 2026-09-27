package com.example.lrcfetcher

import com.example.lrcfetcher.update.UpdateChecker
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class UpdateTest {
    @Test
    fun versionComparison() {
        assertTrue(UpdateChecker.isNewer("2.5", "2.4"))
        assertTrue(UpdateChecker.isNewer("v2.10", "2.9"))
        assertTrue(UpdateChecker.isNewer("3", "2.9.9"))
        assertTrue(UpdateChecker.isNewer("2.4.1", "2.4"))
        assertFalse(UpdateChecker.isNewer("2.4", "2.4"))
        assertFalse(UpdateChecker.isNewer("v2.4", "2.5"))
        assertFalse(UpdateChecker.isNewer("2.4.0", "2.4"))
    }

    @Test
    fun parsesRelease() {
        val json = """
            {"tag_name":"v2.5","html_url":"https://github.com/M4th01/Lyricota/releases/tag/v2.5","body":"## Lyricota 2.5\n- Update notice",
             "draft":false,"prerelease":false,
             "assets":[{"name":"notes.txt","size":10,"browser_download_url":"https://x/notes.txt"},
                       {"name":"Lyricota-2.5.apk","size":36611475,"browser_download_url":"https://x/Lyricota-2.5.apk"}]}
        """.trimIndent()
        val info = UpdateChecker.parse(json)!!
        assertEquals("2.5", info.version)
        assertEquals("https://x/Lyricota-2.5.apk", info.apkUrl)
        assertEquals(36611475L, info.apkSize)
        assertTrue(info.notes.startsWith("## Lyricota 2.5"))
        assertNull(UpdateChecker.parse("""{"tag_name":"v3.0","prerelease":true}"""))
    }

    /** Contra la Release publicada de verdad (v2.4 o posterior). */
    @Test
    fun liveLatestRelease() {
        assumeTrue(System.getenv("LIVE") == "true")
        val info = UpdateChecker.fetchLatest()
        assertNotNull(info)
        assertFalse(UpdateChecker.isNewer("2.4", info!!.version))
        assertNotNull(info.apkUrl)
        // Descarga parcial no: se comprueba la descarga completa con progreso.
        val dest = File.createTempFile("lyricota", ".apk")
        var last = 0f
        UpdateChecker.download(info.apkUrl!!, dest) { last = it }
        assertEquals(info.apkSize, dest.length())
        assertEquals(1f, last, 0.0001f)
        dest.delete()
    }
}
