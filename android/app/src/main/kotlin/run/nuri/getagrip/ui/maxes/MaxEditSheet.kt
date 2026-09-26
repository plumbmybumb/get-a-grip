// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.maxes

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

@Composable
fun MaxEditSheet(grip: GripSpec, onSaved: () -> Unit = {}, onClose: () -> Unit) {
    val templates = LocalTemplateStore.current
    val feed = LocalHistoryFeed.current
    val palette = LocalGripPalette.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    var draft by remember(grip.key) { mutableStateOf(MaxEditDraft(
        leftKg = templates.maxTable.exact(grip.key, Side.left),
        rightKg = templates.maxTable.exact(grip.key, Side.right))) }
    var failed by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var editingShared by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<TemplateStore.MaxSaveReceipt?>(null) }
    val submission = remember { SubmissionState() }
    val current = templates.maxTable
    LaunchedEffect(current, grip.key) {
        draft = draft.copy().also { it.rebase(current.exact(grip.key, Side.left), current.exact(grip.key, Side.right)) }
    }

    fun finish() { onSaved(); onClose() }
    fun save() {
        if (committed || submission.isRunning) return
        submission.launch(scope) {
            focus.clearFocus(force = true)
            withFrameNanos { }
            if (!draft.canSave) return@launch
            failed = false
            val saved = templates.recordMaxesWithReceipt(draft.changes.map {
                TemplateStore.MaxSave(grip, it.side, it.kg, MaxSource.manual)
            })
            if (saved == null) { failed = true; return@launch }
            committed = true
            feed.refresh()
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            if (saved.hasDetails) receipt = saved else finish()
        }
    }

    receipt?.let { MaxSaveReceiptScreen(it, onDone = ::finish); return }
    if (editingShared) {
        SharedMaxSheet(grip, onClose = { editingShared = false })
        return
    }
    if (history) {
        MaxesFlowScaffold(tr("Earlier records"), onClose = { history = false }) { padding ->
            CompositionLocalProvider(LocalFloatingTabBarInset provides 0.dp) {
                MaxesListScreen(onAddMax = {}, grip = grip, modifier = Modifier.padding(padding), feed = feed)
            }
        }
        return
    }
    MaxesFlowScaffold(tr("Edit maxes"), onClose = onClose, closeEnabled = !submission.isRunning, actions = {
        TextButton(onClick = ::save, enabled = !committed && !submission.isRunning && (draft.canSave || hasInputFocus),
            modifier = Modifier.testTag("maxEdit.save")) { Text(tr("Save"), fontWeight = FontWeight.SemiBold) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readablePageWidth()
            .verticalScroll(rememberScrollState()).imePadding()
            .onFocusChanged { hasInputFocus = it.hasFocus }.focusGroup()
            .padding(horizontal = Metrics.hPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.spacing)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlyphTile(grip, 56.dp)
                Text(grip.displayName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = palette.inkPrimary, modifier = Modifier.weight(1f))
            }
            MaxesContentCard {
                listOf(Side.left, Side.right).forEachIndexed { index, side ->
                    if (index > 0) HorizontalDivider()
                    val kg = if (side == Side.left) draft.leftKg else draft.rightKg
                    ValueRow(title = tr(if (side == Side.left) "Left hand" else "Right hand"),
                        value = WeightUnits.fromKg(kg), unit = WeightUnits.symbol,
                        range = WeightUnits.sliderRange(0.0..100.0), limit = WeightUnits.fromKg(0.0..250.0),
                        step = 0.5, decimals = 1, caption = manualBandCaption(kg),
                        modifier = Modifier.testTag("maxEdit.value.${side.rawValue}")) { displayValue ->
                        draft = draft.copy().also {
                            if (side == Side.left) it.leftKg = WeightUnits.toKg(displayValue)
                            else it.rightKg = WeightUnits.toKg(displayValue)
                        }
                    }
                    if (draft.originalKg(side) == kg) {
                        val selected = draft.recordsAnotherTest(side)
                        TextButton(onClick = {
                            draft = draft.copy().also { it.setRecordsAnotherTest(!selected, side) }
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("maxEdit.retest.${side.rawValue}").semantics { this.selected = selected }) {
                            Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Record another test"), modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (draft.hasInvalidChanges) Text(tr("Enter a max above zero. To remove a record, open Earlier records."),
                    style = MaterialTheme.typography.bodySmall, color = palette.alarm)
                if (failed) Text(tr("Couldn’t save your maxes. Your changes are still here—please try again."),
                    style = MaterialTheme.typography.bodySmall, color = palette.alarm)
            }
            templates.maxTable.exact(grip.key, Side.both)?.let { shared ->
                MaxesContentCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(tr("Shared max"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(WeightUnits.text(shared), fontWeight = FontWeight.SemiBold)
                    }
                    Text(tr("Used when a hand has no max of its own, and for two-handed pulls."),
                        style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                    TextButton(onClick = { focus.clearFocus(); editingShared = true }, modifier = Modifier.testTag("maxEdit.shared")) {
                        Text(tr("Edit shared max"))
                    }
                }
            }
            SecondaryButton(tr("Earlier records"), modifier = Modifier.fillMaxWidth().testTag("maxEdit.history")) {
                focus.clearFocus(); history = true
            }
        }
    }
}

internal fun manualBandCaption(kg: Double): String? {
    if (kg <= 0) return L10n.tr("Not set. Enter a value to save a max for this hand.")
    val band = PlanMath.suggestedBand(kg) ?: return null
    return WeightUnits.tr("20–30 %% of that is %s–%s kg", WeightUnits.number(band.start), WeightUnits.number(band.endInclusive))
}
