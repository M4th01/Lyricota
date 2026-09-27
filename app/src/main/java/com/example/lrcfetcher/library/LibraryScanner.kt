package com.example.lrcfetcher.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.example.lrcfetcher.tags.ByteSource
import com.example.lrcfetcher.tags.TagInfo
import com.example.lrcfetcher.tags.TagReader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Escaneo rápido de una carpeta SAF.
 *
 * Antes se usaba DocumentFile.listFiles(), que hace varias consultas al ContentResolver POR
 * ARCHIVO, y se recorría el árbol dos veces. Aquí se hace UNA consulta por carpeta (con todas
 * las columnas necesarias), las carpetas se recorren en paralelo, y los metadatos (etiquetas)
 * sólo se leen para archivos nuevos o modificados.
 */
class LibraryScanner(private val context: Context) {

    data class Entry(
        val docId: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val parentDocId: String,
    )

    data class Listing(val audio: List<Entry>, val lrcByKey: Map<String, String>)

    companion object {
        val AUDIO_EXT = setOf("mp3", "m4a", "mp4", "flac", "wav", "ogg", "oga", "aac", "opus", "wma", "alac", "ape", "wv", "aiff", "aif")
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun lrcKey(parentDocId: String, baseName: String) = parentDocId + "\u0000" + baseName.lowercase()
    }

    /** Lista todo el árbol: una consulta por carpeta, carpetas en paralelo. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun list(treeUri: Uri): Listing = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val audio = ConcurrentLinkedQueue<Entry>()
        val lrc = java.util.concurrent.ConcurrentHashMap<String, String>()
        val io = Dispatchers.IO.limitedParallelism(8)

        suspend fun visit(dirId: String) {
            ensureActive()
            val subdirs = mutableListOf<String>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) subdirs += id
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    when {
                        ext in AUDIO_EXT -> audio += Entry(id, name, c.getLong(3), c.getLong(4), dirId)
                        ext == "lrc" -> lrc[lrcKey(dirId, name.substringBeforeLast('.'))] = id
                    }
                }
            }
            coroutineScope { subdirs.forEach { launch(io) { visit(it) } } }
        }

        visit(rootId)
        Listing(audio.toList(), lrc)
    }

    fun documentUri(treeUri: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** (tamaño, fecha) actuales de un documento, para que la caché no lo crea modificado. */
    fun stat(treeUri: Uri, docId: String): Pair<Long, Long>? {
        val uri = documentUri(treeUri, docId)
        return context.contentResolver.query(uri, arrayOf(PROJECTION[3], PROJECTION[4]), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
        }
    }

    /**
     * Combina el listado con la caché: lo que no cambió (mismo tamaño y fecha) se reutiliza tal
     * cual; lo nuevo o modificado queda "pendiente" con título provisional sacado del nombre.
     */
    fun diff(treeUri: Uri, listing: Listing, cached: Map<String, Track>): Pair<List<Track>, List<Track>> {
        val ready = ArrayList<Track>(listing.audio.size)
        val pending = ArrayList<Track>()
        for (e in listing.audio) {
            val uri = documentUri(treeUri, e.docId).toString()
            val lrcId = listing.lrcByKey[lrcKey(e.parentDocId, e.name.substringBeforeLast('.'))]
            val old = cached[uri]
            if (old != null && old.size == e.size && old.lastModified == e.lastModified && old.metadataLoaded) {
                ready += old.copy(lrcDocId = lrcId, parentDocId = e.parentDocId, fileName = e.name)
            } else {
                val (title, artist) = Track.guessFromFileName(e.name)
                pending += Track(
                    uri = uri, docId = e.docId, parentDocId = e.parentDocId, fileName = e.name,
                    ext = e.name.substringAfterLast('.', "").lowercase(), size = e.size, lastModified = e.lastModified,
                    title = title, artist = artist, album = null, durationMs = null, lrcDocId = lrcId,
                )
            }
        }
        return ready to pending
    }

    /**
     * Lee las etiquetas del propio archivo (título, artistas, álbum…) con [TagReader]. El
     * MediaMetadataRetriever sólo aporta la duración, o los datos de formatos que no leemos.
     * El nombre de archivo es el último recurso.
     */
    fun readMetadata(track: Track): Track {
        val uri = track.androidUri
        val info = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).channel.use { TagReader.read(ByteSource.of(it), track.ext) }
            }
        }.getOrNull() ?: TagInfo()

        var mmrTitle: String? = null
        var mmrArtist: String? = null
        var mmrAlbum: String? = null
        var duration: Long? = null
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            if (info.tags.title == null) {
                mmrTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null }
                mmrArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null }
                mmrAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifBlank { null }
            }
        } catch (_: Exception) {
            // Sin duración: no pasa nada.
        } finally {
            runCatching { mmr.release() }
        }

        val tags = info.tags.let { t ->
            if (t.title == null && mmrTitle != null) t.copy(title = mmrTitle, artists = t.artists.ifEmpty { listOfNotNull(mmrArtist) }, album = t.album ?: mmrAlbum)
            else t
        }
        val (guessTitle, guessArtist) = Track.guessFromFileName(track.fileName)
        return track.copy(
            title = tags.title ?: guessTitle,
            artist = tags.artist ?: guessArtist.takeIf { tags.title == null },
            album = tags.album,
            durationMs = duration,
            tags = tags,
            hasCover = info.hasCover,
            hasEmbeddedLyrics = info.hasLyrics,
            metadataLoaded = true,
            identity = when {
                tags.title != null && tags.artists.isNotEmpty() -> Identity.TAGS
                IdentityResolver.splits(track.fileName).isNotEmpty() -> Identity.GUESSED
                else -> Identity.CHECKED
            },
            swapSuspected = false,
        )
    }

    /** Lee metadatos de [pending] en paralelo; [onBatch] recibe resultados parciales. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun readAll(pending: List<Track>, onProgress: (done: Int, batch: List<Track>) -> Unit): List<Track> =
        withContext(Dispatchers.IO) {
            val io = Dispatchers.IO.limitedParallelism(4)
            val done = AtomicInteger()
            pending.chunked(24).flatMap { chunk ->
                ensureActive()
                val results = coroutineScope { chunk.map { async(io) { readMetadata(it) } }.awaitAll() }
                onProgress(done.addAndGet(results.size), results)
                results
            }
        }
}
