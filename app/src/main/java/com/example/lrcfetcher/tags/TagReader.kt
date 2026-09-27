package com.example.lrcfetcher.tags

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Base64

/** Acceso aleatorio a un archivo (FileChannel de SAF o RandomAccessFile). */
interface ByteSource {
    val size: Long
    fun read(pos: Long, len: Int): ByteArray?

    companion object {
        fun of(ch: FileChannel) = object : ByteSource {
            override val size: Long get() = ch.size()
            override fun read(pos: Long, len: Int): ByteArray? {
                if (len < 0 || pos < 0 || pos + len > size) return null
                val buf = ByteBuffer.allocate(len)
                var p = pos
                while (buf.hasRemaining()) {
                    val n = ch.read(buf, p)
                    if (n <= 0) return null
                    p += n
                }
                return buf.array()
            }
        }

        fun of(raf: RandomAccessFile) = object : ByteSource {
            override val size: Long get() = raf.length()
            override fun read(pos: Long, len: Int): ByteArray? {
                if (len < 0 || pos < 0 || pos + len > size) return null
                val b = ByteArray(len)
                raf.seek(pos)
                raf.readFully(b)
                return b
            }
        }
    }
}

/**
 * Lee título, álbum, artistas, artista del álbum, compositores, géneros, año, pista/total,
 * disco/total y si hay portada/letra. Se leen las etiquetas del propio archivo (no se deducen
 * del nombre), lo que evita confundir título y artista.
 */
object TagReader {

    fun read(src: ByteSource, ext: String): TagInfo = runCatching {
        when (ext) {
            "mp3", "aac" -> readId3(src) ?: readApe(src) ?: readId3v1(src) ?: TagInfo()
            "wav" -> readRiff(src) ?: TagInfo()
            "aiff", "aif", "aifc" -> aiffId3(src)?.let { readId3(it) } ?: TagInfo()
            "ape", "wv", "mpc" -> readApe(src) ?: TagInfo()
            "wma", "asf" -> readAsf(src) ?: TagInfo()
            "flac" -> readFlac(src)
            "m4a", "mp4", "alac", "m4b" -> readMp4(src)
            "ogg", "oga", "opus" -> readOgg(src)
            else -> TagInfo()
        }
    }.getOrDefault(TagInfo())

    /** Bytes de la portada incrustada, si existe. */
    fun readCover(src: ByteSource, ext: String): Cover? = runCatching {
        when (ext) {
            "mp3", "aac" -> id3Cover(src)
            "wav" -> riffId3(src)?.let { id3Cover(it) }
            "aiff", "aif", "aifc" -> aiffId3(src)?.let { id3Cover(it) }
            "ogg", "oga", "opus" -> oggComments(src)?.get("METADATA_BLOCK_PICTURE")?.firstOrNull()?.let(::decodeBase64Picture)
            "flac" -> flacCover(src)
            "m4a", "mp4", "alac", "m4b" -> mp4Cover(src)
            else -> null
        }
    }.getOrNull()

    /**
     * Letra incrustada en el archivo (texto tal cual: LRC, LRC mejorado o sin tiempos). Si hay
     * varias, se prefiere la que trae marcas de tiempo.
     */
    fun readLyrics(src: ByteSource, ext: String): String? = runCatching {
        val all: List<String> = when (ext) {
            "mp3", "aac" -> id3Lyrics(src)?.takeIf { it.isNotEmpty() } ?: apeItems(src)?.first?.get("LYRICS")?.let(::listOf).orEmpty()
            "wav" -> riffId3(src)?.let { id3Lyrics(it) }.orEmpty()
            "aiff", "aif", "aifc" -> aiffId3(src)?.let { id3Lyrics(it) }.orEmpty()
            "ape", "wv", "mpc" -> apeItems(src)?.first?.get("LYRICS")?.let(::listOf).orEmpty()
            "flac" -> flacBlocks(src)?.firstOrNull { it.type == 4 }?.let { b -> src.read(b.pos + 4, b.len) }
                ?.let { vorbisLyrics(parseVorbisComments(it)) }.orEmpty()
            "ogg", "oga", "opus" -> oggComments(src)?.let(::vorbisLyrics).orEmpty()
            "m4a", "mp4", "alac", "m4b" -> ilst(src)?.let { l ->
                boxes(src, l.contentStart, l.end).filter { it.type == "©lyr" }
                    .mapNotNull { itemData(src, it, 4 shl 20)?.second?.let { d -> String(d, Charsets.UTF_8) } }
            }.orEmpty()
            else -> emptyList()
        }
        val texts = all.map { it.replace("\r\n", "\n").replace('\r', '\n').trim() }.filter { it.isNotBlank() }
        texts.firstOrNull { TIMED.containsMatchIn(it) } ?: texts.maxByOrNull { it.length }
    }.getOrNull()

