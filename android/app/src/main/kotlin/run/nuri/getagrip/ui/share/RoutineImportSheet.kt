// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.share

import run.nuri.getagrip.ui.units.WeightUnits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RoutineSummary
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.EdgeMark
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.tint
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.today.PlanRowFit

/// The other side of a QR code: somebody else's routine, read out in full, before it is yours.
///
/// A PREVIEW, not an editor: a routine you have not accepted is not yours to correct. Once it
/// lands, its card opens the builder on its own plan row.
///
/// **Percentage targets are NOT translated.** They resolve against the READER's maxes, per
/// hand, at session start — the reason the app prescribes fractions, so one code suits two
/// very different pairs of fingers. The footnotes cover where that is not the whole story.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineImportSheet(incoming: RoutineDraft, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Normalized HERE so the preview shows what will land (default name, emptied sets dropped,
    // inheritance consolidated). `normalized` is idempotent, so the store's pass costs nothing.
    val draft = remember(incoming) { incoming.normalized }
    // Folded ONCE: `RoutineSummary.previewing` walks the reps three times and mints an id, so a
    // computed read would re-fold a fifty-set routine per scroll frame and change identity.
    val summary = remember(draft) { RoutineSummary.previewing(draft) }
    val plan = draft.plan
    /// `executable`, like every fold: a set with no pulls is an emptied row, not a rest.
    val sets = remember(plan) { plan.executable.sets }

    /// The name this routine will actually LAND under (the store's `uniqueName`): two people keeping
    /// the default name is common, and "Daily no-hangs" becoming "Daily no-hangs 2" would be the
    /// preview and card disagreeing.
    ///
    /// TRANSLATION NOTE: iOS reads it synchronously; `plannedImportName` suspends here (Room), so
    /// it resolves into state, starting from the routine's own name so the header never flickers.
    var landingName by remember(draft) { mutableStateOf(plan.name) }
    LaunchedEffect(draft) { landingName = templates.plannedImportName(plan.name) }

    /// Whether THIS sheet's add failed — local, not the store's error field, or the sheet would
    /// open accused by an earlier unrelated failure.
    var saveFailed by remember { mutableStateOf(false) }
    /// An add in flight: the draft has no id, so a second tap inside the write added it twice.
    var adding by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding)
                .padding(bottom = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                tr("Shared routine"),
                style = MaterialTheme.typography.titleLarge,
                color = palette.inkPrimary,
            )

            Header(landingName, summary)
            PlanCard(sets, plan)
            RhythmCard(plan, draft, summary.setCount)
            Notes(sets, plan)

            if (saveFailed) {
                // STAYS OPEN on a rollback: dismissing loses the code too, and rescanning is somebody
                // else's phone away.
                Text(
                    tr("That routine couldn't be saved just now — nothing was added. Try again."),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = palette.alarm,
                )
            }

            PrimaryButton(tr("Add to my routines"), icon = Icons.Outlined.Add, enabled = !adding) {
                if (adding) return@PrimaryButton
                adding = true
                scope.launch {
                    try {
                        // The card appears on Today by itself: the routine list is store state.
                        if (templates.importRoutine(draft) != null) {
                            saveFailed = false
                            onClose()
                        } else {
                            // INLINE, and the store's copy is consumed: the global "Couldn't save" watches the same
                            // field, and one rollback stated twice reads as two.
                            saveFailed = true
                            templates.saveError = null
                        }
                    } finally {
                        adding = false
                    }
                }
            }

            // Quiet, never destructive-looking: declining costs nothing.
            SecondaryButton(tr("Not now"), modifier = Modifier.fillMaxWidth(), onClick = onClose)

            Spacer(Modifier.padding(bottom = 4.dp))
        }
    }
}

// MARK: - Identity

@Composable
private fun Header(landingName: String, summary: RoutineSummary) {
    val palette = LocalGripPalette.current
    Row(
        Modifier.clearAndSetSemantics {
            // The rung's colour is invisible to TalkBack and greyscale, so speak the number (as `RoutineCard`).
            contentDescription = buildString {
                append(landingName)
                append(". ")
                append(summary.metaLine)
                val peak = summary.peakIntensity
                if (peak != null) {
                    append(L10n.tr(". Peak target %d percent of max.", Math.round(peak * 100)))
                }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EdgeMark(
            fingers = summary.signatureFingers ?: FingerSet.four,
            rungTint = PlanMath.IntensityBand.band(summary.peakIntensity).tint(palette),
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                landingName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            Text(
                summary.metaLine,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkSecondary,
            )
        }
    }
}

// MARK: - The plan

@Composable
private fun PlanCard(sets: List<SetPlan>, plan: SessionPlan) {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CapsLabel(tr("WHAT YOU'LL PULL"))
            sets.forEachIndexed { index, set ->
                SetRow(set, plan)
                if (index < sets.size - 1) {
                    HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.14f))
                }
            }
        }
    }
}

