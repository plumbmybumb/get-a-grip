// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineSummary
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.EdgeMark
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.tint
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.units.WeightUnits

/** A read-only preparation view. Counts/timing fold sets, never an expanded rep array. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineOverviewSheet(
    routine: SessionTemplateEntity,
    summary: RoutineSummary,
    onClose: () -> Unit,
    onEdit: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val plan = remember(routine) { routine.plan.executable }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }

    fun dismiss(edit: Boolean) {
        if (leaving) return
        leaving = true
        scope.launch {
            try {
                sheetState.hide()
                if (edit) onEdit() else onClose()
            } finally {
                // A gesture can interrupt the sheet animation; leave its actions
                // usable if the sheet remains on screen after that cancellation.
                leaving = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
        modifier = Modifier.testTag("routineOverview"),
    ) {
        // Edit remains reachable while inspecting the later sets. It replaces this
        // sheet with the same builder used by the routine's options menu.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { dismiss(false) }, enabled = !leaving) {
                Icon(Icons.Outlined.Close, contentDescription = tr("Close"), tint = palette.inkSecondary)
            }
            Text(
                tr("Routine overview"),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = palette.inkPrimary,
            )
            TextButton(
                onClick = { dismiss(true) }, enabled = !leaving,
                modifier = Modifier.testTag("routineOverview.edit"),
            ) {
                Text(tr("Edit routine"), color = palette.graphite)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Metrics.hPadding, end = Metrics.hPadding,
                top = 12.dp, bottom = Metrics.spacing,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("identity") {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EdgeMark(
                            fingers = summary.signatureFingers ?: FingerSet.four,
                            barWidth = 8.dp,
                            rungTint = PlanMath.IntensityBand.band(summary.peakIntensity).tint(palette),
                        )
                        Text(
                            routine.name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold, color = palette.inkPrimary,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CapsLabel(tr("Estimated duration"))
                        Text(
                            PlanMath.durationText(PlanMath.totalSeconds(plan)),
                            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Light, color = palette.inkPrimary,
                            modifier = Modifier.testTag("routineOverview.duration"),
                        )
                        Text(
                            "${tr("%d %s", plan.sets.size, tr(if (plan.sets.size == 1) "set" else "sets"))} · " +
                                overviewTotalPullCount(PlanMath.totalReps(plan)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.inkSecondary,
                        )
                    }
                    Text(
                        overviewHandOrder(plan.handMode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary,
                    )
                    if (plan.sets.isNotEmpty() && plan.leadInSeconds > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = palette.inkTertiary)
                            Text(
                                "${tr("Lead-in before each set")} · ${PlanMath.durationText(plan.leadInSeconds)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkSecondary,
                            )
                        }
                    }
                    HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.15f))
                }
            }
            itemsIndexed(plan.sets, key = { index, _ -> index }) { index, set ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverviewSet(set, plan, index)
                    if (index < plan.sets.lastIndex && plan.setBreakSeconds > 0) {
                        Text(
                            tr("%s between sets", PlanMath.durationText(plan.setBreakSeconds)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkTertiary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewSet(set: SetPlan, plan: SessionPlan, index: Int) {
    val palette = LocalGripPalette.current
    InstrumentSurface(
        shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card,
        modifier = Modifier.fillMaxWidth().testTag("routineOverview.set.$index"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CapsLabel(tr("Set %d of %d", index + 1, plan.sets.size), Modifier.weight(1f))
                Text(
                    PlanMath.clockText(PlanMath.setSeconds(set, plan)),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = palette.inkTertiary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                FingerGlyph(
                    fingers = set.grip.fingers, position = set.grip.position,
                    dot = 9.dp, gap = 3.dp, modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    set.grip.line, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = palette.inkPrimary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(overviewPullCount(set, plan), style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkPrimary)
                Text(
                    buildList {
                        add(tr("%s hold", PlanMath.durationText(PlanMath.hold(set, plan))))
                        if (PlanMath.repCount(set, plan.handMode) > 1) {
                            add(tr("%s rest", PlanMath.durationText(PlanMath.rest(set, plan))))
                        }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary,
                )
                overviewTarget(set, plan)?.let { target ->
                    Text(target, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
                }
                if (set.note.isNotBlank()) {
                    Text(set.note, style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

internal fun overviewHandOrder(mode: HandMode): String = when (mode) {
    HandMode.alternateEachRep -> L10n.tr("Left, right, left, right — swapping hands every pull.")
    HandMode.alternateEachSet -> L10n.tr("Left hand first, then right, within each set.")
    HandMode.bothHands -> L10n.tr("One pull with both hands on the edge.")
}

internal fun overviewPullCount(set: SetPlan, plan: SessionPlan): String {
    val total = PlanMath.repCount(set, plan.handMode)
    return if (plan.handMode.sideCount > 1) {
        L10n.tr("%d per side · %d pulls total", set.repsPerSide, total)
    } else overviewTotalPullCount(total)
}

private fun overviewTotalPullCount(total: Int): String =
    if (total == 1) L10n.tr("1 pull total") else L10n.tr("%d pulls total", total)

/** Show the prescription itself. No max lookup or newly calculated weight is needed. */
internal fun overviewTarget(set: SetPlan, plan: SessionPlan): String? {
    set.targetBand?.let { band ->
        return WeightUnits.tr("%s–%s kg target", WeightUnits.number(band.start), WeightUnits.number(band.endInclusive))
    }
    val band = PlanMath.targetPercent(set, plan) ?: return null
    val formatter = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
        isGroupingUsed = false
    }
    val lower = formatter.format(band.start * 100)
    val upper = formatter.format(band.endInclusive * 100)
    val amount = if (lower == upper) L10n.tr("%s %% of max", lower)
    else L10n.tr("%s–%s %% of max", lower, upper)
    return L10n.tr("Target: %s", amount)
}