    private val TIMED = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")

    private fun vorbisLyrics(c: Map<String, List<String>>): List<String> =
        listOf("SYNCEDLYRICS", "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS").flatMap { c[it].orEmpty() }

    /** USLT (texto) y SYLT (sincronizada, convertida a LRC) de una etiqueta ID3v2. */
    private fun id3Lyrics(src: ByteSource): List<String>? {
        val tag = id3Frames(src) ?: return null
        val out = mutableListOf<String>()
        for (f in tag.frames) {
            val d = (if (f.id in setOf("USLT", "ULT", "SYLT", "SLT")) frameData(src, tag, f) else null) ?: continue
            if (d.size < 5) continue
            val enc = d[0].toInt()
            when (f.id) {
                "USLT", "ULT" -> {
                    // codificación, idioma (3), descripción terminada en 0, texto
                    val p = skipTerminated(d, 4, enc)
                    if (p < d.size) out += decodeString(d.copyOfRange(p, d.size), enc)
                }
                else -> syltToLrc(d, enc)?.let { out += it }
            }
        }
        return out
    }

    private fun skipTerminated(d: ByteArray, from: Int, enc: Int): Int {
        var p = from
        if (enc == 1 || enc == 2) {
            while (p + 1 < d.size && !(d[p].toInt() == 0 && d[p + 1].toInt() == 0)) p += 2
            return p + 2
        }
        while (p < d.size && d[p].toInt() != 0) p++
        return p + 1
    }

    private fun decodeString(b: ByteArray, enc: Int): String = when (enc) {
        0 -> String(b, Charsets.ISO_8859_1)
        1 -> String(b, Charsets.UTF_16)
        2 -> String(b, Charsets.UTF_16BE)
        else -> String(b, Charsets.UTF_8)
    }.trimEnd('\u0000').trimStart('﻿')

