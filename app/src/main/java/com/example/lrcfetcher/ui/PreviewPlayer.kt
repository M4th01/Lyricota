package com.example.lrcfetcher.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Reproduce la canción local para comprobar la sincronización de la letra (y ajustar el
 * desfase de oído). Se prepara la primera vez que se pulsa "reproducir", en segundo plano
 * para no congelar la interfaz con archivos grandes.
 */
class PreviewPlayer(private val context: Context, private val uri: Uri) {
    private var player: MediaPlayer? = null
    private var prepared = false

    /** Lo pedido mientras se preparaba: se aplica al estar listo. */
    private var pendingPlay = false
    private var pendingSeek: Long? = null

    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Preparando el archivo (aún no suena). */
    var preparing by mutableStateOf(false)
        private set

    val started: Boolean get() = player != null

    fun toggle() {
        val fresh = player == null
        val p = ensure() ?: return
        if (!prepared) {
            // Aún preparando: se recuerda si debe sonar al estar listo.
            pendingPlay = fresh || !pendingPlay
            playing = pendingPlay
            return
        }
        if (p.isPlaying) {
            p.pause()
            playing = false
        } else {
            p.start()
            playing = true
        }
    }

    /** Salta a [ms] y reproduce. */
    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        val p = ensure() ?: return
        positionMs = target
        if (!prepared) {
            pendingSeek = target
            pendingPlay = true
            playing = true
            return
        }
        p.seekTo(target.toInt())
        if (!p.isPlaying) {
            p.start()
            playing = true
        }
    }

    /** Llamar periódicamente mientras suena. */
    fun tick() {
        player?.let { if (prepared && it.isPlaying) positionMs = it.currentPosition.toLong() }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
        preparing = false
        playing = false
    }

    private fun ensure(): MediaPlayer? {
        player?.let { return it }
        return runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setOnPreparedListener { mp ->
                    prepared = true
                    preparing = false
                    durationMs = mp.duration.toLong().coerceAtLeast(0)
                    pendingSeek?.let { mp.seekTo(it.toInt()) }
                    pendingSeek = null
                    if (pendingPlay) mp.start()
                    playing = pendingPlay
                    pendingPlay = false
                }
                setOnCompletionListener { this@PreviewPlayer.playing = false }
                setOnErrorListener { _, _, _ ->
                    failed = true
                    preparing = false
                    this@PreviewPlayer.playing = false
                    true
                }
                preparing = true
                prepareAsync()
            }
        }.onFailure { failed = true; preparing = false }.getOrNull()?.also { player = it }
    }
}
