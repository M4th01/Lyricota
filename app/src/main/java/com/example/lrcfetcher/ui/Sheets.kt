package com.example.lrcfetcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.lrcfetcher.AppLanguage
import com.example.lrcfetcher.AppViewModel
import com.example.lrcfetcher.MetaScope
import com.example.lrcfetcher.R
import com.example.lrcfetcher.SaveTarget
import com.example.lrcfetcher.ThemeMode
import com.example.lrcfetcher.lyrics.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: AppViewModel, onLanguage: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
        ) {
            Section(stringResource(R.string.settings_providers))
            Hint(stringResource(R.string.settings_providers_hint))
            Spacer(Modifier.height(4.dp))
            vm.providerOrder.forEachIndexed { i, id ->
                ProviderRow(
                    id = id,
                    enabled = id !in vm.disabledProviders,
                    canUp = i > 0,
                    canDown = i < vm.providerOrder.lastIndex,
                    onToggle = { vm.setProviderEnabled(id, it) },
                    onMove = { vm.moveProvider(id, it) },
                )
            }

            Section(stringResource(R.string.settings_save_target))
            SaveTarget.entries.forEach { t -> RadioRow(t.label(), vm.saveTarget == t) { vm.updateSaveTarget(t) } }
            Hint(stringResource(R.string.target_hint))

            Section(stringResource(R.string.settings_lrc))
            SwitchRow(stringResource(R.string.setting_voices), stringResource(R.string.setting_voices_hint), vm.includeVoices, vm::updateVoices)
            SwitchRow(stringResource(R.string.setting_millis), stringResource(R.string.setting_millis_hint), vm.millis, vm::updateMillis)

            Section(stringResource(R.string.settings_theme))
            listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            ).forEach { (m, label) -> RadioRow(stringResource(label), vm.theme == m) { vm.updateTheme(m) } }

            Section(stringResource(R.string.settings_language))
            AppLanguage.entries.forEach { lang ->
                RadioRow(lang.label(), vm.language == lang) { if (vm.language != lang) onLanguage(lang) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProviderRow(
    id: ProviderId,
    enabled: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(id.label, style = MaterialTheme.typography.bodyLarge)
            Hint(
                stringResource(
                    when {
                        id.voices -> R.string.provider_voices
                        id.wordSync -> R.string.provider_word
                        else -> R.string.provider_line
                    },
                ),
            )
        }
        IconButton(onClick = { onMove(-1) }, enabled = canUp) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
        IconButton(onClick = { onMove(1) }, enabled = canDown) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
fun BatchDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var onlyMissing by remember { mutableStateOf(true) }
    val selectedCount = vm.selected.size
    var onlySelected by remember { mutableStateOf(selectedCount > 0) }
    val missing = vm.tracks.count { !it.hasLyrics }
    val settings = stringResource(R.string.batch_settings, vm.format.label(), vm.textMode.label()).let {
        if (vm.includeTranslation) stringResource(R.string.batch_with_translation, it) else it
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.batch_body, vm.saveTarget.label()), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.clickable { onlyMissing = !onlyMissing }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onlyMissing, onCheckedChange = { onlyMissing = it })
                    Text(stringResource(R.string.batch_only_missing, missing))
                }
                if (selectedCount > 0) {
                    Row(Modifier.clickable { onlySelected = !onlySelected }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = onlySelected, onCheckedChange = { onlySelected = it })
                        Text(stringResource(R.string.batch_selected, selectedCount))
                    }
                }
                Hint(settings)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.startBatch(onlyMissing, onlySelected); onDismiss() }) { Text(stringResource(R.string.start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Lote de metadatos: seleccionadas / todas / sin metadatos. */
@Composable
fun MetadataBatchDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val selectedCount = vm.selected.size
    val missing = vm.tracks.count { it.metadataIncomplete }
    var scope by remember { mutableStateOf(if (selectedCount > 0) MetaScope.SELECTED else MetaScope.MISSING) }
    var overwrite by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meta_batch_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.meta_batch_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                if (selectedCount > 0) RadioRow(stringResource(R.string.scope_selected, selectedCount), scope == MetaScope.SELECTED) { scope = MetaScope.SELECTED }
                RadioRow(stringResource(R.string.scope_missing, missing), scope == MetaScope.MISSING) { scope = MetaScope.MISSING }
                RadioRow(stringResource(R.string.scope_all, vm.tracks.size), scope == MetaScope.ALL) { scope = MetaScope.ALL }
                Row(Modifier.clickable { overwrite = !overwrite }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                    Text(stringResource(R.string.meta_overwrite), style = MaterialTheme.typography.bodyMedium)
                }
                Hint(stringResource(R.string.meta_batch_note))
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.startMetadataBatch(scope, overwrite); onDismiss() }) { Text(stringResource(R.string.start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
