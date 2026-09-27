package com.example.lrcfetcher

import com.example.lrcfetcher.lyrics.LrcWriter
import com.example.lrcfetcher.lyrics.LyricLine
import com.example.lrcfetcher.lyrics.LyricWord
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.OutputOptions
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TextMode
import com.example.lrcfetcher.lyrics.providers.AppleMusicProvider
import com.example.lrcfetcher.lyrics.providers.QrcDecrypter
import com.example.lrcfetcher.romanization.Romanizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).readText()

    /** QRC sintético con la misma forma que devuelve QQ (título, crédito y sílabas con tiempo). */
    private val qrcXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <QrcInfos>
        <QrcHeadInfo SaveTime="10" Version="100"/>
        <LyricInfo LyricCount="1">
        <Lyric_1 LyricType="1" LyricContent="[ti:テスト]
        [0,500]テスト(0,250) - (250,50)Lyricota(300,200)
        [500,400]作词(500,100)：(600,100)Mazu(700,200)
        [1000,2000]今日(1000,400)も(1400,200)歌(1600,300)を(1900,200)歌(2100,300)う(2400,600)
        [3000,1500]&quot;Hello&quot; (3000,700)world(3700,800)
        "/>
        </LyricInfo>
        </QrcInfos>
    """.trimIndent()

    @Test
    fun qrc_encrypt_decrypt_parse() {
        val hex = QrcDecrypter.encryptToHex(qrcXml)
        val xml = QrcDecrypter.decryptHex(hex)
        assertEquals(qrcXml, xml)
        val parsed = LyricsParsers.parseQrc(xml)
        assertEquals(SyncType.WORD, parsed.sync)
        val cleaned = LyricsParsers.cleanCredits(parsed.lines, "テスト", "Lyricota")
        assertEquals(listOf("今日も歌を歌う", "\"Hello\" world"), cleaned.map { it.text })
        assertEquals(1600L, cleaned[0].words[2].start)
        assertEquals(1900L, cleaned[0].words[2].end)
    }

    @Test
    fun krc_parses_with_word_times() {
        val r = LyricsParsers.parseKrc(res("krc_sample.txt"))
        val line = r.lyrics.lines.first { it.text.startsWith("静か") }
        assertEquals(1659L, line.start)
        assertEquals("静", line.words[0].text)
        assertEquals(1659L + 192, line.words[1].start)
    }

    @Test
    fun yrc_parses() {
        val yrc = "{\"t\":0,\"c\":[{\"tx\":\"作词: \"}]}\n[1000,1500](1000,500,0)起(1500,500,0)风(2000,500,0)了"
        val p = LyricsParsers.parseYrc(yrc)
        assertEquals(1, p.lines.size)
        assertEquals("起风了", p.lines[0].text)
        assertEquals(2500L, p.lines[0].words[2].end)
    }

    @Test
    fun lrc_enhanced_roundtrip() {
        val src = """
            [ar:Test]
            [offset:0]
            [00:27.39]<00:27.39>I <00:27.54>walk <00:27.74>alone <00:28.07>tonight<00:28.96>
            [00:30.18][01:00.00]Plain line
        """.trimIndent()
        val parsed = LyricsParsers.parseLrc(src)
        assertEquals(SyncType.WORD, parsed.sync)
        assertEquals("I walk alone tonight", parsed.lines[0].text)
        assertEquals(28960L, parsed.lines[0].words.last().end)
        assertEquals(3, parsed.lines.size) // la línea con dos marcas se duplica
        val lyrics = Lyrics(parsed.lines, parsed.sync, ProviderId.LRCLIB, "1")
        assertEquals(
            "[00:27.39]<00:27.39>I <00:27.54>walk <00:27.74>alone <00:28.07>tonight<00:28.96>",
            LrcWriter.write(lyrics, OutputOptions(SyncType.WORD, millis = false)).lines()[0],
        )
        assertEquals("[00:27.39]I walk alone tonight", LrcWriter.write(lyrics, OutputOptions(SyncType.LINE, millis = false)).lines()[0])
        assertEquals("[00:27.89]I walk alone tonight", LrcWriter.write(lyrics, OutputOptions(SyncType.LINE, offsetMs = 500, millis = false)).lines()[0])
    }

    @Test
    fun cjk_spacing_artifact_is_removed_only_when_it_dominates() {
        fun line(t: String) = LyricLine(0, 1, listOf(LyricWord(0, 1, t)))
        val spaced = List(5) { line("素 晴 ら し き 世 界 に 今日 も 乾 杯") }
        assertEquals("素晴らしき世界に今日も乾杯", LyricsParsers.fixCjkSpacing(spaced)[0].text)
        val legit = List(5) { line("淡々と だけど燦々と見えそうで見えない") }
        assertEquals(legit, LyricsParsers.fixCjkSpacing(legit))
    }

    @Test
    fun apple_json_with_official_romanization() {
        val cand = SongCandidate(ProviderId.APPLE_MUSIC, "1", "テスト", "Lyricota")
        val lyrics = AppleMusicProvider.parse(JSONObject(res("apple_sample.json")), cand)!!
        assertEquals(SyncType.WORD, lyrics.sync)
        assertTrue(lyrics.officialRomanization)
        assertTrue(lyrics.hasTranslation)
        assertEquals("shizuka na yoru no machi o aruku", lyrics.lines[0].romanText)
        val lrc = LrcWriter.write(lyrics, OutputOptions(SyncType.WORD, TextMode.BOTH, includeTranslation = true, millis = false))
        val out = lrc.lines()
        assertTrue(out[0].startsWith("[00:00.58]<00:00.58>静か<00:01.39>な "))
        assertTrue(out[1].startsWith("[00:00.58]<00:00.58>shizuka <00:01.39>na "))
        assertFalse(out[2].contains('<')) // la traducción va sin marcas por palabra
    }

    @Test
    fun local_romanization_keeps_karaoke_alignment_per_syllable() {
        // Como llega de QQ/Kugou: un "word" por carácter, sin romanización oficial.
        val cand = SongCandidate(ProviderId.APPLE_MUSIC, "1", "テスト", "Lyricota")
        val apple = AppleMusicProvider.parse(JSONObject(res("apple_sample.json")), cand)!!
        val perChar = apple.lines.map { l ->
            val text = l.text
            val step = (l.end - l.start) / text.length
            l.copy(words = text.mapIndexed { i, c -> LyricWord(l.start + i * step, l.start + (i + 1) * step, c.toString()) }, romanWords = null)
        }
        val lyrics = Lyrics(perChar, SyncType.WORD, ProviderId.QQ_MUSIC, "1")
        val roman = Romanizer.romanize(lyrics)
        assertEquals("shizuka na yoru no machi o aruku", roman.lines[0].romanText)
        assertEquals(perChar[0].words.size, roman.lines[0].romanWords!!.size)
        // 静か → 静 "shizu" + か "ka"; el espacio de fin de palabra queda al final del segmento.
        assertEquals("shizu", roman.lines[0].romanWords!![0].text)
        assertEquals("ka ", roman.lines[0].romanWords!![1].text)
        val lrc = LrcWriter.write(roman, OutputOptions(SyncType.WORD, TextMode.ROMANIZED))
        assertFalse(lrc.lines()[0].contains("<>"))
    }
}
