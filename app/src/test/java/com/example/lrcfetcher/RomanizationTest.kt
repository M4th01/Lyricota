package com.example.lrcfetcher

import com.example.lrcfetcher.romanization.ChineseRomanizer
import com.example.lrcfetcher.romanization.JapaneseRomanizer
import com.example.lrcfetcher.romanization.KanaRomaji
import com.example.lrcfetcher.romanization.KoreanRomanizer
import com.example.lrcfetcher.romanization.Romanizer
import com.example.lrcfetcher.romanization.Script
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Casos de referencia (frases inventadas) con el estilo de romanización de Apple Music:
 * "kyou", partículas wa/o, palabras agrupadas; y reglas de pronunciación coreanas.
 */
class RomanizationTest {

    private fun ja(s: String) = Romanizer.romanizeText(s, "ja")

    @Test
    fun japanese_matches_apple_style() {
        val cases = mapOf(
            "静かな夜の街を歩く" to "shizuka na yoru no machi o aruku",
            "本当のことを知りたい" to "hontou no koto o shiritai",
            "君は優しくて強い人だ" to "kimi wa yasashikute tsuyoi hito da",
            "昨日何を見た？" to "kinou nani o mita?",
            "海に行くなら一緒に行こう" to "umi ni iku nara issho ni ikou",
            "何を言われても" to "nani o iwarete mo",
            "一人で帰る" to "hitori de kaeru",
            "二人だけの秘密" to "futari dake no himitsu",
            "本当は私も行きたいんだ" to "hontou wa watashi mo ikitai n da",
            "誰も知り得ない" to "daremo shirienai",
            "誰かを待っている" to "dareka o matte iru",
            "彼は走り出した" to "kare wa hashiridashita",
            "手を繋いだ" to "te o tsunaida",
            "いつか日が昇る" to "itsuka hi ga noboru",
            "寂しさを忘れたい" to "sabishisa o wasuretai",
            "明日は晴れるでしょう" to "ashita wa hareru deshou",
        )
        val failures = cases.filter { (src, expected) -> ja(src) != expected }
            .map { (src, expected) -> "$src\n  esperado: $expected\n  obtenido: ${ja(src)}" }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun japanese_normalizes_kangxi_radicals_and_fullwidth() {
        // Algunas letras usan radicales Kangxi (⼆⼈, ⽇) en lugar de los kanji normales.
        assertEquals("futari de iyou", ja("⼆⼈でいよう"))
        assertEquals("B desu", ja("Ｂです"))
    }

    @Test
    fun kana_basics() {
        assertEquals("kyou", KanaRomaji.toRomaji("きょう"))
        assertEquals("matte", KanaRomaji.toRomaji("まって"))
        assertEquals("kotchi", KanaRomaji.toRomaji("こっち"))
        assertEquals("raamen", KanaRomaji.toRomaji("ラーメン"))
        assertEquals("fantajii", KanaRomaji.toRomaji("ファンタジー"))
        assertEquals("ren'ai", KanaRomaji.toRomaji("れんあい"))
    }

    @Test
    fun karaoke_segments_stay_aligned() {
        val segs = listOf("静か", "な", "夜の", "街", "を", "歩", "く")
        val out = JapaneseRomanizer.romanize(segs)
        assertEquals(segs.size, out.size)
        assertEquals("shizuka na yoru no machi o aruku", out.joinToString("").trim())
        assertEquals("aru", out[5])
        assertEquals("ku", out[6])
    }

    @Test
    fun korean_pronunciation_rules() {
        fun r(s: String) = KoreanRomanizer.romanize(listOf(s)).first()
        val cases = mapOf(
            "합니다" to "hamnida", "이렇게" to "ireoke", "같이" to "gachi", "신라" to "silla", "많이" to "mani",
            "사랑해" to "saranghae", "보고 싶다" to "bogo sipda", "믿어봐" to "mideobwa", "좋아" to "joa",
            "있는" to "inneun", "끝이" to "kkeuchi", "솔직히" to "soljiki", "싫어" to "sireo", "없어" to "eopseo",
            "밥 먹었어" to "bap meogeosseo", "Baby, 빠져버리는 꿈" to "Baby, ppajyeobeorineun kkum",
        )
        val failures = cases.filter { (src, expected) -> r(src) != expected }.map { (s, e) -> "$s → ${r(s)} (esperado $e)" }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun chinese_pinyin_with_polyphones() {
        fun r(s: String) = ChineseRomanizer.romanize(listOf(s)).first()
        assertEquals("wo men yi qi zou zou ting ting", r("我们一起走走停停"))
        assertEquals("wo jue de ni hen kuai le", r("我觉得你很快乐"))
        assertEquals("zhang da yi hou", r("长大以后"))
    }

    @Test
    fun script_detection_is_per_song() {
        assertEquals(Script.JAPANESE, Romanizer.detect("一番\nそれは秘密"))
        assertEquals(Script.CHINESE, Romanizer.detect("我们一起走走停停"))
        assertEquals(Script.KOREAN, Romanizer.detect("보고 싶다 Baby"))
        assertEquals(Script.LATIN, Romanizer.detect("Walking home tonight"))
    }
}
