// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.delay
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.AnalysisExport
import java.util.UUID

/** A frozen snapshot; formatting never runs during composition or on the UI thread. */
data class AnalysisExportRequest(val input: AnalysisExport.Input, val isWorkout: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisExportSheet(request: AnalysisExportRequest, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var range by remember { mutableStateOf(AnalysisExport.CSVScope.recent) }
    var detail by remember { mutableStateOf(AnalysisExport.CSVDetail.summary) }
    val selected = if (request.isWorkout) AnalysisExport.CSVScope.workout else range
    val document by produceState<AnalysisExport.CSVDocument?>(null, request, selected, detail) {
        value = null
        value = withContext(Dispatchers.Default) { AnalysisExport.csv(request.input, selected, detail) }
    }
    var justCopied by remember(selected, detail) { mutableStateOf(false) }
    var shareFailed by remember(selected, detail) { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) { if (justCopied) { delay(2_000); justCopied = false } }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = palette.field,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = Metrics.hPadding).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr(if (request.isWorkout) "Export workout" else "Export for analysis"),
                style = MaterialTheme.typography.titleLarge, color = palette.inkPrimary)
            if (request.isWorkout) {
                request.input.sessions.firstOrNull()?.let {
                    Text(it.routineName, style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary)
                    Text(AnalysisExport.isoDay(it.day), style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                }
            } else {
                val chipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    labelColor = palette.inkSecondary,
                    selectedContainerColor = palette.graphite.copy(alpha = 0.12f),
                    selectedLabelColor = palette.inkPrimary,
                )
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(colors = chipColors, shape = androidx.compose.foundation.shape.CircleShape, selected = range == AnalysisExport.CSVScope.recent,
                        onClick = { range = AnalysisExport.CSVScope.recent }, label = { Text(tr("Last 8 weeks")) })
                    FilterChip(colors = chipColors, shape = androidx.compose.foundation.shape.CircleShape, selected = range == AnalysisExport.CSVScope.all,
                        onClick = { range = AnalysisExport.CSVScope.all }, label = { Text(tr("All history")) })
                }
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisExport.CSVDetail.entries.forEach { choice ->
                    FilterChip(selected = detail == choice, onClick = { detail = choice },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            labelColor = palette.inkSecondary,
                            selectedContainerColor = palette.graphite.copy(alpha = 0.12f),
                            selectedLabelColor = palette.inkPrimary),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        label = { Text(tr(if (choice == AnalysisExport.CSVDetail.summary) "Summary" else "Every pull")) })
                }
            }
            Text(tr("A compact CSV for your AI assistant or spreadsheet. Summary groups pulls by set and hand. Choose Every pull for individual measurements and timing."),
                style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
            val ready = document
            if (ready == null) {
                Text(tr("Preparing export…"), color = palette.inkSecondary)
                CircularProgressIndicator()
            } else if (ready.isEmpty) {
                Text(tr("Nothing to export in this range."), color = palette.inkSecondary)
            } else {
                Text(tr("Workouts: %d · Pulls: %d · Maxes: %d", ready.sessionCount, ready.pullCount, ready.maxCount),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                Text("CSV · ${android.text.format.Formatter.formatShortFileSize(context, ready.byteCount.toLong())}",
                    style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
                Text(tr("Column names and the data guide stay in English. Your notes stay as written."),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
                PrimaryButton(title = tr(if (sharing) "Preparing export…" else "Share CSV"), icon = Icons.Outlined.Share) {
                    if (!sharing) coroutineScope.launch {
                        sharing = true
                        shareFailed = !shareDocument(context, ready)
                        sharing = false
                    }
                }
                SecondaryButton(title = tr(if (justCopied) "Copied" else "Copy"),
                    icon = if (justCopied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    modifier = Modifier.fillMaxWidth()) {
                    clipboard.nativeClipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        context.tr("Get a Grip — training export"), ready.text))
                    justCopied = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                if (shareFailed) Text(tr("Sharing a file isn't available on this build — use Copy, and paste the document where you need it."),
                    style = MaterialTheme.typography.bodySmall, color = palette.alarm)
            }
        }
    }
}

/** Each share is immutable so another export cannot replace an attachment in flight. */
private suspend fun shareDocument(context: Context, document: AnalysisExport.CSVDocument): Boolean = runCatching {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "$SHARE_DIR/${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, document.filename)
        file.writeText(document.text)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, context.tr("Get a Grip — training export"))
        clipData = android.content.ClipData.newRawUri(document.filename, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
    true
}.getOrDefault(false)

internal const val SHARE_DIR = "shared"
