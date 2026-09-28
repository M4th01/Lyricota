package com.example.lrcfetcher.tags

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Escribe metadatos, portada y/o letra en MP3 (ID3v2.3/2.4), FLAC (VORBIS_COMMENT + PICTURE)
 * y M4A/MP4 (moov/udta/meta/ilst). Sólo se tocan los campos que gestiona Lyricota; el resto
 * de etiquetas y el audio se conservan tal cual.
 *
 * El resultado se describe como un "plan" (trozos nuevos + rangos del original) que luego se
 * copia por partes, sin cargar el audio en memoria.
 */
object TagWriter {

    val WRITABLE = setOf("mp3", "flac", "m4a", "mp4", "alac", "m4b", "ogg", "oga", "opus")

    class UnsupportedFormat(ext: String) : Exception("Format .$ext is not supported for writing")

    sealed interface Piece {
        class Bytes(val data: ByteArray) : Piece
        class Range(val from: Long, val to: Long) : Piece
    }

    fun write(src: RandomAccessFile, ext: String, changes: TagChanges, out: OutputStream) = writePlan(src, plan(src, ext, changes), out)

    fun plan(src: RandomAccessFile, ext: String, changes: TagChanges): List<Piece> = when (ext) {
        "mp3" -> planMp3(src, changes)
        "flac" -> planFlac(src, changes)
        "m4a", "mp4", "alac", "m4b" -> planMp4(src, changes)
        "ogg", "oga", "opus" -> planOgg(src, changes)
        else -> throw UnsupportedFormat(ext)
    }

