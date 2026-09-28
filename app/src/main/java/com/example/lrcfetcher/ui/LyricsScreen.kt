package com.example.lrcfetcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.lrcfetcher.R
import com.example.lrcfetcher.UiText
import com.example.lrcfetcher.AppViewModel
import com.example.lrcfetcher.LyricsSession
import com.example.lrcfetcher.SaveTarget
import com.example.lrcfetcher.lyrics.LrcWriter
import com.example.lrcfetcher.lyrics.LyricLine
import com.example.lrcfetcher.lyrics.Lyrics
import com.example.lrcfetcher.lyrics.ProviderId
import com.example.lrcfetcher.lyrics.ProviderResult
import com.example.lrcfetcher.lyrics.LyricWord
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.example.lrcfetcher.lyrics.SyncType
import com.example.lrcfetcher.lyrics.TextMode
import com.example.lrcfetcher.romanization.Romanizer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(vm: AppViewModel, session: LyricsSession, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    BackHandler { vm.closeLyrics() }

    var menuOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var offsetOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }

    val found = session.current
    val player = session.track?.let { t -> remember(t.uri) { PreviewPlayer(context, t.androidUri) } }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(player?.playing) {
        while (player?.playing == true) {
            player.tick()
            delay(40)
        }
    }
    val lyrics = vm.displayLyrics(session)
    val q = session.query
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportTo(uri, session)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = vm::closeLyrics) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
                title = {
                    Column {
                        Text(q.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        q.artist?.let {
                            Text(
                                it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { editOpen = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_fix_search)) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (session.showRaw) R.string.view_lyrics else R.string.view_lrc)) },
                                onClick = { session.showRaw = !session.showRaw; menuOpen = false },
                            )
                            if (session.track != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_metadata)) },
                                    onClick = { menuOpen = false; vm.openMetadata(session.track) },
                                )
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.adjust_offset)) }, onClick = { offsetOpen = true; menuOpen = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.import_lyrics)) }, onClick = { importOpen = true; menuOpen = false })
                            if (session.track != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sync_manual)) },
                                    onClick = {
                                        menuOpen = false
                                        if (lyrics != null) vm.openSyncEditor(session) else importOpen = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_lrc)) },
                                enabled = lyrics != null,
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch(listOfNotNull(q.artist, q.title).joinToString(" - ") + ".lrc")
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ActionBar(
                enabled = lyrics != null,
                canSave = session.track != null,
                saving = session.saving,
                saveLabel = when (vm.saveTarget) {
                    SaveTarget.EMBED -> stringResource(R.string.save)
                    SaveTarget.LRC -> stringResource(R.string.save_lrc)
                    SaveTarget.BOTH -> stringResource(R.string.save)
                },
                onCopy = { vm.lrcText(session)?.let { copy(context, it); vm.message = UiText(R.string.msg_copied) } },
                onShare = { vm.lrcText(session)?.let { share(context, it) } },
                onSave = { vm.saveToTrack(session) },
                onExport = { exportLauncher.launch(listOfNotNull(q.artist, q.title).joinToString(" - ") + ".lrc") },
                playing = player?.playing,
                onPlay = { player?.toggle(); if (player?.failed == true) vm.message = UiText(R.string.msg_play_error) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val local = session.results[ProviderId.LOCAL] as? ProviderResult.Found
            val showingLocal = session.selected == ProviderId.LOCAL
            if (local != null) LocalToggle(local, showingLocal) { vm.showLocal(session, it) }
            if (!showingLocal) ProviderChips(vm, session)
            OptionChips(vm, session, found?.lyrics)
            if (offsetOpen || session.offsetMs != 0L) OffsetRow(session) { offsetOpen = false }
            if (session.romanizing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))

            val stillLoading = session.results.values.any { it is ProviderResult.Loading }
            when {
                lyrics != null && session.showRaw -> RawLrc(LrcWriter.write(lyrics, vm.outputOptions(session)))
                lyrics != null -> LyricsBody(
                    lyrics = lyrics,
                    mode = vm.textMode,
                    translation = vm.includeTranslation,
                    voices = vm.includeVoices,
                    source = found?.let {
                        if (it.lyrics.source == ProviderId.LOCAL) stringResource(R.string.lyrics_local_source)
                        else if (it.lyrics.source == ProviderId.MANUAL) stringResource(R.string.source_manual_long)
                        else "${it.lyrics.source.label} · ${it.candidate.artist} — ${it.candidate.title}"
                    },
                    positionMs = player?.takeIf { it.started }?.positionMs?.minus(session.offsetMs),
                    following = player?.playing == true,
                    onLineClick = player?.let { p -> { line -> p.seekTo(line.start + session.offsetMs) } },
                )
                stillLoading && !showingLocal -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        title = stringResource(R.string.not_found_title),
                        body = stringResource(if (session.track != null) R.string.not_found_body_manual else R.string.not_found_body),
                    )
                    OutlinedButton(onClick = { importOpen = true }) { Text(stringResource(R.string.import_lyrics)) }
                }
            }
        }
    }

    if (importOpen) ImportLyricsDialog(onDismiss = { importOpen = false }) { text ->
        importOpen = false
        vm.importLyrics(session, text)
    }
        if (editOpen) EditQueryDialog(session, onDismiss = { editOpen = false }) { title, artist ->
        editOpen = false
        vm.editQuery(session, title, artist)
    }
}

