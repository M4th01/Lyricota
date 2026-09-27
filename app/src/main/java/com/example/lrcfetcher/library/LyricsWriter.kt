package com.example.lrcfetcher.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.lrcfetcher.tags.ByteSource
import com.example.lrcfetcher.tags.Cover
import com.example.lrcfetcher.tags.TagChanges
import com.example.lrcfetcher.tags.TagInfo
import com.example.lrcfetcher.tags.TagReader
import com.example.lrcfetcher.tags.TagWriter
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Lectura/escritura de etiquetas sobre archivos SAF, y archivos .lrc al lado de la canción.
 *
 * Para escribir: se copia el original a la caché, se calcula el archivo nuevo (TagWriter) y
 * sólo entonces se abre el destino en modo "wt": si algo falla antes, el original queda intacto.
 */
class LyricsWriter(private val context: Context) {

    companion object {
        val EMBEDDABLE = TagWriter.WRITABLE
    }

    // ------------------------------------------------------------------ lectura

    fun readTags(uri: Uri, ext: String): TagInfo {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return TagInfo()
        return pfd.use { FileInputStream(it.fileDescriptor).channel.use { ch -> TagReader.read(ByteSource.of(ch), ext) } }
    }

    fun readCover(uri: Uri, ext: String): Cover? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            FileInputStream(it.fileDescriptor).channel.use { ch -> TagReader.readCover(ByteSource.of(ch), ext) }
        }
    }.getOrNull()

    // ------------------------------------------------------------------ .lrc al lado

    /** Crea o reemplaza "<nombre>.lrc" en la misma carpeta. Devuelve el documentId del .lrc. */
    fun writeSidecar(treeUri: Uri, track: Track, lrc: String): String {
        val resolver = context.contentResolver
        val existing = track.lrcDocId
        val targetUri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing)
        } else {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, track.parentDocId)
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "${track.baseName}.lrc")
                ?: error("Could not create the .lrc file")
        }
        resolver.openOutputStream(targetUri, "wt")?.use { it.write(lrc.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not write the .lrc file")
        return DocumentsContract.getDocumentId(targetUri)
    }

    // ------------------------------------------------------------------ escritura

    fun embed(track: Track, lyrics: String) = apply(track, TagChanges(lyrics = lyrics))

    /** Escribe metadatos / portada / letra en el archivo de la canción. */
    fun apply(track: Track, changes: TagChanges) {
        if (track.ext !in EMBEDDABLE) throw TagWriter.UnsupportedFormat(track.ext)
        val uri = track.androidUri
        val resolver = context.contentResolver
        val tmp = File.createTempFile("tag", ".bin", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: error("Could not read the file")
            RandomAccessFile(tmp, "r").use { src ->
                val plan = TagWriter.plan(src, track.ext, changes)
                resolver.openOutputStream(uri, "wt")?.use { out -> TagWriter.writePlan(src, plan, out) }
                    ?: error("Could not write the file")
            }
        } finally {
            tmp.delete()
        }
    }

    /** Núcleo sin Android (pruebas): escribe en [out] el archivo [src] con la letra incrustada. */
    internal fun embedFile(src: File, ext: String, lyrics: String, out: OutputStream) =
        applyFile(src, ext, TagChanges(lyrics = lyrics), out)

    internal fun applyFile(src: File, ext: String, changes: TagChanges, out: OutputStream) {
        RandomAccessFile(src, "r").use { raf -> TagWriter.write(raf, ext, changes, out) }
    }
}
