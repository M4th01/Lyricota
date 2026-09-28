package com.example.lrcfetcher.library

import android.content.Context
import com.example.lrcfetcher.tags.TrackTags
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Qué hizo el último lote (para poder deshacerlo). */
enum class BackupKind { LYRICS, METADATA, CONVERT, RENAME }

/**
 * Estado anterior de una canción. Sólo se rellena lo que el lote tocó:
 * - [tags]: etiquetas antes de un lote de metadatos.
 * - [lyrics]: letra incrustada antes ("" = no tenía); null = no se tocó.
 * - [sidecarText]: contenido anterior del .lrc; [sidecarCreated] = el lote lo creó ([sidecarDocId]).
 * - [oldName]/[newUri]: nombre anterior y documento renombrado.
 */
data class BackupEntry(
    val uri: String,
    val tags: TrackTags? = null,
    val lyrics: String? = null,
    val sidecarDocId: String? = null,
    val sidecarText: String? = null,
    val sidecarCreated: Boolean = false,
    val oldName: String? = null,
    val newUri: String? = null,
    val oldLrcName: String? = null,
    val newLrcUri: String? = null,
)

data class BatchBackupData(val kind: BackupKind, val time: Long, val entries: List<BackupEntry>)

/**
 * Copia de seguridad del último lote en el almacenamiento privado de la app (nunca sale del
 * dispositivo). Cada lote nuevo reemplaza la copia anterior.
 */
class BatchBackup(context: Context) {
    private val file = File(context.filesDir, "last_batch.json")
    private var pending: MutableList<BackupEntry>? = null
    private var pendingKind: BackupKind? = null

    fun begin(kind: BackupKind) {
        pending = mutableListOf()
        pendingKind = kind
    }

    @Synchronized
    fun add(entry: BackupEntry) {
        val list = pending ?: return
        list.add(entry)
        // Se guarda sobre la marcha: si la app se cierra a mitad del lote, lo hecho se puede deshacer.
        if (list.size <= 5 || list.size % 10 == 0) save()
    }

    fun finish() {
        save()
        pending = null
        pendingKind = null
    }

    @Synchronized
    private fun save() {
        val entries = pending ?: return
        val kind = pendingKind ?: return
        if (entries.isEmpty()) return
        val o = JSONObject().put("kind", kind.name).put("time", System.currentTimeMillis())
            .put("entries", JSONArray().apply { entries.forEach { put(toJson(it)) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file).also { if (!it) { file.delete(); tmp.renameTo(file) } }
    }

    fun load(): BatchBackupData? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val arr = o.getJSONArray("entries")
        BatchBackupData(
            BackupKind.valueOf(o.getString("kind")),
            o.optLong("time"),
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) },
        )
    }.getOrNull()

    fun clear() {
        file.delete()
    }

    private fun toJson(e: BackupEntry) = JSONObject().apply {
        put("uri", e.uri)
        e.tags?.let { put("tags", Track.tagsToJson(it)) }
        e.lyrics?.let { put("lyrics", it) }
        e.sidecarDocId?.let { put("sidecarDocId", it) }
        e.sidecarText?.let { put("sidecarText", it) }
        if (e.sidecarCreated) put("sidecarCreated", true)
        e.oldName?.let { put("oldName", it) }
        e.newUri?.let { put("newUri", it) }
        e.oldLrcName?.let { put("oldLrcName", it) }
        e.newLrcUri?.let { put("newLrcUri", it) }
    }

    private fun fromJson(o: JSONObject) = BackupEntry(
        uri = o.getString("uri"),
        tags = o.optJSONObject("tags")?.let { Track.tagsFromJson(it) },
        lyrics = if (o.has("lyrics")) o.getString("lyrics") else null,
        sidecarDocId = o.optString("sidecarDocId").ifBlank { null },
        sidecarText = if (o.has("sidecarText")) o.getString("sidecarText") else null,
        sidecarCreated = o.optBoolean("sidecarCreated"),
        oldName = o.optString("oldName").ifBlank { null },
        newUri = o.optString("newUri").ifBlank { null },
        oldLrcName = o.optString("oldLrcName").ifBlank { null },
        newLrcUri = o.optString("newLrcUri").ifBlank { null },
    )
}