    fun writePlan(src: RandomAccessFile, plan: List<Piece>, out: OutputStream) {
        val buf = ByteArray(1 shl 16)
        for (p in plan) when (p) {
            is Piece.Bytes -> out.write(p.data)
            is Piece.Range -> {
                src.seek(p.from)
                var left = p.to - p.from
                while (left > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    private fun readAt(src: RandomAccessFile, pos: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        src.seek(pos)
        src.readFully(b)
        return b
    }

    // ================================================================== MP3 / ID3v2

    /** Frames que se reemplazan al escribir metadatos. */
    private val ID3_MANAGED = setOf("TIT2", "TALB", "TPE1", "TPE2", "TCOM", "TCON", "TYER", "TDAT", "TDRC", "TRCK", "TPOS")

    private fun planMp3(src: RandomAccessFile, changes: TagChanges): List<Piece> {
        val len = src.length()
        val head = if (len >= 10) readAt(src, 0, 10) else ByteArray(0)
        val hasId3 = head.size == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()

        var version = 3
        var keep = mutableListOf<ByteArray>()
        var skip = 0L
        if (hasId3) {
            val tagVersion = head[3].toInt()
            val flags = head[5].toInt()
            val tagSize = synchsafeToInt(head, 6)
            val footer = if (tagVersion == 4 && flags and 0x10 != 0) 10 else 0
            skip = 10L + tagSize + footer
            if (tagVersion in 3..4 && flags and 0x80 == 0) {
                version = tagVersion
                keep = id3FramesRaw(readAt(src, 10, tagSize), tagVersion, flags).toMutableList()
            } else {
                // v2.2 o "unsynchronisation": se leen los datos con TagReader y se reescribe en v2.3.
                val info = TagReader.read(ByteSource.of(src), "mp3")
                val cover = if (changes.cover == null) TagReader.readCover(ByteSource.of(src), "mp3") else null
                keep = tagFrames(3, info.tags).toMutableList()
                cover?.let { keep += apicFrame(3, it) }
            }
        }
        fun idOf(f: ByteArray) = String(f, 0, 4, Charsets.ISO_8859_1)
        if (changes.tags != null) keep.removeAll { idOf(it) in ID3_MANAGED }
        if (changes.cover != null) keep.removeAll { idOf(it) == "APIC" }
        if (changes.lyrics != null) keep.removeAll { idOf(it) == "USLT" || idOf(it) == "SYLT" }

        changes.tags?.let { keep += tagFrames(version, it.normalized()) }
        changes.cover?.let { keep += apicFrame(version, it) }
        changes.lyrics?.takeIf { it.isNotEmpty() }?.let { keep += usltFrame(version, it) }

        return listOf(Piece.Bytes(buildId3(version, keep)), Piece.Range(skip, len))
    }

    private fun id3FramesRaw(body: ByteArray, version: Int, flags: Int): List<ByteArray> {
        var pos = 0
        if (flags and 0x40 != 0) pos = if (version == 4) synchsafeToInt(body, 0) else beInt(body, 0) + 4
        val frames = mutableListOf<ByteArray>()
        while (pos + 10 <= body.size) {
            if (body[pos].toInt() == 0) break
            val size = if (version == 4) synchsafeToInt(body, pos + 4) else beInt(body, pos + 4)
            if (size < 0 || pos + 10 + size > body.size) break
            frames += body.copyOfRange(pos, pos + 10 + size)
            pos += 10 + size
        }
        return frames
    }

    private fun buildId3(version: Int, frames: List<ByteArray>): ByteArray {
        val padding = 2048
        val size = frames.sumOf { it.size } + padding
        val out = ByteArrayOutputStream(size + 10)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0))
        out.write(synchsafe(size))
        frames.forEach { out.write(it) }
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun frame(version: Int, id: String, payload: ByteArray): ByteArray {
        val header = ByteArrayOutputStream(10)
        header.write(id.toByteArray(Charsets.ISO_8859_1))
        header.write(if (version == 4) synchsafe(payload.size) else beBytes(payload.size))
        header.write(byteArrayOf(0, 0))
        return header.toByteArray() + payload
    }

    /** Texto: v2.4 en UTF-8 con varios valores separados por \0; v2.3 en UTF-16 unidos con "; ". */
    private fun textFrame(version: Int, id: String, values: List<String>): ByteArray {
        val payload = if (version == 4) {
            byteArrayOf(3) + values.joinToString("\u0000").toByteArray(Charsets.UTF_8)
        } else {
            byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + values.joinToString("; ").toByteArray(Charsets.UTF_16LE)
        }
        return frame(version, id, payload)
    }

    private fun tagFrames(version: Int, t: TrackTags): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        fun add(id: String, values: List<String>) { if (values.isNotEmpty()) out += textFrame(version, id, values) }
        add("TIT2", listOfNotNull(t.title))
        add("TALB", listOfNotNull(t.album))
        add("TPE1", t.artists)
        add("TPE2", listOfNotNull(t.albumArtist))
        add("TCOM", t.composers)
        add("TCON", t.genres)
        if (version == 4) add("TDRC", listOfNotNull(t.year)) else add("TYER", listOfNotNull(t.yearNumber))
        add("TRCK", listOfNotNull(pair(t.trackNumber, t.trackTotal)))
        add("TPOS", listOfNotNull(pair(t.discNumber, t.discTotal)))
        return out
    }

    private fun pair(n: Int?, total: Int?): String? = when {
        n == null -> null
        total != null -> "$n/$total"
        else -> "$n"
    }

    private fun apicFrame(version: Int, cover: Cover): ByteArray {
        val payload = byteArrayOf(0) + cover.mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + cover.data
        return frame(version, "APIC", payload)
    }

    /** USLT: v2.4 en UTF-8; v2.3 en UTF-16 con BOM (la v2.3 no admite UTF-8). */
    private fun usltFrame(version: Int, lyrics: String): ByteArray {
        val data = ByteArrayOutputStream()
        if (version == 4) {
            data.write(3)
            data.write("eng".toByteArray(Charsets.ISO_8859_1))
            data.write(0)
            data.write(lyrics.toByteArray(Charsets.UTF_8))
        } else {
            data.write(1)
            data.write("eng".toByteArray(Charsets.ISO_8859_1))
            data.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0))
            data.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            data.write(lyrics.toByteArray(Charsets.UTF_16LE))
        }
        return frame(version, "USLT", data.toByteArray())
    }

