package com.example.lrcfetcher.lyrics

import com.example.lrcfetcher.romanization.Romanizer
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lo que sabemos de la canción que buscamos (de las etiquetas del archivo o del usuario). */
data class TrackQuery(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
) {
    /** Texto para buscadores de texto libre. */
    val searchText: String get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ")
}

object Matching {

    private val bracketed = Regex("""[(\[（【「『][^)\]）】」』]*[)\]）】」』]""")
    private val featRe = Regex("""(?i)\s+(feat\.?|ft\.?|featuring|with)\s+.*$""")
    private val noiseRe = Regex(
        """(?i)\b(official\s*(music\s*)?(video|audio|mv)?|lyrics?|lyric video|hd|hq|remaster(ed)?(\s*\d{4})?|mv|audio|explicit|clean)\b""",
    )
    private val suffixRe = Regex("""(?i)\s[-–—]\s*(remaster(ed)?|live|single version|radio edit|\d{4} remaster).*$""")

    private val latinMarks = Regex("""(?<=\p{IsLatin})\p{Mn}+""")

    /** Minúsculas, sin acentos ni signos, espacios colapsados. Conserva letras CJK. */
    fun normalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        // Sólo se quitan acentos de letras latinas (á → a); ド no debe convertirse en ト.
        val noMarks = Normalizer.normalize(
            Normalizer.normalize(nfkc, Normalizer.Form.NFD).replace(latinMarks, ""),
            Normalizer.Form.NFC,
        )
        return noMarks.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    }

    /** Título "limpio": sin (feat…), [Remaster], "- Live", número de pista, etc. */
    fun cleanTitle(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFKC)
        t = t.replace(Regex("""^\s*\d{1,3}\s*[.\-_)]\s*"""), "")
        t = t.replace(suffixRe, "")
        t = t.replace(bracketed, " ")
        t = t.replace(featRe, "")
        t = t.replace(noiseRe, " ")
        t = t.replace('_', ' ')
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { s.trim() }
    }

    fun cleanArtist(s: String): String =
        s.split(Regex("""(?i)\s*(,|&|、|/|;|\bfeat\.?\b|\bft\.?\b|\bx\b|×)\s*""")).firstOrNull()?.trim().orEmpty().ifBlank { s }

    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val compactX = x.replace(" ", "")
        val compactY = y.replace(" ", "")
        if (compactX == compactY) return 0.98
        val contain = if (compactX.contains(compactY) || compactY.contains(compactX)) {
            0.75 + 0.2 * min(compactX.length, compactY.length).toDouble() / max(compactX.length, compactY.length)
        } else 0.0
        val lev = 1.0 - levenshtein(compactX, compactY).toDouble() / max(compactX.length, compactY.length)
        val tokensX = x.split(' ').toSet()
        val tokensY = y.split(' ').toSet()
        val jaccard = tokensX.intersect(tokensY).size.toDouble() / tokensX.union(tokensY).size
        return maxOf(contain, lev, jaccard)
    }

    fun score(query: TrackQuery, title: String, artist: String?, durationMs: Long?): Double {
        val t = max(similarity(cleanTitle(query.title), cleanTitle(title)), similarity(query.title, title))
        val qa = query.artist?.takeIf { it.isNotBlank() }
        val a = if (qa != null && !artist.isNullOrBlank()) {
            maxOf(similarity(qa, artist), similarity(cleanArtist(qa), cleanArtist(artist)), romanizedSimilarity(qa, artist))
        } else null
        val d = if (query.durationMs != null && query.durationMs > 0 && durationMs != null && durationMs > 0) {
            val diff = abs(query.durationMs - durationMs)
            when {
                diff <= 2_000 -> 1.0
                diff <= 5_000 -> 0.8
                diff <= 10_000 -> 0.4
                else -> 0.0
            }
        } else null

        var s = when {
            a != null && d != null -> 0.55 * t + 0.3 * a + 0.15 * d
            a != null -> 0.62 * t + 0.38 * a
            d != null -> 0.8 * t + 0.2 * d
            else -> t
        }
        if (d == 0.0) s *= 0.6
        // Mismo título pero otro artista: casi seguro otra canción (aunque la duración coincida).
        if (a != null && a < 0.35) s *= 0.7
        return s
    }

    /**
     * Compara nombres escritos en alfabetos distintos (米津玄師 ↔ Kenshi Yonezu, ヨアソビ ↔ YOASOBI)
     * pasando ambos a letras latinas y comparando por palabras sin importar el orden.
     */
    private fun romanizedSimilarity(a: String, b: String): Double {
        val latinA = a.all { it.code < 0x250 }
        val latinB = b.all { it.code < 0x250 }
        if (latinA && latinB) return 0.0
        val ra = normalize(runCatching { Romanizer.romanizeText(a) }.getOrDefault(a))
        val rb = normalize(runCatching { Romanizer.romanizeText(b) }.getOrDefault(b))
        if (ra.isEmpty() || rb.isEmpty()) return 0.0
        val ta = ra.split(' ').toSet()
        val tb = rb.split(' ').toSet()
        val tokens = ta.intersect(tb).size.toDouble() / ta.union(tb).size
        return maxOf(tokens, similarity(ra.replace(" ", ""), rb.replace(" ", "")))
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}
