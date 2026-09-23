// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import run.nuri.getagrip.GetAGripApplication
import run.nuri.getagrip.runner.FinishedSessionDraft
import run.nuri.getagrip.runner.UnsavedSessionRecovery
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/// **"Unsaved session from …" — Save or Discard.** A session that finished and was never
/// saved (the process was reclaimed behind its summary) is offered once, on the next launch
/// that is not already running one. Save records it through the ordinary path with no effort
/// reading; Discard throws it away as the summary's Discard would. The decisions live in
/// `UnsavedSessionRecovery`; this only asks.
@Composable
fun UnsavedSessionPrompt() {
    val app = LocalContext.current.applicationContext as? GetAGripApplication ?: return
    val templates = LocalTemplateStore.current
    val feed = LocalHistoryFeed.current
    val palette = LocalGripPalette.current
    val recovery = remember(app, templates) { UnsavedSessionRecovery(app.finishedSessionDrafts, templates) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<FinishedSessionDraft?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(recovery) { draft = recovery.pending() }

    val pending = draft ?: return
    val finished = remember(pending.finishedAt) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(pending.finishedAt.atZone(ZoneId.systemDefault()))
    }
    AlertDialog(
        // A decision about a workout, so it is not dismissed by a stray tap outside it.
        onDismissRequest = {},
        title = { Text(tr("Unsaved session from %s", finished)) },
        text = { Text(tr("This session finished, but the app closed before it was saved.")) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    if (recovery.save(pending)) {
                        feed.refresh()
                        draft = null
                    }
                    busy = false
                }
            }) { Text(tr("Save")) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = {
                recovery.discard()
                draft = null
            }) { Text(tr("Discard")) }
        },
        containerColor = palette.card,
    )
}