    // ================================================================== FLAC

    /** Al escribir (o quitar) la letra se reemplazan todas estas claves. */
    private val VORBIS_LYRICS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")

    private val VORBIS_MANAGED = setOf(
        "TITLE", "ALBUM", "ARTIST", "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST", "COMPOSER", "GENRE", "DATE", "YEAR",
        "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS", "DISCNUMBER", "DISCTOTAL", "TOTALDISCS",
    )

    private fun vorbisEntries(t: TrackTags): List<String> {
        val out = mutableListOf<String>()
        t.title?.let { out += "TITLE=$it" }
        t.album?.let { out += "ALBUM=$it" }
        t.artists.forEach { out += "ARTIST=$it" }
        t.albumArtist?.let { out += "ALBUMARTIST=$it" }
        t.composers.forEach { out += "COMPOSER=$it" }
        t.genres.forEach { out += "GENRE=$it" }
        t.year?.let { out += "DATE=$it" }
        t.trackNumber?.let { out += "TRACKNUMBER=$it" }
        t.trackTotal?.let { out += "TRACKTOTAL=$it"; out += "TOTALTRACKS=$it" }
        t.discNumber?.let { out += "DISCNUMBER=$it" }
        t.discTotal?.let { out += "DISCTOTAL=$it"; out += "TOTALDISCS=$it" }
        return out
    }

    private fun planFlac(src: RandomAccessFile, changes: TagChanges): List<Piece> {
        val len = src.length()
        if (len < 4 || String(readAt(src, 0, 4), Charsets.US_ASCII) != "fLaC") error("Not a valid FLAC file")
        data class Block(val offset: Long, val last: Boolean, val type: Int, val length: Int)

        val blocks = mutableListOf<Block>()
        var pos = 4L
        while (true) {
            val h = readAt(src, pos, 4)
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val l = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            blocks += Block(pos, last, type, l)
            pos += 4 + l
            if (last || pos >= len) break
        }
        val audioStart = pos

        var vendor = "Lyricota"
        val comments = mutableListOf<ByteArray>()
        blocks.firstOrNull { it.type == 4 }?.let { vb ->
            val data = readAt(src, vb.offset + 4, vb.length)
            var p = 0
            val vendorLen = leInt(data, p); p += 4
            vendor = String(data, p, vendorLen, Charsets.UTF_8); p += vendorLen
            val count = leInt(data, p); p += 4
            repeat(count) {
                val cl = leInt(data, p); p += 4
                val c = data.copyOfRange(p, p + cl); p += cl
                val key = String(c, 0, minOf(c.size, 32), Charsets.UTF_8).substringBefore('=').uppercase()
                val drop = (changes.tags != null && key in VORBIS_MANAGED) ||
                    (changes.lyrics != null && key in VORBIS_LYRICS)
                if (!drop) comments += c
            }
        }
        changes.tags?.let { t -> vorbisEntries(t.normalized()).forEach { comments += it.toByteArray(Charsets.UTF_8) } }
        changes.lyrics?.takeIf { it.isNotEmpty() }?.let { comments += "LYRICS=$it".toByteArray(Charsets.UTF_8) }

        val vc = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        vc.write(leBytes(vendorBytes.size)); vc.write(vendorBytes)
        vc.write(leBytes(comments.size))
        comments.forEach { vc.write(leBytes(it.size)); vc.write(it) }
        val vcData = vc.toByteArray()

        // Todos los bloques salvo VORBIS_COMMENT, PADDING (y PICTURE si cambia la portada);
        // luego el comentario nuevo, la portada nueva y un relleno fresco marcado como último.
        val plan = mutableListOf<Piece>(Piece.Bytes("fLaC".toByteArray(Charsets.US_ASCII)))
        for (b in blocks) {
            if (b.type == 4 || b.type == 1 || (b.type == 6 && changes.cover != null)) continue
            val h = readAt(src, b.offset, 4)
            h[0] = (h[0].toInt() and 0x7F).toByte()
            plan += Piece.Bytes(h)
            plan += Piece.Range(b.offset + 4, b.offset + 4 + b.length)
        }
        plan += Piece.Bytes(byteArrayOf(4) + be24(vcData.size) + vcData)
        changes.cover?.let { c ->
            val pic = pictureBlock(c)
            plan += Piece.Bytes(byteArrayOf(6) + be24(pic.size) + pic)
        }
        val padding = 4096
        plan += Piece.Bytes(byteArrayOf((0x80 or 1).toByte()) + be24(padding) + ByteArray(padding))
        plan += Piece.Range(audioStart, len)
        return plan
    }

