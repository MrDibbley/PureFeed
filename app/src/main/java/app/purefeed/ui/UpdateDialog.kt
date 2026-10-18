package app.purefeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.purefeed.R
import app.purefeed.update.Release
import app.purefeed.update.UpdateState
import java.util.Locale

/** Actions the update dialog can take (UpdateController plus opening the release page). */
class UpdateActions(
    val update: (Release) -> Unit,
    val later: () -> Unit,
    val skip: (Release) -> Unit,
    val dismiss: () -> Unit,
    val openInstallPermission: () -> Unit,
    val openReleasePage: (Release) -> Unit,
)

@Composable
fun UpdateDialog(state: UpdateState, installedVersion: String, actions: UpdateActions) {
    when (state) {
        UpdateState.Idle -> Unit

        UpdateState.Checking -> AlertDialog(
            onDismissRequest = actions.dismiss,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.update_checking))
                }
            },
            confirmButton = { TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.update_cancel)) } },
        )

        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = actions.dismiss,
            text = { Text(stringResource(R.string.update_up_to_date, state.version)) },
            confirmButton = { TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.ok)) } },
        )

        is UpdateState.Available -> AlertDialog(
            onDismissRequest = actions.later,
            title = { Text(stringResource(R.string.update_available_title, state.release.version)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.update_available_body, installedVersion, megabytes(state.release.apkSize)))
                    if (state.release.notes.isNotBlank()) {
                        Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleSmall)
                        Text(
                            plainNotes(state.release.notes),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    TextButton(onClick = { actions.skip(state.release) }) { Text(stringResource(R.string.update_skip)) }
                }
            },
            confirmButton = { TextButton(onClick = { actions.update(state.release) }) { Text(stringResource(R.string.update_install)) } },
            dismissButton = { TextButton(onClick = actions.later) { Text(stringResource(R.string.update_later)) } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.update_downloading, state.release.version)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_downloading_body), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.update_cancel)) } },
        )

        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = actions.dismiss,
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_body)) },
            confirmButton = { TextButton(onClick = actions.openInstallPermission) { Text(stringResource(R.string.update_open_settings)) } },
            dismissButton = { TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.update_later)) } },
        )

        is UpdateState.CantSelfUpdate -> AlertDialog(
            onDismissRequest = actions.dismiss,
            title = { Text(stringResource(R.string.update_cant_title)) },
            text = { Text(stringResource(R.string.update_cant_body, state.release.version)) },
            confirmButton = { TextButton(onClick = { actions.openReleasePage(state.release); actions.dismiss() }) { Text(stringResource(R.string.update_open_page)) } },
            dismissButton = { TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.close)) } },
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = actions.dismiss,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.ok)) } },
        )
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.UK, "%.1f MB", bytes / 1_048_576.0)

/** Release notes are Markdown; show them as readable plain text, without the hash section. */
private fun plainNotes(md: String) = md.substringBefore("### Verify")
    .replace(Regex("```[\\s\\S]*?```"), "")          // drop code blocks (hashes)
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("(?m)^>\\s*"), "")
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
    .replace("`", "")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()
