package com.example.lrcfetcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lrcfetcher.AppViewModel
import com.example.lrcfetcher.R
import com.example.lrcfetcher.SyncLine
import com.example.lrcfetcher.SyncSession
import com.example.lrcfetcher.lyrics.LrcWriter
import kotlinx.coroutines.delay

/**
 * "Sincronizar a mano": con la canción sonando, cada toque en «Marcar» pone el tiempo actual a
 * la línea siguiente. También sirve para corregir una línea desfasada.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SyncScreen(vm: AppViewModel, sync: SyncSession) {
    val context = LocalContext.current
    val track = sync.parent.track
    val player = remember(track?.uri) { track?.let { PreviewPlayer(context, it.androidUri) } }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(player?.playing) {
        while (player?.playing == true) {
            player.tick()
            delay(40)
        }
    }
    var confirmExit by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(false) }
    val dirty = sync.lines.any { it.start != null }
    fun exit() { if (dirty) confirmExit = true else vm.closeSyncEditor(sync) }
    BackHandler { exit() }

    val pos = player?.positionMs ?: 0L
    val playingIndex = sync.lines.indexOfLast { it.start != null && it.start!! <= pos }
    val listState = rememberLazyListState()
    LaunchedEffect(sync.cursor) { if (sync.cursor in sync.lines.indices) listState.animateScrollToItem((sync.cursor - 2).coerceAtLeast(0)) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = ::exit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                title = {
                    Column {
                        Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.sync_remaining, sync.remaining, sync.lines.size),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { editText = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.sync_edit_text)) }
                    TextButton(onClick = { vm.finishSync(sync) }, enabled = sync.remaining == 0 && sync.lines.isNotEmpty()) {
                        Text(stringResource(R.string.sync_done))
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val duration = track?.durationMs ?: 0L
                    if (duration > 0) {
                        Slider(
                            value = pos.coerceIn(0, duration).toFloat(),
                            onValueChange = { player?.seekTo(it.toLong()) },
                            valueRange = 0f..duration.toFloat(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            LrcWriter.formatTime(pos) + if (duration > 0) " / " + LrcWriter.formatTime(duration).substringBeforeLast('.') else "",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { player?.seekTo((pos - 5000).coerceAtLeast(0)) }) { Icon(Icons.Filled.Replay5, stringResource(R.string.sync_back5)) }
                        IconButton(onClick = { player?.toggle(); if (player?.failed == true) vm.message = com.example.lrcfetcher.UiText(R.string.msg_play_error) }) {
                            Icon(if (player?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play_check))
                        }
                        IconButton(
                            onClick = {
                                // Deshace la última marca y vuelve a esa línea.
                                val i = (sync.cursor - 1).coerceAtLeast(0)
                                if (i in sync.lines.indices) {
                                    sync.lines[i].start = null
                                    sync.cursor = i
                                }
                            },
                            enabled = sync.cursor > 0,
                        ) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.sync_undo)) }
                    }
                    // Ajuste fino de la última línea marcada.
                    val last = (sync.cursor - 1).takeIf { it in sync.lines.indices && sync.lines[it].start != null }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { last?.let { nudge(sync.lines[it], -100) } }, enabled = last != null) { Text("−0.1 s") }
                        TextButton(onClick = { last?.let { nudge(sync.lines[it], 100) } }, enabled = last != null) { Text("+0.1 s") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                if (player != null && !player.started) player.toggle()
                                if (sync.cursor in sync.lines.indices) {
                                    sync.lines[sync.cursor].start = player?.positionMs ?: 0L
                                    sync.cursor = sync.lines.indexOfFirst { it.start == null }.let { if (it < 0) sync.lines.size else it }
                                }
                            },
                            enabled = sync.cursor in sync.lines.indices,
                        ) {
                            Icon(Icons.Filled.TouchApp, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sync_mark))
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.sync_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            itemsIndexed(sync.lines) { i, line ->
                val isCursor = i == sync.cursor
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (isCursor) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .combinedClickable(
                            onClick = { sync.cursor = i },
                            onLongClick = { line.start?.let { player?.seekTo((it - 1500).coerceAtLeast(0)) } },
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        line.start?.let { LrcWriter.formatTime(it) } ?: "--:--.--",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (line.start == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (i == playingIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == playingIndex) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.sync_exit_title)) },
            text = { Text(stringResource(R.string.sync_exit_body)) },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.closeSyncEditor(sync) }) { Text(stringResource(R.string.sync_exit_discard)) } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (editText) {
        var text by remember { mutableStateOf(sync.lines.joinToString("\n") { it.text }) }
        AlertDialog(
            onDismissRequest = { editText = false },
            title = { Text(stringResource(R.string.sync_edit_text)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 6, maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Se conservan los tiempos de las líneas que siguen en el mismo lugar.
                    val newLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val old = sync.lines.toList()
                    sync.lines.clear()
                    sync.lines.addAll(newLines.mapIndexed { i, t -> SyncLine(t, old.getOrNull(i)?.start) })
                    sync.cursor = sync.lines.indexOfFirst { it.start == null }.let { if (it < 0) sync.lines.size else it }
                    editText = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editText = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun nudge(line: SyncLine, delta: Long) {
    line.start = ((line.start ?: 0L) + delta).coerceAtLeast(0)
}