    /** Bloque PICTURE (también sirve para METADATA_BLOCK_PICTURE). */
    fun pictureBlock(c: Cover): ByteArray {
        val (w, h) = ImageSize.of(c.data) ?: (0 to 0)
        val out = ByteArrayOutputStream()
        val mime = c.mime.toByteArray(Charsets.US_ASCII)
        out.write(beBytes(3)) // portada frontal
        out.write(beBytes(mime.size)); out.write(mime)
        out.write(beBytes(0)) // sin descripción
        out.write(beBytes(w)); out.write(beBytes(h)); out.write(beBytes(24)); out.write(beBytes(0))
        out.write(beBytes(c.data.size)); out.write(c.data)
        return out.toByteArray()
    }

    // ================================================================== MP4 / M4A

    private class Box(val type: String, val start: Int, val header: Int, val size: Int) {
        val end get() = start + size
        val contentStart get() = start + header
    }

    private val MP4_MANAGED = setOf("©nam", "©alb", "©ART", "aART", "©wrt", "©gen", "gnre", "©day", "trkn", "disk")

    private fun planMp4(src: RandomAccessFile, changes: TagChanges): List<Piece> {
        val len = src.length()
        var pos = 0L
        var moovStart = -1L
        var moovSize = 0L
        var mdatStart = -1L
        while (pos + 8 <= len) {
            val h = readAt(src, pos, 8)
            var size = beInt(h, 0).toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                val big = readAt(src, pos + 8, 8)
                size = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (big[i].toLong() and 0xFF) }
            } else if (size == 0L) {
                size = len - pos
            }
            if (size < 8) break
            if (type == "moov") { moovStart = pos; moovSize = size }
            if (type == "mdat" && mdatStart < 0) mdatStart = pos
            pos += size
        }
        if (moovStart < 0) error("'moov' box not found")
        if (moovSize > 64L * 1024 * 1024) error("'moov' box too large")

        val moov = readAt(src, moovStart, moovSize.toInt())
        val newMoov = rebuildMoov(moov) { items ->
            val kept = items.filter { (type, _) ->
                !(changes.tags != null && type in MP4_MANAGED) &&
                    !(changes.cover != null && type == "covr") &&
                    !(changes.lyrics != null && type == "©lyr")
            }.map { it.second }.toMutableList()
            changes.tags?.let { kept += mp4Items(it.normalized()) }
            changes.cover?.let { kept += item("covr", if (it.mime == "image/png") 14 else 13, it.data) }
            changes.lyrics?.takeIf { it.isNotEmpty() }?.let { kept += textItem("©lyr", it) }
            kept
        }
        val delta = newMoov.size - moov.size
        // Si el audio va DESPUÉS de moov, se desplaza: hay que corregir los offsets de los chunks.
        if (delta != 0 && mdatStart > moovStart) shiftChunkOffsets(newMoov, moovStart + moov.size, delta.toLong())

        return listOf(Piece.Range(0, moovStart), Piece.Bytes(newMoov), Piece.Range(moovStart + moovSize, len))
    }

    private fun item(type: String, dataType: Int, value: ByteArray): ByteArray {
        val data = box("data", beBytes(dataType) + ByteArray(4) + value)
        return beBytes(8 + data.size) + type.toByteArray(Charsets.ISO_8859_1) + data
    }

    private fun textItem(type: String, value: String) = item(type, 1, value.toByteArray(Charsets.UTF_8))

    private fun pairItem(type: String, n: Int, total: Int?, trailing: Boolean): ByteArray {
        val v = byteArrayOf(0, 0, (n ushr 8).toByte(), n.toByte(), ((total ?: 0) ushr 8).toByte(), (total ?: 0).toByte())
        return item(type, 0, if (trailing) v + byteArrayOf(0, 0) else v)
    }

    private fun mp4Items(t: TrackTags): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        t.title?.let { out += textItem("©nam", it) }
        t.album?.let { out += textItem("©alb", it) }
        t.artist?.let { out += textItem("©ART", it) }
        t.albumArtist?.let { out += textItem("aART", it) }
        if (t.composers.isNotEmpty()) out += textItem("©wrt", t.composers.joinToString("; "))
        if (t.genres.isNotEmpty()) out += textItem("©gen", t.genres.joinToString("; "))
        t.year?.let { out += textItem("©day", it) }
        t.trackNumber?.let { out += pairItem("trkn", it, t.trackTotal, trailing = true) }
        t.discNumber?.let { out += pairItem("disk", it, t.discTotal, trailing = false) }
        return out
    }

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val list = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = beInt(b, p).toLong() and 0xFFFFFFFFL
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (b[p + 8 + i].toLong() and 0xFF) }
                header = 16
            } else if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) break
            list += Box(type, p, header, size.toInt())
            p += size.toInt()
        }
        return list
    }

    private fun box(type: String, content: ByteArray): ByteArray = beBytes(8 + content.size) + type.toByteArray(Charsets.ISO_8859_1) + content

    private fun metaWithIlst(ilstContent: ByteArray): ByteArray {
        val hdlr = box("hdlr", ByteArray(8) + "mdir".toByteArray(Charsets.ISO_8859_1) + "appl".toByteArray(Charsets.ISO_8859_1) + ByteArray(9))
        return box("meta", ByteArray(4) + hdlr + box("ilst", ilstContent))
    }

    /**
     * Reconstruye moov → udta → meta → ilst aplicando [transform] a los ítems de ilst
     * (lista de (tipo, bytes del ítem)). Tamaños recalculados.
     */
    private fun rebuildMoov(moov: ByteArray, transform: (List<Pair<String, ByteArray>>) -> List<ByteArray>): ByteArray {
        val moovHeader = boxes(moov, 0, moov.size).firstOrNull()?.header ?: 8
        val children = boxes(moov, moovHeader, moov.size)
        val udta = children.firstOrNull { it.type == "udta" }

        fun newIlstContent(existing: List<Box>): ByteArray =
            concat(transform(existing.map { it.type to moov.copyOfRange(it.start, it.end) }))

        val newUdtaContent: ByteArray = if (udta == null) {
            metaWithIlst(newIlstContent(emptyList()))
        } else {
            val udtaChildren = boxes(moov, udta.contentStart, udta.end)
            val meta = udtaChildren.firstOrNull { it.type == "meta" }
            val newMeta = if (meta == null) {
                metaWithIlst(newIlstContent(emptyList()))
            } else {
                val metaChildren = boxes(moov, meta.contentStart + 4, meta.end)
                val ilst = metaChildren.firstOrNull { it.type == "ilst" }
                val ilstContent = newIlstContent(if (ilst == null) emptyList() else boxes(moov, ilst.contentStart, ilst.end))
                val rebuiltChildren = if (ilst == null) {
                    concat(metaChildren.map { moov.copyOfRange(it.start, it.end) }) + box("ilst", ilstContent)
                } else {
                    concat(metaChildren.map { if (it === ilst) box("ilst", ilstContent) else moov.copyOfRange(it.start, it.end) })
                }
                box("meta", moov.copyOfRange(meta.contentStart, meta.contentStart + 4) + rebuiltChildren)
            }
            concat(udtaChildren.map { if (it === meta) newMeta else moov.copyOfRange(it.start, it.end) }) +
                (if (meta == null) newMeta else ByteArray(0))
        }

        val parts = children.map { if (it === udta) box("udta", newUdtaContent) else moov.copyOfRange(it.start, it.end) }
        val body = concat(parts) + (if (udta == null) box("udta", newUdtaContent) else ByteArray(0))
        return box("moov", body)
    }

    /** Suma [delta] a cada offset de stco/co64 que apunte más allá de [threshold]. */
    private fun shiftChunkOffsets(moov: ByteArray, threshold: Long, delta: Long) {
        fun walk(from: Int, to: Int) {
            for (b in boxes(moov, from, to)) when (b.type) {
                "trak", "mdia", "minf", "stbl", "edts" -> walk(b.contentStart, b.end)
                "stco" -> {
                    val count = beInt(moov, b.contentStart + 4)
                    for (i in 0 until count) {
                        val at = b.contentStart + 8 + i * 4
                        val v = beInt(moov, at).toLong() and 0xFFFFFFFFL
                        if (v >= threshold) writeBe(moov, at, (v + delta).toInt())
                    }
                }
                "co64" -> {
                    val count = beInt(moov, b.contentStart + 4)
                    for (i in 0 until count) {
                        val at = b.contentStart + 8 + i * 8
                        val v = (beInt(moov, at).toLong() shl 32) or (beInt(moov, at + 4).toLong() and 0xFFFFFFFFL)
                        if (v >= threshold) {
                            val nv = v + delta
                            writeBe(moov, at, (nv ushr 32).toInt())
                            writeBe(moov, at + 4, nv.toInt())
                        }
                    }
                }
            }
        }
        val header = boxes(moov, 0, moov.size).firstOrNull()?.header ?: 8
        walk(header, moov.size)
    }

    // ================================================================== OGG (Vorbis / Opus)

    private class OggPage(
        val pos: Long, val headerType: Int, val granule: Long, val serial: Int, val seq: Int,
        val segs: IntArray, val dataPos: Long,
    ) {
        val dataLen: Int get() = segs.sum()
        val end: Long get() = dataPos + dataLen
    }

    private fun oggPages(src: RandomAccessFile): List<OggPage> {
        val len = src.length()
        val pages = mutableListOf<OggPage>()
        var pos = 0L
        while (pos + 27 <= len) {
            val h = readAt(src, pos, 27)
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") error("Corrupt Ogg page at $pos")
            val n = h[26].toInt() and 0xFF
            val table = readAt(src, pos + 27, n)
            var granule = 0L
            for (i in 7 downTo 0) granule = (granule shl 8) or (h[6 + i].toLong() and 0xFF)
            val page = OggPage(pos, h[5].toInt() and 0xFF, granule, leInt(h, 14), leInt(h, 18), IntArray(n) { table[it].toInt() and 0xFF }, pos + 27 + n)
            pages += page
            pos = page.end
        }
        return pages
    }

    /**
     * Reescribe el paquete de comentarios (Vorbis: junto con el de configuración). El primer
     * paquete (identificación) y el audio no se tocan; si cambia el número de páginas de
     * cabecera, se renumeran las siguientes y se recalcula su CRC.
     */
    private fun planOgg(src: RandomAccessFile, changes: TagChanges): List<Piece> {
        val pages = oggPages(src)
        if (pages.isEmpty()) error("Not an Ogg file")
        val serial = pages[0].serial
        if (pages.any { it.serial != serial }) throw UnsupportedFormat("ogg (multiple streams)")

        // Reconstruir los paquetes de cabecera y ver en qué página termina el último.
        val packets = mutableListOf<ByteArray>()
        val current = ByteArrayOutputStream()
        var needed = Int.MAX_VALUE
        var lastHeaderPage = -1
        loop@ for ((pi, page) in pages.withIndex()) {
            var dp = page.dataPos
            for ((si, seg) in page.segs.withIndex()) {
                if (seg > 0) current.write(readAt(src, dp, seg))
                dp += seg
                if (seg < 255) {
                    packets += current.toByteArray()
                    current.reset()
                    if (packets.size == 1) {
                        val p0 = packets[0]
                        needed = when {
                            p0.size > 7 && String(p0, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 3
                            p0.size > 8 && String(p0, 0, 8, Charsets.ISO_8859_1) == "OpusHead" -> 2
                            else -> throw UnsupportedFormat("ogg (codec)")
                        }
                        // El paquete de identificación debe ir solo en la primera página.
                        if (pi != 0 || si != page.segs.lastIndex) throw UnsupportedFormat("ogg (layout)")
                    }
                    if (packets.size == needed) {
                        if (si != page.segs.lastIndex) throw UnsupportedFormat("ogg (layout)")
                        lastHeaderPage = pi
                        break@loop
                    }
                }
            }
        }
        if (lastHeaderPage < 1) error("Ogg headers not found")
        val vorbis = needed == 3

        val newComment = buildOggComment(packets[1], vorbis, changes)
        val headerPackets = if (vorbis) listOf(newComment, packets[2]) else listOf(newComment)
        val newPages = buildPages(headerPackets, serial, firstSeq = pages[0].seq + 1)
        val delta = newPages.size - lastHeaderPage

        val plan = mutableListOf<Piece>(Piece.Range(pages[0].pos, pages[0].end))
        newPages.forEach { plan += Piece.Bytes(it) }
        val rest = pages.subList(lastHeaderPage + 1, pages.size)
        if (delta == 0) {
            if (rest.isNotEmpty()) plan += Piece.Range(rest.first().pos, src.length())
        } else {
            for (p in rest) {
                val header = pageHeader(p.headerType, p.granule, serial, p.seq + delta, p.segs)
                val data = readAt(src, p.dataPos, p.dataLen)
                writeLe(header, 22, crc(header, data))
                plan += Piece.Bytes(header)
                plan += Piece.Range(p.dataPos, p.end)
            }
            // Bytes sobrantes tras la última página (raro): se conservan.
            if (pages.last().end < src.length()) plan += Piece.Range(pages.last().end, src.length())
        }
        return plan
    }

    private fun buildOggComment(old: ByteArray, vorbis: Boolean, changes: TagChanges): ByteArray {
        val offset = if (vorbis) 7 else 8
        var p = offset
        val vendorLen = leInt(old, p); p += 4
        val vendor = old.copyOfRange(p, p + vendorLen); p += vendorLen
        val count = leInt(old, p); p += 4
        val comments = mutableListOf<ByteArray>()
        repeat(count) {
            val cl = leInt(old, p); p += 4
            val c = old.copyOfRange(p, p + cl); p += cl
            val key = String(c, 0, minOf(c.size, 32), Charsets.UTF_8).substringBefore('=').uppercase()
            val drop = (changes.tags != null && key in VORBIS_MANAGED) ||
                (changes.lyrics != null && key in VORBIS_LYRICS) ||
                (changes.cover != null && (key == "METADATA_BLOCK_PICTURE" || key == "COVERART" || key == "COVERARTMIME"))
            if (!drop) comments += c
        }
        changes.tags?.let { t -> vorbisEntries(t.normalized()).forEach { comments += it.toByteArray(Charsets.UTF_8) } }
        changes.lyrics?.takeIf { it.isNotEmpty() }?.let { comments += "LYRICS=$it".toByteArray(Charsets.UTF_8) }
        changes.cover?.let {
            val b64 = java.util.Base64.getEncoder().encodeToString(pictureBlock(it))
            comments += "METADATA_BLOCK_PICTURE=$b64".toByteArray(Charsets.UTF_8)
        }
        val out = ByteArrayOutputStream()
        out.write(if (vorbis) byteArrayOf(3) + "vorbis".toByteArray(Charsets.ISO_8859_1) else "OpusTags".toByteArray(Charsets.ISO_8859_1))
        out.write(leBytes(vendor.size)); out.write(vendor)
        out.write(leBytes(comments.size))
        comments.forEach { out.write(leBytes(it.size)); out.write(it) }
        if (vorbis) out.write(1) // bit de "framing"
        return out.toByteArray()
    }

    private class Seg(val len: Int, val packet: Int, val offset: Int, val ends: Boolean)

    /** Reparte paquetes en páginas (máx. 255 segmentos). La última página cierra el último paquete. */
    private fun buildPages(packets: List<ByteArray>, serial: Int, firstSeq: Int): List<ByteArray> {
        val segs = mutableListOf<Seg>()
        packets.forEachIndexed { pi, pk ->
            var off = 0
            while (pk.size - off >= 255) {
                segs += Seg(255, pi, off, false)
                off += 255
            }
            segs += Seg(pk.size - off, pi, off, true)
        }
        val pages = mutableListOf<ByteArray>()
        var i = 0
        var continued = false
        var seq = firstSeq
        while (i < segs.size) {
            val chunk = segs.subList(i, minOf(i + 255, segs.size))
            val data = ByteArrayOutputStream()
            chunk.forEach { data.write(packets[it.packet], it.offset, it.len) }
            val anyEnds = chunk.any { it.ends }
            val header = pageHeader(if (continued) 1 else 0, if (anyEnds) 0L else -1L, serial, seq++, IntArray(chunk.size) { chunk[it].len })
            val body = data.toByteArray()
            writeLe(header, 22, crc(header, body))
            pages += header + body
            continued = !chunk.last().ends
            i += chunk.size
        }
        return pages
    }

    private fun pageHeader(type: Int, granule: Long, serial: Int, seq: Int, segs: IntArray): ByteArray {
        val h = ByteArray(27 + segs.size)
        "OggS".toByteArray(Charsets.ISO_8859_1).copyInto(h, 0)
        h[4] = 0
        h[5] = type.toByte()
        for (k in 0 until 8) h[6 + k] = (granule ushr (8 * k)).toByte()
        writeLe(h, 14, serial)
        writeLe(h, 18, seq)
        writeLe(h, 22, 0)
        h[26] = segs.size.toByte()
        segs.forEachIndexed { k, v -> h[27 + k] = v.toByte() }
        return h
    }

    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** CRC de Ogg (polinomio 0x04C11DB7, sin reflejar) sobre cabecera (con CRC a 0) + datos. */
    private fun crc(header: ByteArray, data: ByteArray): Int {
        var c = 0
        for (b in header) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        for (b in data) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun writeLe(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    // ================================================================== bytes

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun synchsafe(v: Int) = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte(),
    )

    private fun synchsafeToInt(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun beInt(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun beBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun writeBe(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    private fun be24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun leInt(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leBytes(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
}

/** Ancho/alto de una imagen JPEG o PNG leyendo sólo su cabecera. */
object ImageSize {
    fun of(d: ByteArray): Pair<Int, Int>? {
        if (d.size > 24 && d[0] == 0x89.toByte() && d[1] == 'P'.code.toByte()) {
            val w = TagReader.u32(d, 16).toInt()
            val h = TagReader.u32(d, 20).toInt()
            return w to h
        }
        if (d.size > 4 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte()) {
            var p = 2
            while (p + 9 < d.size) {
                if (d[p] != 0xFF.toByte()) { p++; continue }
                val marker = d[p + 1].toInt() and 0xFF
                val len = ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((d[p + 5].toInt() and 0xFF) shl 8) or (d[p + 6].toInt() and 0xFF)
                    val w = ((d[p + 7].toInt() and 0xFF) shl 8) or (d[p + 8].toInt() and 0xFF)
                    return w to h
                }
                p += 2 + len
            }
        }
        return null
    }
}
