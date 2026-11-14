package com.example.lrcfetcher

import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.LyricsParsers
import com.example.lrcfetcher.lyrics.LyricsRepository
import com.example.lrcfetcher.lyrics.Matching
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.SongCandidate
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TrackQuery
import com.example.lrcfetcher.lyrics.TtmlParser
import com.example.lrcfetcher.lyrics.providers.LrcRedProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAndSourcesTest {

    private fun lyrics(text: String, parser: (String) -> com.example.lrcfetcher.lyrics.ParsedLyrics = LyricsParsers::parseLrc): Lyrics {
        val p = parser(text)
        return Lyrics(p.lines, p.sync, ProviderId.QQ_MUSIC, "x").withRealSync()
    }

    // ---------------------------------------------------------------- etiqueta de sincronía real

    @Test
    fun wordLabelNeedsRealWordTimings() {
        // Una sola "palabra" por línea (etiqueta de fin): es letra por línea.
        assertEquals(SyncType.LINE, lyrics("[00:01.00]Hola mundo<00:03.00>\n[00:04.00]Otra línea<00:06.00>").sync)
        // QRC por línea: [inicio,duración]texto sin marcas de palabra.
        assertEquals(SyncType.LINE, lyrics("[1000,2000]Hola mundo\n[4000,2000]Otra línea", LyricsParsers::parseQrc).sync)
        // Un único detalle suelto entre muchas líneas normales no convierte todo en "palabra".
        val mostlyLines = (1..9).joinToString("\n") { "[00:0$it.00]Línea número $it" } + "\n[00:10.00]<00:10.00>Una <00:10.50>sola<00:11.00>"
        assertEquals(SyncType.LINE, lyrics(mostlyLines).sync)
        // Palabra por palabra de verdad: se conserva.
        assertEquals(SyncType.WORD, lyrics("[00:01.00]<00:01.00>Hola <00:01.50>mundo<00:02.00>\n[00:04.00]<00:04.00>Otra <00:04.40>línea<00:05.00>").sync)
        // Todo en el mismo instante: texto sin sincronizar.
        assertEquals(SyncType.PLAIN, lyrics("[00:00.00]Uno\n[00:00.00]Dos").sync)
        // Coma como separador decimal.
        val comma = lyrics("[00:01,50]Uno\n[00:03,25]Dos")
        assertEquals(SyncType.LINE, comma.sync)
        assertEquals(listOf(1500L, 3250L), comma.lines.map { it.start })
    }

    @Test
    fun placeholdersAreNotLyrics() {
        assertTrue(LyricsRepository.isPlaceholder(lyrics("[00:00.00]纯音乐，请欣赏")))
        assertTrue(LyricsRepository.isPlaceholder(lyrics("[00:05.00]Instrumental")))
        assertFalse(LyricsRepository.isPlaceholder(lyrics("[00:01.00]Uno\n[00:02.00]Dos\n[00:03.00]Instrumental break")))
    }

    // ---------------------------------------------------------------- emparejamiento

    @Test
    fun differentSongBySameArtistIsRejected() {
        val q = TrackQuery("INFERNO", "Sub Urban", durationMs = 176_000)
        // Mismo artista y duración parecida, otro título: no es la canción.
        assertTrue(Matching.score(q, "Spring Fever (Explicit)", "Sub Urban", 176_000) < 0.55)
        assertTrue(Matching.score(q, "INFERNO", "Sub Urban, Bella Poarch", 133_000) < Matching.score(q, "INFERNO", "Sub Urban", 176_000))
        assertTrue(Matching.score(q, "INFERNO", "Sub Urban & Bella Poarch", 176_500) >= 0.9)
    }

    @Test
    fun titlesAndVersionsAreNotMixed() {
        // "with" forma parte del título: ya no se corta.
        assertEquals("Stay with Me", Matching.cleanTitle("Stay with Me"))
        val stay = TrackQuery("Stay", "Artista")
        assertTrue(Matching.score(stay, "Stay", "Artista", null) > Matching.score(stay, "Stay with Me", "Artista", null))
        // Se prefiere la misma versión.
        val studio = TrackQuery("Creep", "Radiohead")
        assertTrue(Matching.score(studio, "Creep", "Radiohead", null) > Matching.score(studio, "Creep (Live)", "Radiohead", null))
        val live = TrackQuery("Creep (Live)", "Radiohead")
        assertTrue(Matching.score(live, "Creep (Live)", "Radiohead", null) > Matching.score(live, "Creep", "Radiohead", null))
        // Un título corto contenido en otro largo pesa menos que antes.
        assertTrue(Matching.similarity("Run", "Runaway") < 0.8)
        // Las vocales de otras escrituras no se pierden al normalizar.
        assertEquals("प्यार", Matching.normalize("प्यार"))
    }

    // ---------------------------------------------------------------- lrc.red (TTML inventado)

    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" lrc:timing="Word" xml:lang="es">
          <head><metadata>
            <ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/>
            <sourceMetadata xmlns="http://lrc.red/lyric-ttml-internal">
              <translations><translation type="subtitle" xml:lang="en"><text for="L1">Invented line one</text></translation></translations>
            </sourceMetadata>
          </metadata></head>
          <body dur="0:10.000"><div begin="0:01.000" end="0:09.000" lrc:songPart="Verse">
            <p begin="0:01.000" end="0:03.000" lrc:key="L1" ttm:agent="v1"><span begin="0:01.000" end="0:01.500">Lí</span><span begin="0:01.500" end="0:02.000">nea</span> <span begin="0:02.000" end="0:03.000">uno</span> <span ttm:role="x-bg"><span begin="0:02.200" end="0:02.600">(co</span><span begin="0:02.600" end="0:03.000">ro)</span></span></p>
            <p begin="0:04.000" end="0:06.000" lrc:key="L2" ttm:agent="v2"><span begin="0:04.000" end="0:05.000">Otra</span> <span begin="0:05.000" end="0:06.000">voz</span></p>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun lrcRedTtmlKeepsSingersBackgroundAndTranslation() {
        val cand = SongCandidate(ProviderId.LRC_RED, "XX0000000001", "Canción", "Artista")
        val l = LrcRedProvider.parse(ttml, cand)!!.withRealSync()
        assertEquals(SyncType.WORD, l.sync)
        assertEquals(ProviderId.LRC_RED, l.source)
        assertEquals("es", l.language)
        assertEquals(listOf("Línea uno", "Otra voz"), l.lines.map { it.text })
        assertEquals(listOf(1000L, 1500L, 2000L), l.lines[0].words.map { it.start })
        assertEquals("coro", l.lines[0].backgroundText)
        assertEquals("Invented line one", l.lines[0].translation)
        assertFalse(l.lines[0].isSecondaryVoice)
        assertTrue(l.lines[1].isSecondaryVoice)
        assertEquals("https://lrc.red/s/XX0000000001", LrcRedProvider.pageUrl(cand.id))
        // Respuesta que no es TTML (página de error).
        assertNull(LrcRedProvider.parse("<html>error</html>", cand))
    }

    @Test
    fun untimedTtmlIsPlainText() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p>Primera
               línea</p><p>Segunda</p></div></body></tt>"""
        val p = TtmlParser.parse(xml)
        assertEquals(SyncType.PLAIN, p.sync)
        assertEquals(listOf("Primera línea", "Segunda"), p.lines.map { it.text })
    }

    @Test
    fun lrcRedSearchResults() {
        val json = JSONObject(
            """{"hits":[{"isrc":"XX0000000001","title":"Canción","artist":"Artista, Otro","album":"Disco","duration":200.046},
               {"title":"Sin isrc"},{"isrc":"XX0000000002","title":"Otra","artist":"Artista","album":null}],"next":"p2"}""",
        )
        val c = LrcRedProvider.parseSearch(json)
        assertEquals(listOf("XX0000000001", "XX0000000002"), c.map { it.id })
        assertEquals(200_046L, c[0].durationMs)
        assertEquals("Artista, Otro", c[0].artist)
        assertNull(c[1].album)
        assertNull(c[1].durationMs)
    }
}
