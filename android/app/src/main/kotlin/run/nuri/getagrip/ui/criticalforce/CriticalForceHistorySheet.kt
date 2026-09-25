// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.criticalforce

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.launch
import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import run.nuri.getagrip.ui.components.SwipeActionRow
import run.nuri.getagrip.ui.components.UndoSnackbar
import run.nuri.getagrip.ui.components.UndoSnackbarEffect
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.units.WeightUnits

/// Every critical force test on one grip, newest first, every hand. Delete is the house
/// swipe with ten seconds of Undo, and a row opens the test as it was saved. Opened from
/// the grip's card on Benchmarks.
///
/// TRANSLATION NOTE: iOS presents a `NavigationStack` in a sheet; here a `ModalBottomSheet`
/// swaps between the list and a saved test, and the system back gesture returns to the
/// list. Rows take History's list style (`SessionRow`'s type ramp, hairline dividers), with
/// no artwork, so the divider runs from the text.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalForceHistorySheet(gripKey: String, title: String, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbar = remember { SnackbarHostState() }
    val rows = remember(templates.criticalForceRecords, gripKey) {
        templates.criticalForceRecords.filter { it.gripKey == gripKey }.sortedByDescending { it.recordedAt }
    }
    var openID by remember { mutableStateOf<UUID?>(null) }
    val open = rows.firstOrNull { it.id == openID }

    // Leaving the sheet by ANY route withdraws the Undo: it belongs to this list.
    val latestTemplates by rememberUpdatedState(templates)
    DisposableEffect(Unit) { onDispose { latestTemplates.dismissCriticalForceUndo() } }

    fun close() {
        scope.launch {
            try { sheetState.hide() } finally { onClose() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
        modifier = Modifier.testTag("maxes.cf.historySheet"),
    ) {
        // The sheet is its own window: the tab bar's inset does not reach into it.
        CompositionLocalProvider(LocalFloatingTabBarInset provides 0.dp) {
            Box {
                if (open != null) {
                    BackHandler { openID = null }
                    SavedTest(open) { openID = null }
                } else {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                title,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = ::close) {
                                Text(tr("Done"), color = palette.graphite, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        LazyColumn(
                            Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = Metrics.spacing),
                        ) {
                            items(rows, key = { it.id }) { record ->
                                SwipeActionRow(
                                    onDelete = { scope.launch { templates.deleteCriticalForce(record) } },
                                ) {
                                    Column {
                                        TestRow(record) { openID = record.id }
                                        HorizontalDivider(Modifier.padding(start = Metrics.hPadding),
                                            color = palette.inkTertiary.copy(alpha = 0.25f))
                                    }
                                }
                            }
                        }
                    }
                }
                UndoSnackbar(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    UndoSnackbarEffect(
        hostState = snackbar,
        deleted = templates.lastDeletedCriticalForce,
        message = tr("Test deleted"),
        onUndo = { scope.launch { templates.undoDeleteCriticalForce() } },
        onExpired = { templates.dismissCriticalForceUndo() },
    )
}

/// "Both hands", or the hand's own name.
internal fun handName(side: Side): String =
    if (side == Side.both) L10n.tr("Both hands") else side.displayName

@Composable
private fun TestRow(record: CriticalForceRecordEntity, onOpen: () -> Unit) {
    val palette = LocalGripPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = Metrics.hPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                DATE_TIME.format(record.recordedAt.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(handName(record.side), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium, color = palette.inkSecondary)
            Text(rowDetail(record), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkTertiary)
        }
        Text(
            WeightUnits.text(record.criticalForceKg),
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
            maxLines = 1,
        )
    }
}

private fun rowDetail(record: CriticalForceRecordEntity): String =
    record.percentOfMax?.let { L10n.tr("%d %% of max", roundedPercent(it)) }
        ?: L10n.tr("%d pulls", record.repsRun)

/// A saved test as it was saved, titled with its date, the hand beneath.
@Composable
private fun SavedTest(record: CriticalForceRecordEntity, onBack: () -> Unit) {
    val palette = LocalGripPalette.current
    val summary = remember(record) { CriticalForceSummary.of(record) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"), tint = palette.inkPrimary)
            }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                Text(
                    DATE.format(record.recordedAt.atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.inkPrimary,
                )
                Text(handName(record.side), style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
            }
        }
        CriticalForceResultView(
            summary,
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding, vertical = 16.dp),
        )
    }
}

/// Resolved against the locale in force when they format, never frozen at class load.
private val DATE_TIME: DateTimeFormatter
    get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val DATE: DateTimeFormatter
    get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