/** "En la canción" (letra que ya tiene el archivo) frente a "Fuentes" (lo encontrado en línea). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalToggle(local: ProviderResult.Found, showingLocal: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        SegmentedButton(
            selected = showingLocal,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text(stringResource(R.string.lyrics_in_song, local.lyrics.sync.label()), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        SegmentedButton(
            selected = !showingLocal,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text(stringResource(R.string.lyrics_from_sources), maxLines = 1) }
    }
}

@Composable
private fun ProviderChips(vm: AppViewModel, session: LyricsSession) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ids = listOf(ProviderId.MANUAL).filter { session.results.containsKey(it) } +
            vm.providerOrder.filter { session.results.containsKey(it) }
        items(ids, key = { it.name }) { id ->
            val r = session.results[id]
            val found = r as? ProviderResult.Found
            FilterChip(
                selected = session.selected == id,
                enabled = found != null,
                onClick = { vm.selectProvider(session, id) },
                label = {
                    Text(
                        when (r) {
                            is ProviderResult.Found -> stringResource(
                                R.string.provider_chip,
                                if (id == ProviderId.MANUAL) stringResource(R.string.source_manual) else id.label,
                                r.lyrics.sync.label(),
                            )
                            ProviderResult.Loading -> id.label
                            else -> "${id.label} · —"
                        },
                    )
                },
                leadingIcon = if (r == ProviderResult.Loading) {
                    { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
                } else null,
            )
        }
    }
}

@Composable
private fun OptionChips(vm: AppViewModel, session: LyricsSession, lyrics: Lyrics?) {
    val needsRoman = remember(lyrics) { lyrics != null && Romanizer.needsRomanization(lyrics) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceChip(
            label = stringResource(R.string.format_label, vm.format.label()),
            options = SyncType.entries.map { it.label() to it },
            onPick = vm::updateFormat,
        )
        if (needsRoman) {
            ChoiceChip(
                label = vm.textMode.label(),
                selected = vm.textMode != TextMode.ORIGINAL,
                options = TextMode.entries.map { it.label() to it },
                onPick = { vm.setTextModeFor(session, it) },
            )
        }
        if (lyrics?.hasVoices == true) {
            FilterChip(
                selected = vm.includeVoices,
                onClick = { vm.updateVoices(!vm.includeVoices) },
                label = { Text(stringResource(R.string.voices)) },
            )
        }
        if (lyrics?.hasTranslation == true) {
            FilterChip(
                selected = vm.includeTranslation,
                onClick = { vm.setTranslation(!vm.includeTranslation) },
                label = { Text(stringResource(R.string.translation)) },
            )
        }
    }
}

@Composable
private fun <T> ChoiceChip(label: String, options: List<Pair<String, T>>, onPick: (T) -> Unit, selected: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected, onClick = { open = true }, label = { Text(label) },
            colors = FilterChipDefaults.filterChipColors(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

@Composable
private fun OffsetRow(session: LyricsSession, onClose: () -> Unit) = Column {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.offset), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { session.offsetMs -= 100 }) { Text("−0.1 s") }
        Text(
            String.format(java.util.Locale.ROOT, "%+.1f s", session.offsetMs / 1000.0),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(56.dp),
        )
        TextButton(onClick = { session.offsetMs += 100 }) { Text("+0.1 s") }
        TextButton(onClick = { session.offsetMs = 0; onClose() }) { Text(stringResource(R.string.offset_remove)) }
    }
    if (session.offsetMs != 0L) {
        Text(
            stringResource(R.string.offset_saved_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun LyricsBody(
    lyrics: Lyrics,
    mode: TextMode,
    translation: Boolean,
    voices: Boolean,
    source: String?,
    positionMs: Long?,
    following: Boolean,
    onLineClick: ((LyricLine) -> Unit)?,
) {
    val synced = lyrics.sync != SyncType.PLAIN
    val listState = rememberLazyListState()
    val current = if (positionMs == null || !synced) -1 else lyrics.lines.indexOfLast { it.start <= positionMs }
    LaunchedEffect(current, following) {
        if (following && current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    SelectionContainer {
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
            itemsIndexed(lyrics.lines) { index, line ->
                val roman = line.romanText?.takeIf { it.isNotBlank() && it != line.text }
                val showRomanMain = mode == TextMode.ROMANIZED && roman != null
                val main = if (showRomanMain) roman!! else line.text
                val dim = current >= 0 && index != current
                // Karaoke letra por letra en la línea que suena (letra palabra por palabra).
                val live = index == current && positionMs != null
                val sung = MaterialTheme.colorScheme.primary
                val pending = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                fun karaokeOf(words: List<LyricWord>?, fallback: String): AnnotatedString? =
                    if (live && lyrics.sync == SyncType.WORD && words != null && hasWordTiming(words)) karaoke(words, positionMs!!, sung, pending)
                    else null
                val mainKaraoke = karaokeOf(if (showRomanMain) line.romanWords else line.words, main)
                val rowModifier = Modifier.fillMaxWidth()
                    .then(if (onLineClick != null && synced) Modifier.clickable { onLineClick(line) } else Modifier)
                    .padding(vertical = 6.dp)
                    .alpha(if (dim) 0.45f else 1f)
                Row(rowModifier) {
                    if (synced) {
                        Text(
                            LrcWriter.formatTime(line.start).substringBeforeLast('.'),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(44.dp).padding(top = 5.dp),
                        )
                    }
                    // Como en Apple Music: la segunda voz de un dúo va alineada a la derecha.
                    val secondVoice = voices && line.isSecondaryVoice
                    val align = if (secondVoice) TextAlign.End else TextAlign.Start
                    Column(Modifier.weight(1f), horizontalAlignment = if (secondVoice) Alignment.End else Alignment.Start) {
                        if (mainKaraoke != null) {
                            Text(mainKaraoke, style = LyricLineStyle, textAlign = align)
                        } else {
                            Text(
                                if (main.isBlank()) "♪" else main,
                                style = LyricLineStyle,
                                textAlign = align,
                                color = if (index == current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                        }
                        if (mode == TextMode.BOTH && roman != null) {
                            val romanKaraoke = karaokeOf(line.romanWords, roman)
                            if (romanKaraoke != null) Text(romanKaraoke, style = MaterialTheme.typography.bodyMedium, textAlign = align)
                            else Text(roman, style = MaterialTheme.typography.bodyMedium, textAlign = align, color = MaterialTheme.colorScheme.primary)
                        }
                        val bg = if (voices) line.backgroundText else null
                        if (bg != null) {
                            val bgRoman = line.backgroundRomanText?.takeIf { it != bg }
                            val romanBg = mode == TextMode.ROMANIZED && bgRoman != null
                            val shown = if (romanBg) bgRoman!! else bg
                            val bgKaraoke = karaokeOf(if (romanBg) line.backgroundRoman else line.background, shown)
                            if (bgKaraoke != null) {
                                Text(
                                    buildAnnotatedString { append("("); append(bgKaraoke); append(")") },
                                    style = MaterialTheme.typography.bodyMedium, textAlign = align,
                                )
                            } else {
                                Text(
                                    "($shown)", style = MaterialTheme.typography.bodyMedium, textAlign = align,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (mode == TextMode.BOTH && bgRoman != null) {
                                Text("($bgRoman)", style = MaterialTheme.typography.bodySmall, textAlign = align, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (translation && !line.translation.isNullOrBlank()) {
                            Text(
                                line.translation, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                textAlign = align,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (source != null) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** Las palabras traen tiempos propios (no todas en el mismo instante). */