    /**
     * SYLT: fragmentos de texto con su tiempo (en ms si el formato es 2). Un fragmento que
     * empieza con salto de línea abre una línea nueva; si ninguno lo hace, cada uno es una línea.
     */
    private fun syltToLrc(d: ByteArray, enc: Int): String? {
        if (d[4].toInt() != 2) return null // sólo tiempos en milisegundos
        var p = skipTerminated(d, 6, enc)
        val parts = mutableListOf<Pair<Long, String>>()
        while (p < d.size) {
            val end = skipTerminated(d, p, enc)
            if (end + 4 > d.size) break
            val text = decodeString(d.copyOfRange(p, (end - if (enc == 1 || enc == 2) 2 else 1).coerceAtLeast(p)), enc)
            parts += u32(d, end) to text
            p = end + 4
        }
        if (parts.isEmpty()) return null
        fun t(ms: Long) = "%02d:%02d.%02d".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10)
        val grouped = parts.any { it.second.startsWith("\n") || it.second.startsWith("\r") }
        if (!grouped) return parts.joinToString("\n") { (ms, s) -> "[${t(ms)}]${s.trim()}" }
        val lines = mutableListOf<MutableList<Pair<Long, String>>>()
        for ((ms, s) in parts) {
            if (lines.isEmpty() || s.startsWith("\n") || s.startsWith("\r")) lines += mutableListOf<Pair<Long, String>>()
            lines.last() += ms to s.trimStart('\n', '\r')
        }
        return lines.filter { l -> l.any { it.second.isNotBlank() } }.joinToString("\n") { l ->
            "[${t(l.first().first)}]" + l.joinToString("") { (ms, s) -> "<${t(ms)}>$s" }
        }
    }

    // ------------------------------------------------------------------ utilidades

    internal fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    internal fun synchsafe(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0x7F) shl 21) or ((b[o + 1].toLong() and 0x7F) shl 14) or
            ((b[o + 2].toLong() and 0x7F) shl 7) or (b[o + 3].toLong() and 0x7F)

    internal fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    // ------------------------------------------------------------------ ID3v2

    private class Id3Frame(val id: String, val dataPos: Long, val size: Int, val flags: Int)

    private class Id3Tag(val version: Int, val frames: List<Id3Frame>, val bytes: ByteArray?)

    /** Recorre los frames leyendo sólo cabeceras (se salta portadas y demás). */
    private fun id3Frames(src: ByteSource): Id3Tag? {
        val h = src.read(0, 10) ?: return null
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
        val version = h[3].toInt()
        val flags = h[5].toInt()
        val tagSize = synchsafe(h, 6).toInt()
        // "Unsynchronisation" de toda la etiqueta (raro): se lee entera y se deshace.
        val unsync = version < 4 && flags and 0x80 != 0
        val bytes: ByteArray? = if (unsync) src.read(10, tagSize)?.let(::deUnsync) else null
        fun rd(pos: Long, len: Int): ByteArray? =
            if (bytes != null) (pos - 10).toInt().let { o -> if (o >= 0 && o + len <= bytes.size) bytes.copyOfRange(o, o + len) else null }
            else src.read(pos, len)

        val end = 10L + (bytes?.size ?: tagSize)
        var pos = 10L
        if (flags and 0x40 != 0 && version >= 3) {
            val ext = rd(pos, 4) ?: return null
            pos += if (version == 4) synchsafe(ext, 0) else u32(ext, 0) + 4
        }
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        val frames = mutableListOf<Id3Frame>()
        while (pos + headLen <= end) {
            val fh = rd(pos, headLen) ?: break
            if (fh[0].toInt() == 0) break
            val id = String(fh, 0, idLen, Charsets.ISO_8859_1)
            if (!id.all { it.isLetterOrDigit() }) break
            val size = when (version) {
                2 -> ((fh[3].toInt() and 0xFF) shl 16) or ((fh[4].toInt() and 0xFF) shl 8) or (fh[5].toInt() and 0xFF)
                4 -> synchsafe(fh, 4).toInt()
                else -> u32(fh, 4).toInt()
            }
            if (size <= 0 || pos + headLen + size > end) break
            val fflags = if (version == 2) 0 else fh[9].toInt() and 0xFF
            frames += Id3Frame(id, pos + headLen, size, fflags)
            pos += headLen + size
        }
        return Id3Tag(version, frames, bytes)
    }

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    /** Datos de un frame, quitando indicadores de v2.4 (longitud, unsync). null si está comprimido/cifrado. */
    private fun frameData(src: ByteSource, tag: Id3Tag, f: Id3Frame): ByteArray? {
        var data = if (tag.bytes != null) {
            val o = (f.dataPos - 10).toInt()
            tag.bytes.copyOfRange(o, o + f.size)
        } else src.read(f.dataPos, f.size) ?: return null
        if (tag.version == 4) {
            if (f.flags and 0x0C != 0) return null
            if (f.flags and 0x40 != 0) data = data.copyOfRange(1, data.size)
            if (f.flags and 0x01 != 0) data = data.copyOfRange(4, data.size)
            if (f.flags and 0x02 != 0) data = deUnsync(data)
        } else if (tag.version == 3) {
            if (f.flags and 0xC0 != 0) return null
            if (f.flags and 0x20 != 0) data = data.copyOfRange(1, data.size)
        }
        return data
    }

    /** Texto de un frame T***: codificación + valores separados por \u0000. */
    internal fun decodeText(data: ByteArray): List<String> {
        if (data.isEmpty()) return emptyList()
        val body = data.copyOfRange(1, data.size)
        val text = when (data[0].toInt()) {
            0 -> String(body, Charsets.ISO_8859_1)
            1 -> String(body, Charsets.UTF_16)
            2 -> String(body, Charsets.UTF_16BE)
            else -> String(body, Charsets.UTF_8)
        }
        return text.split('\u0000').map { it.trim().trimStart('﻿') }.filter { it.isNotEmpty() }
    }

    private val TEXT_IDS = setOf(
        "TIT2", "TT2", "TALB", "TAL", "TPE1", "TP1", "TPE2", "TP2", "TCOM", "TCM", "TCON", "TCO",
        "TYER", "TYE", "TDRC", "TORY", "TDOR", "TRCK", "TRK", "TPOS", "TPA",
    )

    private fun readId3(src: ByteSource): TagInfo? {
        val tag = id3Frames(src) ?: return null
        val values = mutableMapOf<String, List<String>>()
        var cover = false
        var lyrics = false
        for (f in tag.frames) {
            when (f.id) {
                in TEXT_IDS -> frameData(src, tag, f)?.let { d -> values.putIfAbsent(f.id, decodeText(d)) }
                "APIC", "PIC" -> cover = true
                "USLT", "SYLT", "ULT", "SLT" -> lyrics = lyrics || f.size > 8
            }
        }
        fun first(vararg ids: String) = ids.firstNotNullOfOrNull { values[it]?.firstOrNull() }
        fun list(vararg ids: String): List<String> {
            val raw = ids.firstNotNullOfOrNull { values[it] }.orEmpty()
            // En ID3v2.3 varios valores suelen venir como "A; B" o "A/B" (sólo en compositores).
            return raw.flatMap { TrackTags.splitList(it) }
        }
        val (track, trackTotal) = TrackTags.parsePair(first("TRCK", "TRK"))
        val (disc, discTotal) = TrackTags.parsePair(first("TPOS", "TPA"))
        val tags = TrackTags(
            title = first("TIT2", "TT2"),
            album = first("TALB", "TAL"),
            artists = list("TPE1", "TP1"),
            albumArtist = first("TPE2", "TP2"),
            composers = list("TCOM", "TCM"),
            genres = list("TCON", "TCO").map(::genreName).filter { it.isNotEmpty() },
            year = first("TDRC", "TYER", "TYE", "TDOR", "TORY"),
            trackNumber = track, trackTotal = trackTotal, discNumber = disc, discTotal = discTotal,
        ).normalized()
        val v1 = if (tags.title == null) readId3v1(src)?.tags else null
        return TagInfo(if (v1 != null) tags.fillFrom(v1) else tags, cover, lyrics)
    }

    private fun id3Cover(src: ByteSource): Cover? {
        val tag = id3Frames(src) ?: return null
        val f = tag.frames.firstOrNull { it.id == "APIC" || it.id == "PIC" } ?: return null
        val d = frameData(src, tag, f) ?: return null
        val enc = d[0].toInt()
        var p = 1
        val mime: String
        if (f.id == "PIC") {
            mime = if (String(d, 1, 3, Charsets.ISO_8859_1).equals("PNG", true)) "image/png" else "image/jpeg"
            p = 4
        } else {
            val end = (p until d.size).first { d[it].toInt() == 0 }
            mime = String(d, p, end - p, Charsets.ISO_8859_1)
            p = end + 1
        }
        p++ // tipo de imagen
        // descripción terminada en 0 (o 00 00 en UTF-16)
        if (enc == 1 || enc == 2) {
            while (p + 1 < d.size && !(d[p].toInt() == 0 && d[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < d.size && d[p].toInt() != 0) p++
            p++
        }
        if (p >= d.size) return null
        val data = d.copyOfRange(p, d.size)
        return Cover(data, mime.ifBlank { Cover.detectMime(data) }.let { if (it.contains('/')) it else "image/$it" })
    }

    private fun readId3v1(src: ByteSource): TagInfo? {
        if (src.size < 128) return null
        val b = src.read(src.size - 128, 128) ?: return null
        if (String(b, 0, 3, Charsets.ISO_8859_1) != "TAG") return null
        fun s(o: Int, l: Int) = String(b, o, l, Charsets.ISO_8859_1).trimEnd('\u0000', ' ').ifBlank { null }
        val track = if (b[125].toInt() == 0 && b[126].toInt() != 0) b[126].toInt() and 0xFF else null
        val genre = (b[127].toInt() and 0xFF).let { ID3_GENRES.getOrNull(it) }
        return TagInfo(
            TrackTags(
                title = s(3, 30), artists = TrackTags.splitList(s(33, 30)), album = s(63, 30), year = s(93, 4),
                trackNumber = track, genres = listOfNotNull(genre),
            ).normalized(),
        )
    }

    /** "(17)", "17", "(17)Rock" → "Rock". */
    internal fun genreName(raw: String): String {
        val m = Regex("""^\((\d+)\)(.*)$""").find(raw)
        if (m != null) return m.groupValues[2].ifBlank { ID3_GENRES.getOrNull(m.groupValues[1].toInt()).orEmpty() }
        raw.toIntOrNull()?.let { return ID3_GENRES.getOrNull(it).orEmpty() }
        return when (raw) {
            "(RX)" -> "Remix"
            "(CR)" -> "Cover"
            else -> raw
        }
    }

    internal val ID3_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    // ------------------------------------------------------------------ Vorbis comments (FLAC/OGG)

    internal fun parseVorbisComments(data: ByteArray, offset: Int = 0): Map<String, List<String>> {
        val map = linkedMapOf<String, MutableList<String>>()
        var p = offset
        val vendorLen = le32(data, p); p += 4 + vendorLen
        val count = le32(data, p); p += 4
        repeat(count) {
            if (p + 4 > data.size) return map
            val len = le32(data, p); p += 4
            if (len < 0 || p + len > data.size) return map
            val c = String(data, p, len, Charsets.UTF_8); p += len
            val eq = c.indexOf('=')
            if (eq > 0) map.getOrPut(c.substring(0, eq).uppercase()) { mutableListOf() } += c.substring(eq + 1)
        }
        return map
    }

    internal fun tagsFromVorbis(c: Map<String, List<String>>): TagInfo {
        fun first(vararg k: String) = k.firstNotNullOfOrNull { c[it]?.firstOrNull()?.trim()?.ifBlank { null } }
        fun list(vararg k: String) = k.firstNotNullOfOrNull { c[it] }.orEmpty().flatMap { TrackTags.splitList(it) }
        val (track, trackTotalInline) = TrackTags.parsePair(first("TRACKNUMBER"))
        val (disc, discTotalInline) = TrackTags.parsePair(first("DISCNUMBER"))
        val tags = TrackTags(
            title = first("TITLE"),
            album = first("ALBUM"),
            artists = list("ARTIST"),
            albumArtist = first("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST"),
            composers = list("COMPOSER"),
            genres = list("GENRE"),
            year = first("DATE", "YEAR", "ORIGINALDATE"),
            trackNumber = track,
            trackTotal = first("TRACKTOTAL", "TOTALTRACKS")?.toIntOrNull() ?: trackTotalInline,
            discNumber = disc,
            discTotal = first("DISCTOTAL", "TOTALDISCS")?.toIntOrNull() ?: discTotalInline,
        ).normalized()
        val lyrics = listOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS").any { !c[it].isNullOrEmpty() }
        return TagInfo(tags, hasCover = c.containsKey("METADATA_BLOCK_PICTURE") || c.containsKey("COVERART"), hasLyrics = lyrics)
    }

    // ------------------------------------------------------------------ FLAC

    private class FlacBlock(val pos: Long, val type: Int, val len: Int, val last: Boolean)

    private fun flacBlocks(src: ByteSource): List<FlacBlock>? {
        val magic = src.read(0, 4) ?: return null
        var pos = 4L
        // Algunos FLAC llevan una etiqueta ID3 delante.
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") {
            val h = src.read(0, 10) ?: return null
            if (String(h, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
            pos = 10 + synchsafe(h, 6) + 4
            if (String(src.read(pos - 4, 4) ?: return null, Charsets.ISO_8859_1) != "fLaC") return null
        }
        val out = mutableListOf<FlacBlock>()
        while (true) {
            val h = src.read(pos, 4) ?: return out
            val last = h[0].toInt() and 0x80 != 0
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            out += FlacBlock(pos, h[0].toInt() and 0x7F, len, last)
            if (last) return out
            pos += 4 + len
        }
    }

    private fun readFlac(src: ByteSource): TagInfo {
        val blocks = flacBlocks(src) ?: return TagInfo()
        val vc = blocks.firstOrNull { it.type == 4 }?.let { b -> src.read(b.pos + 4, b.len) }
        val info = vc?.let { tagsFromVorbis(parseVorbisComments(it)) } ?: TagInfo()
        return info.copy(hasCover = info.hasCover || blocks.any { it.type == 6 })
    }

    private fun flacCover(src: ByteSource): Cover? {
        val blocks = flacBlocks(src) ?: return null
        val pics = blocks.filter { it.type == 6 }.mapNotNull { b -> src.read(b.pos + 4, b.len)?.let(::parsePictureBlock) }
        return pics.firstOrNull { it.first == 3 }?.second ?: pics.firstOrNull()?.second
    }

    /** Bloque PICTURE de FLAC → (tipo, portada). */
    internal fun parsePictureBlock(d: ByteArray): Pair<Int, Cover>? {
        var p = 0
        fun be(): Int = u32(d, p).toInt().also { p += 4 }
        val type = be()
        val mimeLen = be()
        val mime = String(d, p, mimeLen, Charsets.US_ASCII); p += mimeLen
        val descLen = be(); p += descLen
        p += 16 // ancho, alto, profundidad, colores
        val len = be()
        if (p + len > d.size) return null
        val data = d.copyOfRange(p, p + len)
        return type to Cover(data, mime.ifBlank { Cover.detectMime(data) })
    }

    // ------------------------------------------------------------------ OGG (Vorbis / Opus)

    /** Reconstruye el paquete de comentarios leyendo páginas Ogg. null si no es Vorbis/Opus. */
    internal fun oggComments(src: ByteSource): Map<String, List<String>>? {
        val packet = java.io.ByteArrayOutputStream()
        var pos = 0L
        var packetIndex = 0
        var pages = 0
        while (pages++ < 1024 && packetIndex < 2) {
            val h = src.read(pos, 27) ?: break
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") break
            val segs = h[26].toInt() and 0xFF
            val table = src.read(pos + 27, segs) ?: break
            var dataPos = pos + 27 + segs
            for (seg in table) {
                val len = seg.toInt() and 0xFF
                if (packetIndex == 1) packet.write(src.read(dataPos, len) ?: break)
                dataPos += len
                if (len < 255) {
                    packetIndex++
                    if (packetIndex == 2) break
                }
            }
            pos = pos + 27 + segs + table.sumOf { it.toInt() and 0xFF }
            if (packet.size() > 64 * 1024 * 1024) break
        }
        val p = packet.toByteArray()
        val offset = when {
            p.size > 7 && String(p, 0, 7, Charsets.ISO_8859_1) == "\u0003vorbis" -> 7
            p.size > 8 && String(p, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> 8
            else -> return null
        }
        return parseVorbisComments(p, offset)
    }

    private fun readOgg(src: ByteSource): TagInfo = oggComments(src)?.let(::tagsFromVorbis) ?: TagInfo()

    // ------------------------------------------------------------------ WAV / AIFF (ID3 dentro de un "chunk")

    /** Vista de una parte del archivo como si fuera un archivo aparte. */
    private fun ByteSource.slice(offset: Long, length: Long): ByteSource {
        val parent = this
        return object : ByteSource {
            override val size: Long get() = length
            override fun read(pos: Long, len: Int): ByteArray? =
                if (pos < 0 || pos + len > length) null else parent.read(offset + pos, len)
        }
    }

    private class Chunk(val id: String, val dataPos: Long, val size: Long)

    private fun chunks(src: ByteSource, from: Long, littleEndian: Boolean): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var p = from
        while (p + 8 <= src.size && out.size < 512) {
            val h = src.read(p, 8) ?: break
            val size = if (littleEndian) le32(h, 4).toLong() and 0xFFFFFFFFL else u32(h, 4)
            out += Chunk(String(h, 0, 4, Charsets.ISO_8859_1), p + 8, size)
            p += 8 + size + (size and 1)
        }
        return out
    }

    private fun riffId3(src: ByteSource): ByteSource? {
        val h = src.read(0, 12) ?: return null
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "RIFF") return null
        val c = chunks(src, 12, littleEndian = true).firstOrNull { it.id.equals("id3 ", true) } ?: return null
        return src.slice(c.dataPos, c.size)
    }

    private fun aiffId3(src: ByteSource): ByteSource? {
        val h = src.read(0, 12) ?: return null
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "FORM") return null
        val c = chunks(src, 12, littleEndian = false).firstOrNull { it.id.equals("ID3 ", true) } ?: return null
        return src.slice(c.dataPos, c.size)
    }

    /** WAV: etiqueta ID3 dentro del RIFF y, si no hay, la lista LIST/INFO. */
    private fun readRiff(src: ByteSource): TagInfo? {
        val id3 = riffId3(src)?.let { readId3(it) }
        val h = src.read(0, 12) ?: return id3
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "RIFF") return id3
        val list = chunks(src, 12, littleEndian = true).firstOrNull { c ->
            c.id == "LIST" && src.read(c.dataPos, 4)?.let { String(it, Charsets.ISO_8859_1) } == "INFO"
        } ?: return id3
        val info = mutableMapOf<String, String>()
        for (c in chunks(src, list.dataPos + 4, littleEndian = true)) {
            if (c.dataPos + c.size > list.dataPos + list.size || c.size > 65_536) break
            val v = src.read(c.dataPos, c.size.toInt())?.let { String(it, Charsets.UTF_8).trimEnd('\u0000', ' ') } ?: continue
            if (v.isNotBlank()) info[c.id] = v
        }
        val fromInfo = TrackTags(
            title = info["INAM"], artists = TrackTags.splitList(info["IART"]), album = info["IPRD"],
            genres = TrackTags.splitList(info["IGNR"]), year = info["ICRD"],
            trackNumber = TrackTags.parsePair(info["ITRK"] ?: info["IPRT"]).first,
        ).normalized()
        return if (id3 != null) id3.copy(tags = id3.tags.fillFrom(fromInfo)) else TagInfo(fromInfo)
    }

    // ------------------------------------------------------------------ APEv2 (APE, WavPack, Musepack…)

    /** Ítems de texto de la etiqueta APEv2 (claves en mayúsculas) y si trae portada. */
    private fun apeItems(src: ByteSource): Pair<Map<String, String>, Boolean>? {
        val candidates = listOf(src.size - 32, src.size - 128 - 32)
        for (footerPos in candidates) {
            if (footerPos < 0) continue
            val f = src.read(footerPos, 32) ?: continue
            if (String(f, 0, 8, Charsets.ISO_8859_1) != "APETAGEX") continue
            val size = le32(f, 12)
            val count = le32(f, 16)
            val itemsPos = footerPos + 32 - size
            if (size <= 32 || itemsPos < 0 || size > 64 * 1024 * 1024) return null
            val data = src.read(itemsPos, size - 32) ?: return null
            val items = mutableMapOf<String, String>()
            var hasCover = false
            var p = 0
            repeat(count) {
                if (p + 8 > data.size) return@repeat
                val vlen = le32(data, p)
                val flags = le32(data, p + 4)
                p += 8
                var k = p
                while (k < data.size && data[k].toInt() != 0) k++
                val key = String(data, p, k - p, Charsets.ISO_8859_1).uppercase()
                p = k + 1
                if (vlen < 0 || p + vlen > data.size) return@repeat
                if (key.startsWith("COVER ART")) hasCover = true
                else if ((flags shr 1) and 3 == 0) items[key] = String(data, p, vlen, Charsets.UTF_8)
                p += vlen
            }
            return items to hasCover
        }
        return null
    }

    private fun readApe(src: ByteSource): TagInfo? {
        val (items, hasCover) = apeItems(src) ?: return null
        val (track, trackTotal) = TrackTags.parsePair(items["TRACK"])
        val (disc, discTotal) = TrackTags.parsePair(items["DISC"])
        val tags = TrackTags(
            title = items["TITLE"], artists = TrackTags.splitList(items["ARTIST"]), album = items["ALBUM"],
            albumArtist = items["ALBUM ARTIST"] ?: items["ALBUMARTIST"], composers = TrackTags.splitList(items["COMPOSER"]),
            genres = TrackTags.splitList(items["GENRE"]), year = items["YEAR"],
            trackNumber = track, trackTotal = trackTotal, discNumber = disc, discTotal = discTotal,
        ).normalized()
        return TagInfo(tags, hasCover = hasCover, hasLyrics = !items["LYRICS"].isNullOrBlank())
    }

    // ------------------------------------------------------------------ ASF / WMA

    private fun guid(b: ByteArray, o: Int) = b.copyOfRange(o, o + 16).joinToString("") { "%02X".format(it) }

    private const val ASF_HEADER = "3026B2758E66CF11A6D900AA0062CE6C"
    private const val ASF_CONTENT = "3326B2758E66CF11A6D900AA0062CE6C"
    private const val ASF_EXTENDED = "40A4D0D207E3D21197F000A0C95EA850"

    private fun readAsf(src: ByteSource): TagInfo? {
        val h = src.read(0, 30) ?: return null
        if (guid(h, 0) != ASF_HEADER) return null
        val headerSize = minOf(readLe64(h, 16), src.size)
        var p = 30L
        val v = mutableMapOf<String, String>()
        var hasCover = false
        while (p + 24 <= headerSize) {
            val oh = src.read(p, 24) ?: break
            val size = readLe64(oh, 16)
            if (size < 24 || p + size > headerSize) break
            val id = guid(oh, 0)
            if ((id == ASF_CONTENT || id == ASF_EXTENDED) && size < 32L * 1024 * 1024) {
                val d = src.read(p + 24, (size - 24).toInt()) ?: break
                if (id == ASF_CONTENT) {
                    val lens = (0 until 5).map { le16(d, it * 2) }
                    var q = 10
                    val names = listOf("Title", "Author")
                    for (i in 0 until 2) {
                        v[names[i]] = String(d, q, lens[i], Charsets.UTF_16LE).trimEnd('\u0000')
                        q += lens[i]
                    }
                } else {
                    val count = le16(d, 0)
                    var q = 2
                    repeat(count) {
                        if (q + 2 > d.size) return@repeat
                        val nl = le16(d, q); q += 2
                        val name = String(d, q, nl, Charsets.UTF_16LE).trimEnd('\u0000'); q += nl
                        val type = le16(d, q); q += 2
                        val vl = le16(d, q); q += 2
                        if (q + vl > d.size) return@repeat
                        val value = when (type) {
                            0 -> String(d, q, vl, Charsets.UTF_16LE).trimEnd('\u0000')
                            3 -> le32(d, q).toString()
                            5 -> le16(d, q).toString()
                            4 -> readLe64(d, q).toString()
                            else -> null
                        }
                        if (name == "WM/Picture") hasCover = true
                        if (value != null) v[name] = value
                        q += vl
                    }
                }
            }
            p += size
        }
        val (disc, discTotal) = TrackTags.parsePair(v["WM/PartOfSet"])
        val tags = TrackTags(
            title = v["Title"]?.ifBlank { null }, artists = TrackTags.splitList(v["Author"]), album = v["WM/AlbumTitle"],
            albumArtist = v["WM/AlbumArtist"], composers = TrackTags.splitList(v["WM/Composer"]),
            genres = TrackTags.splitList(v["WM/Genre"]), year = v["WM/Year"],
            trackNumber = TrackTags.parsePair(v["WM/TrackNumber"] ?: v["WM/Track"]).first, discNumber = disc, discTotal = discTotal,
        ).normalized()
        return TagInfo(tags, hasCover = hasCover, hasLyrics = !v["WM/Lyrics"].isNullOrBlank())
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readLe64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    // ------------------------------------------------------------------ MP4

    internal class Box(val type: String, val start: Long, val header: Int, val size: Long) {
        val end get() = start + size
        val contentStart get() = start + header
    }

    internal fun boxes(src: ByteSource, from: Long, to: Long): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            val h = src.read(p, 8) ?: break
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                val big = src.read(p + 8, 8) ?: break
                size = (u32(big, 0) shl 32) or u32(big, 4)
                header = 16
            } else if (size == 0L) size = to - p
            if (size < header || p + size > to) break
            out += Box(type, p, header, size)
            p += size
        }
        return out
    }

    private fun ilst(src: ByteSource): Box? {
        val moov = boxes(src, 0, src.size).firstOrNull { it.type == "moov" } ?: return null
        val udta = boxes(src, moov.contentStart, moov.end).firstOrNull { it.type == "udta" } ?: return null
        val meta = boxes(src, udta.contentStart, udta.end).firstOrNull { it.type == "meta" } ?: return null
        return boxes(src, meta.contentStart + 4, meta.end).firstOrNull { it.type == "ilst" }
    }

    /** Contenido del átomo "data" de un ítem: (tipo, bytes del valor). */
    private fun itemData(src: ByteSource, item: Box, maxLen: Int = 1 shl 20): Pair<Int, ByteArray>? {
        val data = boxes(src, item.contentStart, item.end).firstOrNull { it.type == "data" } ?: return null
        val len = (data.size - data.header - 8).toInt()
        if (len < 0 || len > maxLen) return null
        val head = src.read(data.contentStart, 8) ?: return null
        val type = u32(head, 0).toInt() and 0xFFFFFF
        return type to (src.read(data.contentStart + 8, len) ?: return null)
    }

    private fun readMp4(src: ByteSource): TagInfo {
        val ilst = ilst(src) ?: return TagInfo()
        val items = boxes(src, ilst.contentStart, ilst.end)
        fun text(type: String): String? = items.firstOrNull { it.type == type }
            ?.let { itemData(src, it) }?.second?.let { String(it, Charsets.UTF_8).trim() }?.ifBlank { null }
        fun pair(type: String): Pair<Int?, Int?> {
            val d = items.firstOrNull { it.type == type }?.let { itemData(src, it) }?.second ?: return null to null
            if (d.size < 6) return null to null
            val n = ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
            val t = ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
            return n.takeIf { it > 0 } to t.takeIf { it > 0 }
        }
        val genreText = text("©gen") ?: items.firstOrNull { it.type == "gnre" }?.let { itemData(src, it) }?.second
            ?.takeIf { it.size >= 2 }?.let { ID3_GENRES.getOrNull((((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)) - 1) }
        val (track, trackTotal) = pair("trkn")
        val (disc, discTotal) = pair("disk")
        val tags = TrackTags(
            title = text("©nam"),
            album = text("©alb"),
            artists = TrackTags.splitList(text("©ART")),
            albumArtist = text("aART"),
            composers = TrackTags.splitList(text("©wrt")),
            genres = TrackTags.splitList(genreText),
            year = text("©day"),
            trackNumber = track, trackTotal = trackTotal, discNumber = disc, discTotal = discTotal,
        ).normalized()
        return TagInfo(tags, hasCover = items.any { it.type == "covr" }, hasLyrics = items.any { it.type == "©lyr" })
    }

    private fun mp4Cover(src: ByteSource): Cover? {
        val ilst = ilst(src) ?: return null
        val covr = boxes(src, ilst.contentStart, ilst.end).firstOrNull { it.type == "covr" } ?: return null
        val (type, data) = itemData(src, covr, 32 shl 20) ?: return null
        return Cover(data, if (type == 14) "image/png" else Cover.detectMime(data))
    }

    /** Datos sin procesar de un bloque Vorbis para pruebas. */
    internal fun decodeBase64Picture(value: String): Cover? =
        runCatching { parsePictureBlock(Base64.getDecoder().decode(value))?.second }.getOrNull()
}
