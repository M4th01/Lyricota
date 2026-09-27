package com.example.lrcfetcher.library

import android.content.Context
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caché de la biblioteca en un archivo JSON (antes iba en SharedPreferences, que no está
 * pensado para cadenas grandes y se cargaba entero en memoria al abrir la app).
 */
class LibraryCache(private val context: Context) {
    private companion object {
        const val VERSION = 4
    }

    private fun file(treeUri: Uri) = File(context.filesDir, "library_${treeUri.toString().hashCode().toUInt()}.json")

    fun load(treeUri: Uri): Map<String, Track> = runCatching {
        val f = file(treeUri)
        if (!f.exists()) return emptyMap()
        val root = JSONObject(f.readText())
        if (root.optInt("version") != VERSION) return emptyMap()
        val arr = root.getJSONArray("tracks")
        buildMap(arr.length()) {
            for (i in 0 until arr.length()) {
                val t = Track.fromJson(arr.getJSONObject(i))
                put(t.uri, t)
            }
        }
    }.getOrDefault(emptyMap())

    fun save(treeUri: Uri, tracks: Collection<Track>) {
        runCatching {
            val arr = JSONArray()
            tracks.forEach { arr.put(it.toJson()) }
            val json = JSONObject().put("version", VERSION).put("tree", treeUri.toString()).put("tracks", arr)
            val target = file(treeUri)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    fun clear(treeUri: Uri) {
        file(treeUri).delete()
    }
}