/// Two layouts, forked at `.accessibility1` like `RoutineCard`'s plan row: side by side at the
/// type ceiling, glyph and count left the grip sentence a couple of dozen points. Big text
/// gets full-width words; the glyph is spare decoration.
@Composable
private fun SetRow(set: SetPlan, plan: SessionPlan) {
    val palette = LocalGripPalette.current
    val fontScale = LocalDensity.current.fontScale
    val reps = repText(set, plan)
    val detail = setDetailLine(set, plan)

    val spoken = buildString {
        append(set.grip.spoken)
        append(". ")
        append(reps)
        append(".")
        if (detail != null) append(" $detail.")
    }

    if (fontScale >= PlanRowFit.SENTENCE_FONT_SCALE) {
        Column(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                set.grip.line,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            Text(
                reps,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Medium,
                color = palette.inkSecondary,
            )
            if (detail != null) Detail(detail)
        }
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FingerGlyph(
            fingers = set.grip.fingers,
            position = set.grip.position,
            dot = 7.dp,
            gap = 3.dp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // No line limit: this sentence is what the screen exists to state.
            Text(
                set.grip.line,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            if (detail != null) Detail(detail)
        }
        Text(
            reps,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
        )
    }
}

@Composable
private fun Detail(line: String) {
    Text(
        line,
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = LocalGripPalette.current.inkSecondary,
    )
}

/// What this set does DIFFERENTLY — its own timing or load. A typed 40 kg band first met in
/// the runner is the surprise this sheet prevents, and a per-set hold explains why the
/// estimate disagrees with the rhythm line. Nothing for a set that inherits everything.
private fun setDetailLine(set: SetPlan, plan: SessionPlan): String? {
    val parts = mutableListOf<String>()
    set.holdSeconds?.let { parts.add(L10n.tr("%s hold", PlanMath.durationText(it))) }
    set.restSeconds?.let { parts.add(L10n.tr("%s rest", PlanMath.durationText(it))) }
    val kg = set.targetBand
    val percent = set.targetPercentBand
    if (kg != null) {
        parts.add(WeightUnits.tr("%s–%s kg target", kgText(kg.start), kgText(kg.endInclusive)))
    } else if (percent != null) {
        val lo = Math.round(percent.start * 100)
        val hi = Math.round(percent.endInclusive * 100)
        parts.add(
            if (lo == hi) L10n.tr("%d %% of max", hi) else L10n.tr("%d–%d %% of max", lo, hi)
        )
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/// Display text, so the DEFAULT locale (as `PlanMath.bandText`); wire text is locale-free.
private fun kgText(kg: Double): String = WeightUnits.number(kg)

/// "6 per side" when hands alternate, "6 pulls" together. The count comes from
/// `PlanMath.repCount`, the one home of the `× sideCount` a silent factor of two would hide in.
private fun repText(set: SetPlan, plan: SessionPlan): String {
    if (plan.handMode.sideCount != 1) return L10n.tr("%d per side", set.repsPerSide)
    val pulls = PlanMath.repCount(set, plan.handMode)
    return L10n.tr("%d %s", pulls, L10n.tr(if (pulls == 1) "pull" else "pulls"))
}

// MARK: - Rhythm and cadence

@Composable
private fun RhythmCard(plan: SessionPlan, draft: RoutineDraft, setCount: Int) {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapsLabel(tr("RHYTHM"))
                Text(
                    rhythmLine(plan, setCount),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    color = palette.inkPrimary,
                )
                Text(
                    plan.handMode.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapsLabel(tr("EVERY DAY"))
                Text(
                    cadenceLine(draft),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    color = palette.inkPrimary,
                )
            }
        }
    }
}

/// The ROUTINE's rhythm, inherited unless a set overrides (overrides are already in the counts
/// and estimate).
private fun rhythmLine(plan: SessionPlan, setCount: Int): String {
    val parts = mutableListOf(
        L10n.tr("%s hold", PlanMath.durationText(plan.holdSeconds)),
        L10n.tr("%s rest", PlanMath.durationText(plan.restSeconds)),
    )
    // With one set, "between sets" is a constant dressed as information.
    if (setCount > 1) parts.add(L10n.tr("%s between sets", PlanMath.durationText(plan.setBreakSeconds)))
    return parts.joinToString(" · ")
}

/// A WHENEVER routine is never owed, so it says so rather than quoting a number.
private fun cadenceLine(draft: RoutineDraft): String {
    if (draft.isOnDemand) return L10n.tr("Whenever you're fresh")
    return when (val n = maxOf(1, draft.sessionsPerDay)) {
        1 -> L10n.tr("Once a day")
        2 -> L10n.tr("Twice a day")
        else -> L10n.tr("%d× a day", n)
    }
}

// MARK: - The two honesty notes

/// Each shown only when TRUE of this routine: noise footnotes teach people to stop reading them.
@Composable
private fun Notes(sets: List<SetPlan>, plan: SessionPlan) {
    val palette = LocalGripPalette.current
    /// `PlanMath.targetBand` precedence: a set with typed kilograms never reaches its percentage.
    val hasPercent = sets.any { it.targetBand == null && PlanMath.targetPercent(it, plan) != null }
    val hasKilograms = sets.any { it.targetBand != null }
    // Guarded around the STACK: an empty column is still a child, and the 18 dp spacing would
    // leave a gap under the (common) routine prescribing no load.
    if (!hasPercent && !hasKilograms) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasPercent) {
            Text(
                tr("Percentage targets use your saved maxes. These may no longer reflect your current strength."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
        if (hasKilograms) {
            Text(
                tr("Some fixed weight targets were set by the sender. Review them for your own training."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
    }
}
