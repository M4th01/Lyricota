package com.example.lrcfetcher.lyrics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** Pequeño cliente HTTP compartido por todos los proveedores (bloqueante: llamar desde IO). */
internal object Net {
    private const val TIMEOUT_MS = 12_000
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request("GET", url, headers, null)

    fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): String =
        request("POST", url, headers + ("Content-Type" to contentType), body.toByteArray(Charsets.UTF_8))

    private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", BROWSER_UA)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?: throw HttpException(code, "")
            val stream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw HttpException(code, text.take(200))
            return text
        } finally {
            conn.disconnect()
        }
    }
}

class HttpException(val code: Int, message: String) : IOException("HTTP $code $message")
