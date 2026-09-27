package com.example.lrcfetcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.lrcfetcher.AppViewModel
import com.example.lrcfetcher.BuildConfig
import com.example.lrcfetcher.R
import com.example.lrcfetcher.update.UpdateInfo

/** Aviso de versión nueva en GitHub: descargar e instalar, más tarde u omitir esa versión. */
@Composable
fun UpdateDialog(vm: AppViewModel, info: UpdateInfo) {
    val uri = LocalUriHandler.current
    val progress = vm.updateProgress
    AlertDialog(
        onDismissRequest = { if (progress == null) vm.dismissUpdate() },
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column {
                Text(stringResource(R.string.update_body, info.version, BuildConfig.VERSION_NAME))
                if (info.apkSize > 0) {
                    Text(
                        stringResource(R.string.update_size, "%.1f".format(info.apkSize / 1_048_576.0)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val notes = cleanNotes(info.notes)
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.update_downloading, (progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        stringResource(R.string.update_install_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = vm::skipUpdate) { Text(stringResource(R.string.update_skip)) }
                }
            }
        },
        confirmButton = {
            when {
                progress != null -> {}
                info.apkUrl == null -> TextButton(onClick = { runCatching { uri.openUri(info.pageUrl) }; vm.dismissUpdate() }) {
                    Text(stringResource(R.string.update_open_page))
                }
                else -> TextButton(onClick = vm::downloadUpdate) {
                    Text(stringResource(if (vm.updateReady) R.string.update_install else R.string.update_download))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissUpdate) {
                Text(stringResource(if (progress != null) R.string.cancel else R.string.update_later))
            }
        },
    )
}

/** Notas de la Release en texto plano (sin marcas de Markdown). */
private fun cleanNotes(md: String): String = md.lines().joinToString("\n") { line ->
    line.trimEnd()
        .replace(Regex("""^#{1,6}\s*"""), "")
        .replace(Regex("""^\s*[-*]\s+"""), "• ")
        .replace("**", "")
        .replace("`", "")
}.replace(Regex("\n{3,}"), "\n\n").trim()
