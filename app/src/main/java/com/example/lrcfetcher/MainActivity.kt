package com.example.lrcfetcher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.lrcfetcher.ui.AboutScreen
import com.example.lrcfetcher.ui.BatchDialog
import com.example.lrcfetcher.ui.MetadataBatchDialog
import com.example.lrcfetcher.ui.MetadataScreen
import com.example.lrcfetcher.ui.LocaleHelper
import com.example.lrcfetcher.ui.resolve
import com.example.lrcfetcher.ui.LibraryScreen
import com.example.lrcfetcher.ui.LyricotaTheme
import com.example.lrcfetcher.ui.LyricsScreen
import com.example.lrcfetcher.ui.OnboardingScreen
import android.Manifest
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.example.lrcfetcher.ui.SettingsSheet
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Aplica el idioma elegido dentro de la app (o el del sistema). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, Settings(newBase).language))
    }

    private fun changeLanguage(language: AppLanguage) {
        vm.settings.language = language
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = when (vm.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LyricotaTheme(dark) {
                val bg = MaterialTheme.colorScheme.background.toArgb()
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(bg) else SystemBarStyle.light(bg, bg)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                // Mantener la pantalla encendida mientras corre un lote.
                LaunchedEffect(vm.batch.running) {
                    if (vm.batch.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var showSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                    LaunchedEffect(Unit) {
                        delay(900)
                        showSplash = false
                    }
                    if (showSplash) SplashScreen() else AppContent(vm, ::changeLanguage)
                }
            }
        }
    }
}

@Composable
private fun AppContent(vm: AppViewModel, onLanguage: (AppLanguage) -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var batchOpen by remember { mutableStateOf(false) }
    var metaBatchOpen by remember { mutableStateOf(false) }
    var tutorialOpen by rememberSaveable { mutableStateOf(!vm.settings.onboardingDone) }
    // Permiso de notificaciones: se explica y se pide al empezar un lote (una vez por sesión).
    var notifAskedThisSession by rememberSaveable { mutableStateOf(false) }
    var notifRationale by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(vm.batch.running) {
        if (vm.batch.running && Build.VERSION.SDK_INT >= 33 && !notifAskedThisSession && !BatchNotifier.canPost(context)) {
            notifAskedThisSession = true
            notifRationale = true
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }

    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        vm.message = null
        snackbar.showSnackbar(m.resolve(context))
    }

    // Aviso de actualización (como mucho cada 12 h), después del tutorial.
    LaunchedEffect(tutorialOpen) { if (!tutorialOpen) vm.checkForUpdates(manual = false) }
    vm.update?.let { if (!tutorialOpen) com.example.lrcfetcher.ui.UpdateDialog(vm, it) }

    if (tutorialOpen) {
        OnboardingScreen(
            folderChosen = vm.folderUri != null,
            onPickFolder = { folderPicker.launch(null) },
            onFinish = {
                vm.settings.onboardingDone = true
                tutorialOpen = false
            },
        )
        return
    }

    if (aboutOpen) {
        AboutScreen(onBack = { aboutOpen = false })
        return
    }

    AnimatedContent(
        targetState = vm.screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Library -> LibraryScreen(
                vm = vm,
                snackbar = snackbar,
                onPickFolder = { folderPicker.launch(null) },
                onOpenSettings = { settingsOpen = true },
                onOpenBatch = { batchOpen = true },
                onOpenAbout = { aboutOpen = true },
                onOpenMetaBatch = { metaBatchOpen = true },
                onOpenTutorial = { tutorialOpen = true },
            )
            is Screen.LyricsView -> LyricsScreen(vm, screen.session, snackbar)
            is Screen.MetadataView -> MetadataScreen(vm, screen.session, snackbar)
        }
    }

    if (settingsOpen) SettingsSheet(vm, onLanguage = { settingsOpen = false; onLanguage(it) }) { settingsOpen = false }
    if (batchOpen) BatchDialog(vm) { batchOpen = false }
    if (metaBatchOpen) MetadataBatchDialog(vm) { metaBatchOpen = false }
    if (notifRationale) {
        AlertDialog(
            onDismissRequest = { notifRationale = false },
            title = { Text(stringResource(R.string.perm_notif_title)) },
            text = { Text(stringResource(R.string.perm_notif_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    notifRationale = false
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.perm_notif_action)) }
            },
            dismissButton = { TextButton(onClick = { notifRationale = false }) { Text(stringResource(R.string.perm_not_now)) } },
        )
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().padding(32.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(140.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            stringResource(R.string.made_by),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
