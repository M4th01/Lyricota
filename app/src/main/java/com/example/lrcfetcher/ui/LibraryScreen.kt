package com.example.lrcfetcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.lrcfetcher.AppViewModel
import com.example.lrcfetcher.BatchKind
import com.example.lrcfetcher.LibraryFilter
import com.example.lrcfetcher.MetaFilter
import com.example.lrcfetcher.R
import com.example.lrcfetcher.Section
import com.example.lrcfetcher.SortMode
import com.example.lrcfetcher.library.Identity
import com.example.lrcfetcher.library.LyricsWriter
import com.example.lrcfetcher.library.Track
import com.example.lrcfetcher.lyrics.SongCandidate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: AppViewModel,
    snackbar: SnackbarHostState,
    onPickFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenMetaBatch: () -> Unit,
    onOpenTutorial: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = vm.selectionMode) { vm.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.selectionMode) {
                SelectionBar(vm, onOpenBatch, onOpenMetaBatch)
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        if (vm.folderUri != null && vm.section == Section.LYRICS) {
                            IconButton(onClick = onOpenBatch, enabled = !vm.batch.running) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = stringResource(R.string.cd_batch))
                            }
                        }
                        if (vm.folderUri != null && vm.section == Section.METADATA) {
                            IconButton(onClick = onOpenMetaBatch, enabled = !vm.batch.running) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = stringResource(R.string.menu_metadata_batch))
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_choose_folder)) }, onClick = { menuOpen = false; onPickFolder() })
                                if (vm.folderUri != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_batch)) },
                                        enabled = !vm.batch.running,
                                        onClick = { menuOpen = false; onOpenBatch() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_metadata_batch)) },
                                        enabled = !vm.batch.running,
                                        onClick = { menuOpen = false; onOpenMetaBatch() },
                                    )
                                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_refresh)) }, onClick = { menuOpen = false; vm.rescan() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_rescan)) }, onClick = { menuOpen = false; vm.rescan(full = true) })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_settings)) }, onClick = { menuOpen = false; onOpenSettings() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_tutorial)) }, onClick = { menuOpen = false; onOpenTutorial() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_about)) }, onClick = { menuOpen = false; onOpenAbout() })
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavItem(vm, Section.LYRICS, Icons.Filled.Lyrics, R.string.nav_lyrics)
                NavItem(vm, Section.METADATA, Icons.Filled.Sell, R.string.nav_metadata, badge = vm.reviewCount)
                NavItem(vm, Section.ONLINE, Icons.Filled.Public, R.string.nav_online)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                placeholder = stringResource(if (vm.section == Section.ONLINE) R.string.search_online else R.string.search_library),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            when (vm.section) {
                Section.ONLINE -> OnlineResults(vm)
                else -> LibraryList(vm, onPickFolder)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    vm: AppViewModel,
    section: Section,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    badge: Int = 0,
) {
    NavigationBarItem(
        selected = vm.section == section,
        onClick = { vm.changeSection(section) },
        icon = {
            if (badge > 0) {
                androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text("$badge") } }) { Icon(icon, null) }
            } else {
                Icon(icon, null)
            }
        },
        label = { Text(stringResource(label)) },
    )
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear)) }
        },
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun LibraryList(vm: AppViewModel, onPickFolder: () -> Unit) {
    if (vm.folderUri == null) {
        EmptyState(
            title = stringResource(R.string.empty_pick_title),
            body = stringResource(R.string.empty_pick_body),
            action = stringResource(R.string.menu_choose_folder),
            onAction = onPickFolder,
        )
        return
    }
    val metadata = vm.section == Section.METADATA
    val visible = remember(vm.tracks, vm.query, vm.filter, vm.metaFilter, vm.sort, vm.section) { vm.visibleTracks() }

    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (metadata) stringResource(R.string.meta_stats, vm.tracks.size, vm.tracks.count { it.metadataLoaded && !it.tags.isIncomplete })
            else stringResource(R.string.library_stats, vm.tracks.size, vm.tracks.count { it.hasLyrics }),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (metadata) {
            ChoiceMenu(vm.metaFilter.label(), MetaFilter.entries.map { it.label() to it }, vm::updateMetaFilter)
        } else {
            ChoiceMenu(vm.filter.label(), LibraryFilter.entries.map { it.label() to it }, vm::updateFilter)
        }
        SortMenu(vm)
    }

    if (vm.scan.running) {
        val s = vm.scan
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            if ((s.readingTags || s.identifying) && s.total > 0) {
                LinearProgressIndicator(progress = { s.done / s.total.toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(
                when {
                    s.identifying -> stringResource(R.string.scan_identity, s.done, s.total)
                    s.readingTags -> stringResource(R.string.scan_tags, s.done, s.total)
                    else -> stringResource(R.string.scan_listing)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    BatchBanner(vm)
    ReviewBanner(vm)

    if (visible.isEmpty() && !vm.scan.running) {
        EmptyState(
            title = stringResource(if (vm.tracks.isEmpty()) R.string.empty_no_songs else R.string.empty_nothing),
            body = stringResource(if (vm.tracks.isEmpty()) R.string.empty_no_songs_body else R.string.empty_nothing_body),
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(visible, key = { it.uri }) { track ->
            val onClick = {
                when {
                    vm.selectionMode -> vm.toggleSelection(track)
                    metadata -> vm.openMetadata(track)
                    else -> vm.openTrack(track)
                }
            }
            TrackRow(
                track = track,
                metadata = metadata,
                selectionMode = vm.selectionMode,
                selected = track.uri in vm.selected,
                onClick = onClick,
                onLongClick = { vm.toggleSelection(track) },
            )
        }
    }
}

@Composable
private fun <T> ChoiceMenu(label: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false }) }
        }
    }
}

@Composable
private fun SortMenu(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.cd_sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortMode.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label(), color = if (s == vm.sort) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    onClick = { vm.updateSort(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun BatchBanner(vm: AppViewModel) {
    val b = vm.batch
    if (!b.running && b.total == 0) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val meta = b.kind == BatchKind.METADATA
                    Text(
                        stringResource(
                            when {
                                meta && b.running -> R.string.batch_meta_running
                                b.running -> R.string.batch_running
                                else -> R.string.batch_finished
                            },
                            b.done, b.total,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (b.running && b.current.isNotBlank()) b.current
                        else if (meta) stringResource(R.string.batch_meta_summary, b.found, b.review)
                        else if (b.failed > 0) stringResource(R.string.batch_summary_errors, b.found, b.notFound, b.failed)
                        else stringResource(R.string.batch_summary, b.found, b.notFound),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
                if (b.running) TextButton(onClick = vm::cancelBatch) { Text(stringResource(R.string.stop)) }
                else IconButton(onClick = vm::dismissBatchSummary) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
            }
            if (b.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { if (b.total == 0) 0f else b.done / b.total.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** "Es necesaria la aceptación del usuario para cambios en metadatos". */
@Composable
private fun ReviewBanner(vm: AppViewModel) {
    val count = vm.reviewCount
    if (count == 0) return
    // En el filtro "Por aprobar" se explican los pasos; en el resto, un aviso corto.
    val reviewing = vm.section == Section.METADATA && vm.metaFilter == MetaFilter.REVIEW
    var confirm by remember { mutableStateOf(false) }
    val onColor = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = onColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    pluralStringResource(R.plurals.meta_review_title, count, count),
                    style = MaterialTheme.typography.titleSmall, color = onColor,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.meta_review_why), style = MaterialTheme.typography.bodySmall, color = onColor, modifier = Modifier.padding(end = 8.dp))
            if (reviewing) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.meta_review_steps), style = MaterialTheme.typography.bodySmall, color = onColor, modifier = Modifier.padding(end = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.meta_review_dismiss_all)) }
                if (!reviewing) {
                    TextButton(onClick = { vm.changeSection(Section.METADATA); vm.updateMetaFilter(MetaFilter.REVIEW) }) {
                        Text(stringResource(R.string.meta_review_action))
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.meta_review_dismiss_all_title)) },
            text = { Text(pluralStringResource(R.plurals.meta_review_dismiss_all_body, count, count)) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.dismissAllReviews() }) { Text(stringResource(R.string.meta_review_dismiss_all)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(vm: AppViewModel, onOpenBatch: () -> Unit, onOpenMetaBatch: () -> Unit) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = {
            IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close_selection)) }
        },
        title = { Text(stringResource(R.string.selection_count, vm.selected.size)) },
        actions = {
            IconButton(onClick = { vm.selectAll(vm.visibleTracks()) }) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.select_all))
            }
            IconButton(onClick = onOpenBatch, enabled = !vm.batch.running) {
                Icon(Icons.Filled.Lyrics, contentDescription = stringResource(R.string.action_lyrics))
            }
            IconButton(
                onClick = {
                    // Una sola canción: pantalla individual (con portada). Varias: lote.
                    if (vm.selected.size == 1) {
                        vm.tracks.firstOrNull { it.uri in vm.selected }?.let { vm.clearSelection(); vm.openMetadata(it) }
                    } else {
                        onOpenMetaBatch()
                    }
                },
                enabled = !vm.batch.running,
            ) {
                Icon(Icons.Filled.Sell, contentDescription = stringResource(R.string.action_metadata))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: Track,
    metadata: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (metadata) {
                listOfNotNull(track.artist, track.album, track.tags.yearNumber).joinToString(" · ")
            } else {
                listOfNotNull(track.artist, track.album).joinToString(" · ")
            }.ifBlank { track.fileName }
            Text(
                sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (metadata) MetadataStatus(track)
        }
        Spacer(Modifier.width(12.dp))
        if (!metadata) {
            track.durationMs?.let {
                Text(formatDuration(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
        }
        if (track.needsReview) {
            Icon(
                Icons.Filled.Warning, contentDescription = stringResource(R.string.filter_review),
                tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (metadata) {
            Icon(
                Icons.Filled.Image,
                contentDescription = if (track.hasCover) stringResource(R.string.cover_current) else stringResource(R.string.cover_none),
                tint = if (track.hasCover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                Icons.Filled.Lyrics,
                contentDescription = if (track.hasLyrics) stringResource(R.string.cd_has_lyrics) else null,
                tint = if (track.hasLyrics) MaterialTheme.colorScheme.primary else Color.Transparent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Línea de estado en la sección Metadatos: completo / qué falta / sin etiquetas / sólo lectura. */
@Composable
private fun MetadataStatus(track: Track) {
    if (!track.metadataLoaded) return
    val t = track.tags
    val missing = buildList {
        if (t.album == null) add(stringResource(R.string.field_album))
        if (t.yearNumber == null) add(stringResource(R.string.field_year))
        if (t.trackNumber == null) add(stringResource(R.string.field_track))
        if (t.genres.isEmpty()) add(stringResource(R.string.field_genres))
        if (t.albumArtist == null) add(stringResource(R.string.field_album_artist))
        if (t.composers.isEmpty()) add(stringResource(R.string.field_composers))
    }
    val text = when {
        track.swapSuspected -> stringResource(R.string.meta_swapped_short)
        track.identity != Identity.TAGS -> stringResource(R.string.meta_untagged)
        missing.isEmpty() -> stringResource(R.string.meta_complete)
        else -> stringResource(R.string.meta_missing, missing.joinToString(", "))
    } + if (track.ext !in LyricsWriter.EMBEDDABLE) " · " + stringResource(R.string.meta_read_only) else ""
    val warn = track.swapSuspected || track.identity != Identity.TAGS || t.isIncomplete
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun OnlineResults(vm: AppViewModel) {
    if (vm.onlineLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    when {
        vm.query.isBlank() -> EmptyState(
            title = stringResource(R.string.online_prompt_title),
            body = stringResource(R.string.online_prompt_body, vm.enabledProviders.joinToString(", ") { it.label }),
        )
        vm.onlineError != null -> EmptyState(
            title = stringResource(R.string.online_error_title),
            body = vm.onlineError?.let { stringResource(it.id, *it.args) }.orEmpty(),
        )
        vm.onlineResults.isEmpty() && !vm.onlineLoading -> EmptyState(
            title = stringResource(R.string.online_no_results),
            body = stringResource(R.string.online_no_results_body),
        )
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(vm.onlineResults, key = { it.key }) { c -> CandidateRow(c) { vm.openCandidate(c) } }
        }
    }
}

@Composable
private fun CandidateRow(c: SongCandidate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(c.artist.ifBlank { null }, c.album, c.durationMs?.let(::formatDuration)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        ProviderTag(c.provider.label)
    }
}

@Composable
fun ProviderTag(label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (action != null) Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
