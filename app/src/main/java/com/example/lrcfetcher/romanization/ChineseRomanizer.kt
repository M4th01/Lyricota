package com.example.lrcfetcher.romanization

import com.ibm.icu.text.Transliterator

/**
 * Pinyin sílaba por sílaba (el formato habitual en letras). ICU da la lectura más común de
 * cada carácter; una tabla de frases corrige los polifónicos más frecuentes en canciones.
 */
object ChineseRomanizer : SegmentRomanizer {

    private val hanLatin: Transliterator by lazy { Transliterator.getInstance("Han-Latin; Latin-ASCII") }

    /** Lecturas de caracteres sueltos que ICU suele resolver mal en letras de canciones. */
    private val single = mapOf(
        '的' to "de", '了' to "le", '着' to "zhe", '著' to "zhe", '得' to "de", '地' to "di", '么' to "me", '麼' to "me",
        '吗' to "ma", '嗎' to "ma", '呢' to "ne", '吧' to "ba", '啊' to "a", '呀' to "ya", '哪' to "na", '还' to "hai",
        '還' to "hai", '都' to "dou", '和' to "he", '为' to "wei", '為' to "wei", '说' to "shuo", '說' to "shuo",
        '长' to "chang", '長' to "chang", '重' to "zhong", '行' to "xing", '觉' to "jue", '覺' to "jue", '只' to "zhi",
        '没' to "mei", '沒' to "mei", '乐' to "le", '樂' to "le", '会' to "hui", '會' to "hui", '看' to "kan",
        '什' to "shen", '谁' to "shei", '誰' to "shei", '血' to "xue", '落' to "luo", '朝' to "chao", '转' to "zhuan",
        '轉' to "zhuan", '处' to "chu", '處' to "chu", '更' to "geng", '空' to "kong", '相' to "xiang", '传' to "chuan",
        '傳' to "chuan", '差' to "cha", '种' to "zhong", '種' to "zhong", '背' to "bei", '便' to "bian", '调' to "diao",
    )

    /** Frases donde la lectura cambia. */
    private val phrases = mapOf(
        "地方" to "di fang", "天地" to "tian di", "大地" to "da di", "土地" to "tu di", "慢慢地" to "man man de",
        "音乐" to "yin yue", "音樂" to "yin yue", "快乐" to "kuai le", "快樂" to "kuai le",
        "长大" to "zhang da", "長大" to "zhang da", "成长" to "cheng zhang", "成長" to "cheng zhang",
        "银行" to "yin hang", "重新" to "chong xin", "重来" to "chong lai", "重來" to "chong lai",
        "重复" to "chong fu", "重複" to "chong fu", "重逢" to "chong feng", "了解" to "liao jie", "了结" to "liao jie",
        "受不了" to "shou bu liao", "忘不了" to "wang bu liao", "得不到" to "de bu dao", "还给" to "huan gei",
        "還給" to "huan gei", "觉得" to "jue de", "覺得" to "jue de", "睡觉" to "shui jiao", "睡覺" to "shui jiao",
        "记得" to "ji de", "記得" to "ji de", "值得" to "zhi de", "舍得" to "she de", "捨得" to "she de",
        "得到" to "de dao", "不得不" to "bu de bu", "一只" to "yi zhi", "一隻" to "yi zhi", "只有" to "zhi you",
        "目的" to "mu di", "的确" to "di que", "的確" to "di que", "着急" to "zhao ji", "睡着" to "shui zhao",
        "睡著" to "shui zhao", "看着" to "kan zhe", "为了" to "wei le", "為了" to "wei le", "因为" to "yin wei",
        "因為" to "yin wei", "认为" to "ren wei", "認為" to "ren wei", "角色" to "jue se", "一行" to "yi hang",
        "行走" to "xing zou", "流行" to "liu xing", "不行" to "bu xing", "会计" to "kuai ji", "会計" to "kuai ji",
        "落下" to "luo xia", "朝阳" to "zhao yang", "朝陽" to "zhao yang", "朝夕" to "zhao xi", "空白" to "kong bai",
        "相信" to "xiang xin", "传说" to "chuan shuo", "傳說" to "chuan shuo", "自传" to "zi zhuan",
        "差不多" to "cha bu duo", "出差" to "chu chai", "种子" to "zhong zi", "种下" to "zhong xia",
        "背包" to "bei bao", "背着" to "bei zhe", "便宜" to "pian yi", "调皮" to "tiao pi", "长发" to "chang fa",
        "头发" to "tou fa", "頭髮" to "tou fa", "什么" to "shen me", "什麼" to "shen me", "怎么" to "zen me",
        "怎麼" to "zen me", "那么" to "na me", "这么" to "zhe me", "多么" to "duo me", "还是" to "hai shi",
        "还有" to "hai you", "都是" to "dou shi", "首都" to "shou du", "都市" to "du shi", "和平" to "he ping",
        "暖和" to "nuan huo", "曲折" to "qu zhe", "歌曲" to "ge qu", "一曲" to "yi qu", "数不清" to "shu bu qing",
    )
    private val maxPhrase = phrases.keys.maxOf { it.length }

    override fun romanize(segments: List<String>): List<String> {
        val text = TextNorm.normalizeCharwise(segments.joinToString(""))
        val n = text.length
        val out = Array(n) { "" }
        val wordStart = BooleanArray(n)
        var i = 0
        while (i < n) {
            val c = text[i]
            if (!TextNorm.isHan(c)) {
                out[i] = if (c.isWhitespace()) "" else TextNorm.latinize(c.toString())
                if (!c.isWhitespace() && i > 0 && (TextNorm.isHan(text[i - 1]) || text[i - 1].isWhitespace()) &&
                    (c.isLetterOrDigit() || TextNorm.isOpening(c.toString()))
                ) wordStart[i] = true
                i++
                continue
            }
            val phrase = (minOf(maxPhrase, n - i) downTo 2).firstNotNullOfOrNull { len ->
                phrases[text.substring(i, i + len)]?.let { len to it }
            }
            if (phrase != null) {
                val parts = phrase.second.split(' ')
                for (k in 0 until phrase.first) {
                    out[i + k] = parts.getOrElse(k) { "" }
                    wordStart[i + k] = true
                }
                i += phrase.first
                continue
            }
            out[i] = single[c] ?: runCatching { hanLatin.transliterate(c.toString()).trim() }.getOrDefault(c.toString())
            wordStart[i] = true
            i++
        }
        return TextNorm.assembleSegments(segments, out, wordStart)
    }
}