private fun hasWordTiming(words: List<LyricWord>): Boolean =
    words.size > 1 && words.any { it.end > it.start } || words.size == 1 && words[0].end > words[0].start

/**
 * Colorea lo ya cantado: palabras completas y, dentro de la palabra que suena, las letras en
 * proporción al tiempo transcurrido.
 */
internal fun karaoke(words: List<LyricWord>, pos: Long, sung: Color, pending: Color): AnnotatedString = buildAnnotatedString {
    words.forEachIndexed { i, w ->
        val text = if (i == 0) w.text.trimStart() else if (i == words.lastIndex) w.text.trimEnd() else w.text
        val done = when {
            pos >= w.end && w.end > w.start -> text.length
            pos < w.start -> 0
            w.end <= w.start -> text.length
            else -> ((pos - w.start).toDouble() / (w.end - w.start) * text.length).toInt().coerceIn(0, text.length)
        }
        if (done > 0) withStyle(SpanStyle(color = sung)) { append(text.substring(0, done)) }
        if (done < text.length) withStyle(SpanStyle(color = pending)) { append(text.substring(done)) }
    }
}

@Composable
private fun RawLrc(text: String) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}

@Composable
private fun ActionBar(
    enabled: Boolean,
    canSave: Boolean,
    saving: Boolean,
    saveLabel: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    playing: Boolean?,
    onPlay: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCopy, enabled = enabled) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.copy))
            }
            IconButton(onClick = onShare, enabled = enabled) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share)) }
            if (playing != null) {
                IconButton(onClick = onPlay, enabled = enabled) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.pause else R.string.play_check),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = if (canSave) onSave else onExport, enabled = enabled && !saving) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (canSave) saveLabel else stringResource(R.string.export))
            }
        }
    }
}

@Composable
private fun EditQueryDialog(session: LyricsSession, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf(session.query.title) }
    var artist by remember { mutableStateOf(session.query.artist.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_search_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.title_label)) }, singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text(stringResource(R.string.artist_label)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, artist) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.search)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("lrc", text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_title)))
}

/** Pegar una letra o abrir un .lrc / .txt del teléfono. */
@Composable
private fun ImportLyricsDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull()?.let { text = it }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_lyrics)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.import_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 6, maxLines = 12,
                    placeholder = { Text(stringResource(R.string.import_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/octet-stream", "application/x-subrip")) }) {
                    Text(stringResource(R.string.import_file))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.import_use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
