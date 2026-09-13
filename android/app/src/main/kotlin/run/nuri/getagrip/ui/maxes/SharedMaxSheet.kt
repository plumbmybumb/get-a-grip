// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.maxes

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.components.*
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.units.WeightUnits

/** The legacy shared benchmark remains explicit, separate from two individual hand records. */
@Composable
fun SharedMaxSheet(grip: GripSpec, onSaved: () -> Unit = {}, onClose: () -> Unit) {
    val templates = LocalTemplateStore.current
    val feed = LocalHistoryFeed.current
    val palette = LocalGripPalette.current
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val submission = remember { SubmissionState() }
    var kg by remember(grip.key) { mutableDoubleStateOf(templates.maxTable.exact(grip.key, Side.both) ?: 0.0) }
    var focused by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<TemplateStore.MaxSaveReceipt?>(null) }
    fun finish() { onSaved(); onClose() }
    fun save() {
        if (committed || submission.isRunning) return
        submission.launch(scope) {
            focus.clearFocus(force = true)
            withFrameNanos { }
            if (!kg.isFinite() || kg <= 0.0) return@launch
            failed = false
            val saved = templates.recordMaxesWithReceipt(listOf(TemplateStore.MaxSave(grip, Side.both, kg, MaxSource.manual)))
            if (saved == null) { failed = true; return@launch }
            committed = true
            feed.refresh()
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            if (saved.hasDetails) receipt = saved else finish()
        }
    }
    receipt?.let { MaxSaveReceiptScreen(it, onDone = ::finish); return }
    MaxesFlowScaffold(tr("Shared max"), onClose = onClose, closeEnabled = !submission.isRunning, actions = {
        TextButton(onClick = ::save, enabled = !committed && !submission.isRunning && (kg > 0 || focused),
            modifier = Modifier.testTag("maxShared.save")) { Text(tr("Save"), fontWeight = FontWeight.SemiBold) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readablePageWidth().verticalScroll(rememberScrollState())
            .imePadding().onFocusChanged { focused = it.hasFocus }.focusGroup()
            .padding(horizontal = Metrics.hPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(Metrics.spacing)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlyphTile(grip, 56.dp)
                Text(grip.displayName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            MaxesContentCard {
                Text(tr("Used when a hand has no max of its own, and for two-handed pulls."),
                    style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
                ValueRow(title = tr("Shared max"), value = WeightUnits.fromKg(kg), unit = WeightUnits.symbol,
                    range = WeightUnits.sliderRange(MaxEntryDraft.sliderRange), limit = WeightUnits.fromKg(MaxEntryDraft.limit),
                    step = 0.5, decimals = 1, caption = manualBandCaption(kg),
                    modifier = Modifier.testTag("maxShared.value")) { kg = WeightUnits.toKg(it) }
                Text(tr("Percentage targets follow the values you save. Earlier records stay in your history."),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                if (failed) Text(tr("Couldn’t save your maxes. Your changes are still here—please try again."),
                    style = MaterialTheme.typography.bodySmall, color = palette.alarm)
            }
        }
    }
}
