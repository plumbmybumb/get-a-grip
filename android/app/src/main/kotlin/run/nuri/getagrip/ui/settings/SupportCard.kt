// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.store.PlayStoreListing
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

@Composable
internal fun SupportCard(gauge: String, diagnostics: () -> String?) {
    val context = LocalContext.current
    val palette = LocalGripPalette.current
    var pendingDiagnostics by remember { mutableStateOf<String?>(null) }
    var fallback by remember { mutableStateOf<SupportDraft?>(null) }

    fun compose(bug: Boolean, report: String? = null) {
        val draft = supportDraft(context, bug, gauge, report)
        if (!draft.open(context)) fallback = draft
    }

    InstrumentSurface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsLabel(tr("Support"))
            SupportRow(tr("Report a bug"), Icons.Outlined.BugReport) {
                val report = diagnostics()?.takeIf { it.isNotBlank() }
                if (report == null) compose(bug = true) else pendingDiagnostics = report
            }
            SupportRow(tr("Request a feature"), Icons.Outlined.Lightbulb) { compose(bug = false) }
            RateRow()
        }
    }

    pendingDiagnostics?.let { report ->
        AlertDialog(
            onDismissRequest = { pendingDiagnostics = null },
            title = { Text(tr("Include gauge diagnostics?")) },
            text = { Text(tr("Recent connection events are added to your email. You can check them before sending.")) },
            confirmButton = { TextButton(onClick = { pendingDiagnostics = null; compose(true, report) }) {
                Text(tr("Include gauge diagnostics"))
            } },
            dismissButton = { TextButton(onClick = { pendingDiagnostics = null; compose(true) }) {
                Text(tr("Send without"))
            } },
        )
    }
    fallback?.let { draft ->
        AlertDialog(
            onDismissRequest = { fallback = null },
            title = { Text(tr("No email app available")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Copy your draft and email it to this address."))
                SelectionContainer { Text(SUPPORT_ADDRESS) }
            } },
            confirmButton = { TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(draft.subject, draft.copyText()))
                fallback = null
            }) { Text(tr("Copy email draft")) } },
            dismissButton = { TextButton(onClick = { fallback = null }) { Text(tr("Cancel")) } },
        )
    }
}

/// The permanent link to the Play listing, and the one place the ask can say WHY — the
/// one-time prompt after the fifth session says it once (see `ReviewRequestPolicy`); this
/// row says it for as long as anyone looks.
@Composable
private fun RateRow() {
    val context = LocalContext.current
    val spoken = tr("Rate on Google Play")
    Column {
        SupportRow(
            tr("Rate on Google Play"),
            Icons.Outlined.StarOutline,
            Modifier.semantics { contentDescription = spoken },
        ) { PlayStoreListing.open(context) }
        Text(
            tr("A rating helps other climbers find Get a Grip."),
            style = MaterialTheme.typography.bodySmall,
            color = LocalGripPalette.current.inkTertiary,
        )
    }
}

@Composable
private fun SupportRow(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalGripPalette.current.graphite)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
    }
}
