package com.example.lrcfetcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.lrcfetcher.AppInfo
import com.example.lrcfetcher.BuildConfig
import com.example.lrcfetcher.R
import java.net.URLEncoder

/**
 * Tipo de comentario → plantilla de GitHub (.github/ISSUE_TEMPLATE) y el campo que se rellena.
 */
enum class FeedbackType(@StringRes val label: Int, val template: String, val field: String, val tag: String) {
    BUG(R.string.feedback_bug, "bug_report.yml", "what", "Bug"),
    IDEA(R.string.feedback_idea, "feature_request.yml", "idea", "Idea"),
    PRAISE(R.string.feedback_praise, "feedback.yml", "message", "Feedback"),
}

/**
 * "Deja tu comentario". No se envía nada desde la app: se abre GitHub (o el correo) con el
 * texto ya escrito y el usuario lo revisa y lo envía él mismo.
 */
@Composable
fun FeedbackDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(FeedbackType.BUG) }
    var text by remember { mutableStateOf("") }
    var includeDevice by remember { mutableStateOf(true) }
    val device = remember { deviceInfo() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feedback_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FeedbackType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(stringResource(t.label)) })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            stringResource(
                                when (type) {
                                    FeedbackType.BUG -> R.string.feedback_hint_bug
                                    FeedbackType.IDEA -> R.string.feedback_hint_idea
                                    FeedbackType.PRAISE -> R.string.feedback_hint_praise
                                },
                            ),
                        )
                    },
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                Row(Modifier.clickable { includeDevice = !includeDevice }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeDevice, onCheckedChange = { includeDevice = it })
                    Column {
                        Text(stringResource(R.string.feedback_device), style = MaterialTheme.typography.bodyMedium)
                        Text(device, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(R.string.feedback_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                if (AppInfo.FEEDBACK_EMAIL.isNotBlank()) {
                    TextButton(onClick = { sendEmail(context, type, text, device.takeIf { includeDevice }); onDismiss() }, enabled = text.isNotBlank()) {
                        Text(stringResource(R.string.feedback_email))
                    }
                }
                TextButton(onClick = { openGitHub(context, type, text, device.takeIf { includeDevice }); onDismiss() }, enabled = text.isNotBlank()) {
                    Text(stringResource(R.string.feedback_github))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun deviceInfo(): String =
    "Lyricota ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
        "${Build.MANUFACTURER} ${Build.MODEL}"

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Página "nueva incidencia" de GitHub con la plantilla y los campos ya rellenos. */
internal fun feedbackUrl(type: FeedbackType, text: String, device: String?): String {
    val title = "[${type.tag}] " + text.lineSequence().first().take(70)
    val params = buildList {
        add("template=" + enc(type.template))
        add("title=" + enc(title))
        add(type.field + "=" + enc(text))
        if (device != null) add("device=" + enc(device))
    }
    return "${AppInfo.REPO_URL}/issues/new?" + params.joinToString("&")
}

private fun openGitHub(context: Context, type: FeedbackType, text: String, device: String?) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(feedbackUrl(type, text, device))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun sendEmail(context: Context, type: FeedbackType, text: String, device: String?) {
    val body = if (device != null) "$text\n\n—\n$device" else text
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(AppInfo.FEEDBACK_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, "Lyricota · ${type.tag}")
        .putExtra(Intent.EXTRA_TEXT, body)
    runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
