package com.example.lrcfetcher.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.lrcfetcher.AppInfo
import com.example.lrcfetcher.R

private data class Source(val name: String, val url: String, @StringRes val description: Int)

private data class Library(
    val name: String,
    val copyright: String,
    val license: String,
    @StringRes val description: Int,
    /** Texto de licencia incluido en assets/licenses. */
    val licenseFile: String,
)

private val sources = listOf(
    Source("Apple Music", "lyrics.paxsenix.org · itunes.apple.com", R.string.about_src_apple),
    Source("AMLL TTML DB", "github.com/amll-dev/amll-ttml-db", R.string.about_src_amll),
    Source("QQ Music", "y.qq.com", R.string.about_src_qq),
    Source("Kugou", "kugou.com", R.string.about_src_kugou),
    Source("NetEase Cloud Music", "music.163.com", R.string.about_src_netease),
    Source("SyncLRC", "api.synclrc.dev", R.string.about_src_synclrc),
    Source("LRCLIB", "lrclib.net", R.string.about_src_lrclib),
)

private val metaSources = listOf(
    Source("Deezer", "api.deezer.com", R.string.about_src_deezer),
    Source("Apple Music / iTunes", "itunes.apple.com", R.string.about_src_itunes_meta),
    Source("MusicBrainz · Cover Art Archive", "musicbrainz.org · coverartarchive.org", R.string.about_src_musicbrainz),
)

private val libraries = listOf(
    Library("AndroidX · Jetpack Compose · Material 3", "© The Android Open Source Project", "Apache License 2.0", R.string.about_lib_androidx, "Apache-2.0.txt"),
    Library("Kotlin · kotlinx.coroutines", "© JetBrains s.r.o. and contributors", "Apache License 2.0", R.string.about_lib_kotlin, "Apache-2.0.txt"),
    Library("Kuromoji 0.9.0", "© 2010-2015 Atilika Inc. and contributors", "Apache License 2.0", R.string.about_lib_kuromoji, "Kuromoji-NOTICE.txt"),
    Library("mecab-ipadic 2.7.0", "© Nara Institute of Science and Technology (NAIST)", "IPADIC License", R.string.about_lib_ipadic, "Kuromoji-NOTICE.txt"),
    Library("ICU4J 75.1", "© 2016-2024 Unicode, Inc.", "Unicode License v3", R.string.about_lib_icu, "ICU-LICENSE.txt"),
    Library("QQMusicDecoder", "© 2023 WXRIW", "MIT License", R.string.about_lib_qrc, "QQMusicDecoder-MIT.txt"),
    Library("LDDC", "© chenmozhijin", "GPL-3.0", R.string.about_lib_lddc, "GPL-3.0.txt"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    var licenseToShow by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                title = { Text(stringResource(R.string.about_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.about_version, version) + " · " + stringResource(R.string.made_by),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
            }

            item { SupportCard() }

            item { Heading(stringResource(R.string.about_sources)) }
            sources.forEach { s ->
                item { Entry(title = s.name, subtitle = s.url, body = stringResource(s.description)) }
            }

            item { Heading(stringResource(R.string.about_meta_sources)) }
            metaSources.forEach { s ->
                item { Entry(title = s.name, subtitle = s.url, body = stringResource(s.description)) }
            }

            item {
                Heading(stringResource(R.string.about_legal))
                Text(stringResource(R.string.about_legal_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_legal_covers), style = MaterialTheme.typography.bodyMedium)
            }

            item {
                Heading(stringResource(R.string.about_privacy))
                Text(stringResource(R.string.about_privacy_body), style = MaterialTheme.typography.bodyMedium)
            }

            item { Heading(stringResource(R.string.about_libraries)) }
            libraries.forEach { lib ->
                item {
                    Entry(
                        title = lib.name,
                        subtitle = "${lib.license} · ${lib.copyright}",
                        body = stringResource(lib.description),
                        onClick = { licenseToShow = lib.license to readAsset(context, lib.licenseFile) },
                    )
                }
            }

            item {
                Heading(stringResource(R.string.about_license))
                Text(stringResource(R.string.about_license_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { licenseToShow = "GNU GPL v3.0" to readAsset(context, "GPL-3.0.txt") }) {
                    Text(stringResource(R.string.about_view_license))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    licenseToShow?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { licenseToShow = null },
            title = { Text(title) },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { licenseToShow = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

/** Botón de Ko-fi con los colores del widget oficial ("Buy a small coffee", #00C424). */
@Composable
private fun SupportCard() {
    val uri = LocalUriHandler.current
    Heading(stringResource(R.string.about_support))
    Text(stringResource(R.string.about_support_body), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { runCatching { uri.openUri(AppInfo.KOFI_URL) } },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C424), contentColor = Color.White),
    ) {
        Icon(Icons.Filled.LocalCafe, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.kofi_button))
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Entry(title: String, subtitle: String, body: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun readAsset(context: android.content.Context, name: String): String =
    runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault(name)
