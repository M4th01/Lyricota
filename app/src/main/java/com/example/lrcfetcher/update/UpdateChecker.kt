package com.example.lrcfetcher.update

import com.example.lrcfetcher.AppInfo
import com.example.lrcfetcher.lyrics.Net
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Última versión publicada en GitHub Releases. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
)

/**
 * Consulta la última Release del repositorio (API pública de GitHub, sin cuenta) y descarga
 * su APK. La instalación la confirma siempre el usuario en el instalador de Android.
 */
object UpdateChecker {
    private val headers = mapOf("User-Agent" to AppInfo.USER_AGENT, "Accept" to "application/vnd.github+json")

    /** Bloqueante. null si no hay Releases o no hay conexión. */
    fun fetchLatest(): UpdateInfo? = parse(Net.get("https://api.github.com/repos/${AppInfo.REPO}/releases/latest", headers))

    fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets")
        var apkUrl: String? = null
        var size = 0L
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                size = a.optLong("size")
                break
            }
        }
        return UpdateInfo(
            version = tag.trimStart('v', 'V'),
            notes = o.optString("body").trim(),
            pageUrl = o.optString("html_url"),
            apkUrl = apkUrl,
            apkSize = size,
        )
    }

    /** "2.10" > "2.9"; se ignoran sufijos no numéricos ("2.5-beta" = 2.5). */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-', '_', ' ').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Descarga el APK a [dest] informando el progreso (0..1). Bloqueante. */
    fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", AppInfo.USER_AGENT)
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
            if (total > 0 && read != total) throw IOException("incomplete download")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("rename failed")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }
}
