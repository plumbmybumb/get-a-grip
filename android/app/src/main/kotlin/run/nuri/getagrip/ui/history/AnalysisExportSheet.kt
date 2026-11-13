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

/** What to export, frozen at the tap. `assemble` turns the captured rows into the export's
 * input — a pass over every log's blobs — so it and the formatting both run on a background
 * dispatcher once the sheet is up, never during composition or on the UI thread. */
class AnalysisExportRequest(val isWorkout: Boolean = false, val assemble: () -> AnalysisExport.Input)

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
    // The sheet is up the instant it is asked for; the rows are assembled behind it.
    val input by produceState<AnalysisExport.Input?>(null, request) {
        value = withContext(Dispatchers.Default) { request.assemble() }
    }
    val document by produceState<AnalysisExport.CSVDocument?>(null, input, selected, detail) {
        value = null
        val ready = input ?: return@produceState
        value = withContext(Dispatchers.Default) { AnalysisExport.csv(ready, selected, detail) }
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
                input?.sessions?.firstOrNull()?.let {
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
            Text(tr("A CSV for your AI assistant or spreadsheet. Summary groups pulls by set and hand."),
                style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
            val ready = document
            if (ready == null) {
                Text(tr("Preparing export…"), color = palette.inkSecondary)
                CircularProgressIndicator()
            } else if (ready.isEmpty) {
                Text(tr("Nothing to export in this range."), color = palette.inkSecondary)
            } else {
                Text(if (ready.criticalForceCount > 0) {
                        tr("Workouts: %d · Pulls: %d · Maxes: %d · Critical force tests: %d",
                            ready.sessionCount, ready.pullCount, ready.maxCount, ready.criticalForceCount)
                    } else {
                        tr("Workouts: %d · Pulls: %d · Maxes: %d", ready.sessionCount, ready.pullCount, ready.maxCount)
                    },
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                Text("CSV · ${android.text.format.Formatter.formatShortFileSize(context, ready.byteCount.toLong())}",
                    style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
                Text(tr("Column names and the data guide are in English."),
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
                if (shareFailed) Text(tr("Can't share files on this build. Use Copy instead."),
                    style = MaterialTheme.typography.bodySmall, color = palette.alarm)
            }
        }
    }
}

/** Each share gets a directory of its own, so the file a receiver was handed is never
 * rewritten under it. The directories before it are removed first — see `ShareCache`. */
private suspend fun shareDocument(context: Context, document: AnalysisExport.CSVDocument): Boolean = runCatching {
    val uri = withContext(Dispatchers.IO) {
        ShareCache.purge(ShareCache.directory(context))
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

/**
 * `<cache>/shared`, the one folder the `FileProvider` hands out. What lands in it is a COPY
 * of somebody's training history, written for one share — and until now never removed, so
 * every export ever made sat there, each in its own directory, until the system chose to
 * reclaim the cache.
 *
 * **Emptied before each new export is written**, so at most one share's files exist at a
 * time. A receiver that already has the old file open keeps reading it (an unlinked file
 * lives as long as its descriptor); one that was handed the link and has not opened it yet
 * loses it, which is the price of not keeping every copy.
 *
 * **At launch only what is older than a day.** Reading a shared `content://` link is itself
 * what starts this process when it is not running, so emptying the folder at every start
 * would race the very read that woke the app.
 */
internal object ShareCache {
    const val staleAfterMillis: Long = 24L * 60 * 60 * 1000

    fun directory(context: Context): File = File(context.cacheDir, SHARE_DIR)

    /** Everything under [directory], or with [olderThanMillis] only what was last written
     * longer ago than that. Never throws: a file that will not go is left for next time. */
    fun purge(
        directory: File,
        olderThanMillis: Long? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            directory.listFiles()?.forEach { entry ->
                if (olderThanMillis == null || now - newestWrite(entry) > olderThanMillis) {
                    entry.deleteRecursively()
                }
            }
        }
    }

    /** An export's age is its newest FILE's. A directory's own timestamp says when an
     * entry was last added or removed, which is not when anything in it was written; it is
     * the answer only for a directory with nothing in it. */
    private fun newestWrite(entry: File): Long =
        entry.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() }
            ?: entry.lastModified()
}
