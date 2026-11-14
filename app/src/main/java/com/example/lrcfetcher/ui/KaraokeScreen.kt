package com.example.lrcfetcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.example.lrcfetcher.R
import com.example.lrcfetcher.lyrics.LrcWriter
import com.example.lrcfetcher.lyrics.LyricWord
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TextMode

/** Modo karaoke a pantalla completa: la línea que suena, grande y centrada, con relleno letra por letra. */
@Composable
fun KaraokeView(
    lyrics: Lyrics,
    player: PreviewPlayer,
    title: String,
    artist: String?,
    offsetMs: Long,
    mode: TextMode,
    voices: Boolean,
    scale: Float,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    // Pantalla encendida mientras se canta; se restaura al cerrar.
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }

    val pos = player.positionMs - offsetMs
    val current = lyrics.lines.indexOfLast { it.start <= pos }
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (lyrics.lines.isNotEmpty()) listState.animateScrollToItem(current.coerceAtLeast(0))
    }
    val sung = MaterialTheme.colorScheme.primary
    val pending = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Cabecera: título, artista y cerrar.
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (artist != null) {
                        Text(
                            artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.karaoke_close)) }
            }

            // Letra: la línea actual se mantiene hacia el centro.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val pad = maxHeight * 0.35f
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = pad, bottom = pad),
                ) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        val t by animateFloatAsState(if (index == current) 1f else 0f, tween(250), label = "karaokeLine")
                        val roman = line.romanText?.takeIf { it.isNotBlank() && it != line.text }
                        val showRomanMain = mode == TextMode.ROMANIZED && roman != null
                        val main = if (showRomanMain) roman!! else line.text
                        val words = if (showRomanMain) line.romanWords else line.words
                        val live = index == current && lyrics.sync == SyncType.WORD && words != null && hasWordTiming(words)
                        val big = MaterialTheme.typography.headlineSmall
                        val small = MaterialTheme.typography.titleMedium
                        val size = lerp(small.fontSize * scale, big.fontSize * scale, t)
                        val mainStyle: TextStyle = small.copy(
                            fontSize = size,
                            lineHeight = size * 1.3f,
                            fontWeight = if (t > 0.5f) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        )
                        val sub = MaterialTheme.typography.bodyMedium.let { it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale, textAlign = TextAlign.Center) }
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    player.seekTo(line.start + offsetMs)
                                }
                                .padding(vertical = 10.dp)
                                .alpha(0.4f + 0.6f * t),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (live) {
                                Text(karaoke(words!!, pos, sung, pending), style = mainStyle)
                            } else {
                                Text(
                                    if (main.isBlank()) "♪" else main, style = mainStyle,
                                    color = if (index == current) sung else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (mode == TextMode.BOTH && roman != null) {
                                Text(roman, style = sub, color = MaterialTheme.colorScheme.primary)
                            }
                            val bg = if (voices) line.backgroundText else null
                            if (bg != null) {
                                val bgRoman = line.backgroundRomanText?.takeIf { it != bg }
                                val shown = if (mode == TextMode.ROMANIZED && bgRoman != null) bgRoman else bg
                                Text("($shown)", style = sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Progreso y reproducir / pausar.
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerProgress(player, fallbackDurationMs = null)
                Spacer(Modifier.height(4.dp))
                FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.size(64.dp)) {
                    if (player.preparing) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(
                            if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (player.playing) R.string.pause else R.string.play_check),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fila compacta: tiempo actual, barra de progreso y duración. Sin duración conocida (ni la de la
 * canción) la barra se oculta; mientras se prepara el archivo, una barra indeterminada fina.
 */
@Composable
internal fun PlayerProgress(player: PreviewPlayer, fallbackDurationMs: Long?) {
    val duration = if (player.durationMs > 0) player.durationMs else fallbackDurationMs ?: 0L
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val shown = if (dragging) dragValue else player.positionMs.coerceIn(0L, duration.coerceAtLeast(0L)).toFloat()
    val mono = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(clock(shown.toLong()), style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
        when {
            player.preparing -> LinearProgressIndicator(Modifier.weight(1f).padding(horizontal = 8.dp).height(2.dp))
            duration > 0 -> Slider(
                value = shown,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { dragging = false; player.seekTo(dragValue.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.weight(1f),
            )
            else -> Spacer(Modifier.weight(1f))
        }
        Text(
            if (duration > 0) clock(duration) else "--:--", style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp), textAlign = TextAlign.End,
        )
    }
}

private fun clock(ms: Long): String = LrcWriter.formatTime(ms).substringBeforeLast('.')
