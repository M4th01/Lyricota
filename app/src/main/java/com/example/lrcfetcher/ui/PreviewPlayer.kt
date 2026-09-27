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
 * desfase de oído). Se prepara la primera vez que se pulsa "reproducir".
 */
class PreviewPlayer(private val context: Context, private val uri: Uri) {
    private var player: MediaPlayer? = null

    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var failed by mutableStateOf(false)
        private set

    val started: Boolean get() = player != null

    fun toggle() {
        val p = ensure() ?: return
        if (p.isPlaying) {
            p.pause()
            playing = false
        } else {
            p.start()
            playing = true
        }
    }

    fun seekTo(ms: Long) {
        val p = ensure() ?: return
        p.seekTo(ms.coerceAtLeast(0).toInt())
        positionMs = ms
        if (!p.isPlaying) {
            p.start()
            playing = true
        }
    }

    /** Llamar periódicamente mientras suena. */
    fun tick() {
        player?.let { if (it.isPlaying) positionMs = it.currentPosition.toLong() }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        playing = false
    }

    private fun ensure(): MediaPlayer? {
        player?.let { return it }
        return runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                setOnCompletionListener { this@PreviewPlayer.playing = false }
            }
        }.onFailure { failed = true }.getOrNull()?.also { player = it }
    }
}
