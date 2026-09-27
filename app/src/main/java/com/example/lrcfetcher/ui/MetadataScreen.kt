package com.example.lrcfetcher.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.lrcfetcher.AppViewModel
import com.example.lrcfetcher.MetaFields
import com.example.lrcfetcher.MetadataSession
import com.example.lrcfetcher.R
import com.example.lrcfetcher.metadata.CoverOption
import com.example.lrcfetcher.metadata.MetadataCandidate
import com.example.lrcfetcher.metadata.MetadataRepository
import com.example.lrcfetcher.tags.TrackTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataScreen(vm: AppViewModel, session: MetadataSession, snackbar: SnackbarHostState) {
    BackHandler { vm.closeMetadata() }
    var coverPicker by remember { mutableStateOf(false) }
    val track = session.track

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = vm::closeMetadata) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.metadata_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            track.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (track.needsReview) {
                        TextButton(onClick = { vm.dismissReview(track) }) { Text(stringResource(R.string.meta_review_dismiss)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { vm.saveMetadata(session) }, enabled = session.changed && !session.saving) {
                        if (session.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(if (track.needsReview) R.string.meta_accept else R.string.save))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (track.needsReview) {
                item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.meta_review_notice), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (track.swapSuspected || session.result?.swapped == true) {
                item {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SwapVert, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.meta_swapped_notice), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            item { CoverRow(session, onSearch = { coverPicker = true }, onUndo = { session.newCover = null }) }
            item { Fields(session) }
            item {
                OutlinedButton(onClick = { vm.searchMetadata(session) }, enabled = !session.searching, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.meta_search))
                }
            }
            if (session.searching) {
                item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.meta_searching), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            val result = session.result
            if (!session.searching && session.searched && (result == null || result.bySource.isEmpty())) {
                item { Text(stringResource(R.string.meta_no_results), style = MaterialTheme.typography.bodyMedium) }
            }
            if (!session.searching && result != null) {
                result.merged?.let { merged ->
                    item {
                        ResultCard(
                            title = stringResource(R.string.meta_best),
                            subtitle = stringResource(if (result.strictMatch) R.string.meta_strict_ok else R.string.meta_strict_no),
                            tags = merged,
                            highlight = true,
                            onUse = { vm.applyMetadata(session, merged) },
                        )
                    }
                }
                result.bySource.values.forEach { c ->
                    item { SourceCard(c) { vm.applyMetadata(session, c.tags) } }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (coverPicker) {
        CoverPickerDialog(
            options = session.result?.covers.orEmpty(),
            searching = session.searching,
            onPick = { vm.chooseCover(session, it); coverPicker = false },
            onDismiss = { coverPicker = false },
        )
    }
}

@Composable
private fun CoverRow(session: MetadataSession, onSearch: () -> Unit, onUndo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val bytes = session.newCover?.data ?: session.currentCover
        val bitmap = remember(bytes) { bytes?.let { decode(it, 400) } }
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when {
                session.coverLoading -> CircularProgressIndicator(Modifier.size(28.dp))
                bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.cover_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (session.newCover != null) R.string.cover_new else if (bytes == null) R.string.cover_none else R.string.cover_current),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSearch) { Text(stringResource(R.string.cover_search)) }
                if (session.newCover != null) TextButton(onClick = onUndo) { Text(stringResource(R.string.cover_undo)) }
            }
        }
    }
}

@Composable
private fun Fields(session: MetadataSession) {
    val f = session.fields
    fun update(block: (MetaFields) -> MetaFields) { session.fields = block(session.fields) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Field(stringResource(R.string.field_title), f.title) { v -> update { it.copy(title = v) } }
        TextButton(onClick = { update { it.copy(title = it.artists.substringBefore(';').trim(), artists = it.title) } }) {
            Icon(Icons.Filled.SwapVert, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.meta_swap))
        }
        Field(stringResource(R.string.field_artists), f.artists, hint = stringResource(R.string.meta_list_hint)) { v -> update { it.copy(artists = v) } }
        Field(stringResource(R.string.field_album), f.album) { v -> update { it.copy(album = v) } }
        Field(stringResource(R.string.field_album_artist), f.albumArtist) { v -> update { it.copy(albumArtist = v) } }
        Field(stringResource(R.string.field_composers), f.composers, hint = stringResource(R.string.meta_list_hint)) { v -> update { it.copy(composers = v) } }
        Field(stringResource(R.string.field_genres), f.genres, hint = stringResource(R.string.meta_list_hint)) { v -> update { it.copy(genres = v) } }
        Field(stringResource(R.string.field_year), f.year) { v -> update { it.copy(year = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.field_track), f.track, number = true, modifier = Modifier.weight(1f)) { v -> update { it.copy(track = v) } }
            Field(stringResource(R.string.field_track_total), f.trackTotal, number = true, modifier = Modifier.weight(1f)) { v -> update { it.copy(trackTotal = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.field_disc), f.disc, number = true, modifier = Modifier.weight(1f)) { v -> update { it.copy(disc = v) } }
            Field(stringResource(R.string.field_disc_total), f.discTotal, number = true, modifier = Modifier.weight(1f)) { v -> update { it.copy(discTotal = v) } }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    hint: String? = null,
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (number) v.filter { it.isDigit() }.take(4) else v) },
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier,
    )
}

@Composable
private fun SourceCard(c: MetadataCandidate, onUse: () -> Unit) {
    ResultCard(title = c.source.label, subtitle = null, tags = c.tags, highlight = false, onUse = onUse)
}

@Composable
private fun ResultCard(title: String, subtitle: String?, tags: TrackTags, highlight: Boolean, onUse: () -> Unit) {
    Surface(
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onUse) { Text(stringResource(R.string.meta_use)) }
            }
            Spacer(Modifier.height(4.dp))
            SummaryLine(stringResource(R.string.field_title), tags.title)
            SummaryLine(stringResource(R.string.field_artists), tags.artist)
            SummaryLine(stringResource(R.string.field_album), tags.album)
            SummaryLine(stringResource(R.string.field_album_artist), tags.albumArtist)
            SummaryLine(stringResource(R.string.field_composers), tags.composers.joinToString("; ").ifBlank { null })
            SummaryLine(stringResource(R.string.field_genres), tags.genres.joinToString("; ").ifBlank { null })
            SummaryLine(stringResource(R.string.field_year), tags.year)
            SummaryLine(stringResource(R.string.field_track), listOfNotNull(tags.trackNumber, tags.trackTotal).joinToString("/").ifBlank { null })
            SummaryLine(stringResource(R.string.field_disc), listOfNotNull(tags.discNumber, tags.discTotal).joinToString("/").ifBlank { null })
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CoverPickerDialog(options: List<CoverOption>, searching: Boolean, onPick: (CoverOption) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_pick_title)) },
        text = {
            when {
                searching -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                options.isEmpty() -> Text(stringResource(R.string.cover_no_results))
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(360.dp),
                ) {
                    items(options, key = { it.url }) { o ->
                        Column(Modifier.clickable { onPick(o) }) {
                            RemoteImage(
                                o.url,
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                            )
                            Text("${o.source} · ${o.size}px", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

// ---------------------------------------------------------------- imágenes remotas (sin dependencias)

private val thumbCache = LruCache<String, ImageBitmap>(48)

private fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

@Composable
private fun RemoteImage(url: String, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(thumbCache.get(url), url) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                MetadataRepository.downloadImage(url)?.let { decode(it, 300) }
            }?.also { thumbCache.put(url, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
