package com.example.lrcfetcher.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lrcfetcher.BatchNotifier
import com.example.lrcfetcher.R
import kotlinx.coroutines.launch

private class TourPage(val icon: ImageVector?, @StringRes val title: Int, val points: List<Int>)

private val pages = listOf(
    TourPage(null, R.string.onb_welcome_title, listOf(R.string.onb_welcome_body)),
    TourPage(Icons.Filled.Lyrics, R.string.onb_lyrics_title, listOf(R.string.onb_lyrics_1, R.string.onb_lyrics_2, R.string.onb_lyrics_3)),
    TourPage(Icons.Filled.Album, R.string.onb_meta_title, listOf(R.string.onb_meta_1, R.string.onb_meta_2, R.string.onb_meta_3)),
    TourPage(Icons.Filled.TravelExplore, R.string.onb_online_title, listOf(R.string.onb_online_1)),
    TourPage(Icons.Filled.PlaylistPlay, R.string.onb_batch_title, listOf(R.string.onb_batch_1, R.string.onb_batch_2, R.string.onb_batch_3)),
)

/** Mini tutorial de la primera vez; la última página pide los permisos explicando cada uno. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(folderChosen: Boolean, onPickFolder: () -> Unit, onFinish: () -> Unit, startPage: Int = 0) {
    val count = pages.size + 1
    val pager = rememberPagerState(initialPage = startPage) { count }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            if (pager.currentPage < count - 1) TextButton(onClick = onFinish) { Text(stringResource(R.string.onb_skip)) }
            else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            if (i < pages.size) TourContent(pages[i]) else PermissionsPage(folderChosen, onPickFolder)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(count) { i ->
                    Box(
                        Modifier.size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                }
            }
            val last = pager.currentPage == count - 1
            Button(onClick = { if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                Text(stringResource(if (last) R.string.onb_start else R.string.onb_next))
            }
        }
    }
}

@Composable
private fun TourContent(page: TourPage) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (page.icon == null) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(140.dp))
        } else {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(page.icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (page.points.size == 1) {
            Text(
                stringResource(page.points[0]),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                page.points.forEach { p ->
                    Row {
                        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(p), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsPage(folderChosen: Boolean, onPickFolder: () -> Unit) {
    val context = LocalContext.current
    var notifGranted by remember { mutableStateOf(BatchNotifier.canPost(context)) }
    var notifAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifAsked = true
        notifGranted = BatchNotifier.canPost(context)
    }
    // Al volver de los ajustes de Android se relee el estado.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) notifGranted = BatchNotifier.canPost(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.onb_perm_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.onb_perm_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        PermissionCard(
            icon = Icons.Filled.Folder,
            title = R.string.perm_folder_title,
            body = R.string.perm_folder_body,
            granted = folderChosen,
            action = R.string.perm_folder_action,
            onAction = onPickFolder,
        )
        PermissionCard(
            icon = Icons.Filled.Notifications,
            title = R.string.perm_notif_title,
            body = R.string.perm_notif_body,
            granted = notifGranted,
            // Denegado dos veces: Android ya no muestra el diálogo, se abren sus ajustes.
            action = if (notifAsked && !notifGranted) R.string.perm_open_settings else R.string.perm_notif_action,
            onAction = {
                if (Build.VERSION.SDK_INT >= 33 && !notifAsked) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                    notifAsked = false
                }
            },
        )
        PermissionCard(
            icon = Icons.Filled.Language,
            title = R.string.perm_internet_title,
            body = R.string.perm_internet_body,
            granted = true,
            grantedLabel = R.string.perm_automatic,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes body: Int,
    granted: Boolean,
    @StringRes action: Int = 0,
    @StringRes grantedLabel: Int = R.string.perm_granted,
    onAction: () -> Unit = {},
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(grantedLabel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                OutlinedButton(onClick = onAction) { Text(stringResource(action)) }
            }
        }
    }
}
