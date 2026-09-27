package com.example.lrcfetcher

import com.example.lrcfetcher.lyrics.LrcWriter
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.OutputOptions
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TextMode
import com.example.lrcfetcher.lyrics.TtmlParser
import com.example.lrcfetcher.lyrics.providers.AppleMusicProvider
import com.example.lrcfetcher.romanization.Romanizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dúos (v1/v2) y coros de fondo ([bg: …]), en el formato estilo Apple Music / AMLL. */
class VoicesTest {

    // Mismo formato que las letras "multi-persona" (texto inventado).
    private val lrc = """
        [00:00.362]<00:00.362>Hello<00:01.890>
        [01:23.513]<01:23.513>My <01:23.805>song<01:24.190>
        [bg: v2:<01:23.902>My <01:24.095>song<01:24.412>]
        [01:42.243]<01:42.243>Walking <01:42.659>down <01:42.826>the <01:42.963>road<01:43.935>
        [bg: <01:43.762>Okay<01:44.533>]
        [02:54.809]v2:<02:54.809>They <02:55.114>know <02:55.289>it<02:55.528>
        [04:07.630]<04:07.630>I <04:07.780>can <04:08.224>change<04:08.535>
    """.trimIndent()

    @Test
    fun lrc_with_voices_parses_and_round_trips_exactly() {
        val parsed = LyricsParsers.parseLrc(lrc)
        assertEquals(SyncType.WORD, parsed.sync)
        assertEquals(5, parsed.lines.size)
        assertEquals("My song", parsed.lines[1].backgroundText)
        assertEquals("Okay", parsed.lines[2].backgroundText)
        assertEquals("v2", parsed.lines[3].agent)
        assertTrue(parsed.lines[3].isSecondaryVoice)
        assertNull(parsed.lines[0].agent)

        val lyrics = Lyrics(parsed.lines, parsed.sync, ProviderId.LRCLIB, "x")
        assertTrue(lyrics.hasVoices)
        val out = LrcWriter.write(lyrics, OutputOptions(SyncType.WORD, millis = true))
        // El coro se escribe con el cantante de su línea (aquí v1 → sin prefijo).
        assertEquals(lrc.replace("[bg: v2:", "[bg: "), out)
    }

    @Test
    fun voices_can_be_turned_off_and_line_format_keeps_markers() {
        val lyrics = LyricsParsers.parseLrc(lrc).let { Lyrics(it.lines, it.sync, ProviderId.LRCLIB, "x") }
        val off = LrcWriter.write(lyrics, OutputOptions(SyncType.WORD, includeVoices = false))
        assertFalse(off.contains("[bg:"))
        assertFalse(off.contains("v2:"))
        val line = LrcWriter.write(lyrics, OutputOptions(SyncType.LINE, millis = false)).lines()
        assertEquals("[01:23.51]My song", line[1])
        assertEquals("[bg: My song]", line[2])
        assertEquals("[02:54.80]v2:They know it", line[5])
        val plain = LrcWriter.write(lyrics, OutputOptions(SyncType.PLAIN)).lines()
        assertEquals("(Okay)", plain[4])
    }

    @Test
    fun apple_agents_and_background_vocals() {
        val json = javaClass.classLoader!!.getResourceAsStream("apple_sample.json")!!.bufferedReader().readText()
        val lyrics = AppleMusicProvider.parse(JSONObject(json), SongCandidate(ProviderId.APPLE_MUSIC, "1", "テスト", "Lyricota"))!!
        assertTrue(lyrics.hasVoices)
        val duet = lyrics.lines[1]
        assertEquals("v2", duet.agent)
        assertEquals("ああ", duet.backgroundText)
        val out = LrcWriter.write(lyrics, OutputOptions(SyncType.WORD)).lines()
        assertEquals("[00:03.490]v2:<00:03.490>星<00:04.080>が <00:04.470>光<00:05.300>る<00:06.070>", out[1])
        assertEquals("[bg: v2:<00:05.000>ああ<00:06.000>]", out[2])
        // Romanización: la oficial para el verso, la local para el coro.
        val roman = Romanizer.romanize(lyrics)
        assertEquals("hoshi ga hikaru", roman.lines[1].romanText)
        assertEquals("aa", roman.lines[1].backgroundRomanText)
        val both = LrcWriter.write(roman, OutputOptions(SyncType.LINE, TextMode.ROMANIZED, millis = false)).lines()
        assertEquals("[00:03.49]v2:hoshi ga hikaru", both[1])
        assertEquals("[bg: v2:aa]", both[2])
    }

    @Test
    fun ttml_duet_with_background_and_translation() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="other" xml:id="v2"/></metadata></head>
            <body><div>
            <p begin="00:10.737" end="00:12.429" ttm:agent="v1"><span begin="00:10.737" end="00:11.129">No</span> <span begin="00:11.129" end="00:11.527">wa</span><span begin="00:12.088" end="00:12.429">y</span><span ttm:role="x-translation" xml:lang="es">De ninguna manera</span></p>
            <p begin="00:13.000" end="00:16.000" ttm:agent="v2"><span begin="00:13.000" end="00:14.000">Come</span> <span begin="00:14.000" end="00:15.000">back</span><span ttm:role="x-bg"><span begin="00:15.000" end="00:15.500">(come</span> <span begin="00:15.500" end="00:16.000">back)</span></span></p>
            </div></body></tt>
        """.trimIndent()
        val p = TtmlParser.parse(ttml)
        assertEquals(SyncType.WORD, p.sync)
        assertEquals("No way", p.lines[0].text)
        assertEquals("De ninguna manera", p.lines[0].translation)
        assertEquals("v2", p.lines[1].agent)
        assertEquals("Come back", p.lines[1].text)
        assertEquals("come back", p.lines[1].backgroundText) // sin los paréntesis
        val lyrics = Lyrics(p.lines, p.sync, ProviderId.AMLL, "x")
        val out = LrcWriter.write(lyrics, OutputOptions(SyncType.WORD)).lines()
        assertEquals("[00:10.737]<00:10.737>No <00:11.129>wa<00:12.088>y<00:12.429>", out[0])
        assertEquals("[00:13.000]v2:<00:13.000>Come <00:14.000>back<00:15.000>", out[1])
        assertEquals("[bg: v2:<00:15.000>come <00:15.500>back<00:16.000>]", out[2])
    }

    @Test
    fun ttml_times() {
        assertEquals(83_902L, TtmlParser.parseTime("01:23.902"))
        assertEquals(3_723_400L, TtmlParser.parseTime("1:02:03.4"))
        assertEquals(12_500L, TtmlParser.parseTime("12.5s"))
        assertEquals(83_902L, TtmlParser.parseTime("83.902"))
    }
}
