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
import java.text.NumberFormat
import java.util.Locale
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

/// The other side of a QR code: somebody else's routine, read out in full, before it is
/// yours.
///
/// It is a PREVIEW, not an editor. Nothing here is adjustable, and that is the honest shape
/// — a routine you have not accepted yet is not a routine you can edit, and fields would be
/// asking a stranger's plan to be corrected before it has been read. Everything in it is one
/// tap from editable the moment it lands: the card it becomes opens the builder on its own
/// plan row.
///
/// **Percentage targets are deliberately NOT translated.** They resolve against the READER's
/// maxes, per hand, at the moment a session starts — which is the entire reason this app
/// prescribes fractions rather than kilograms, and it means the same code prescribes the
/// right load for two people with very different fingers. The two footnotes exist for the
/// cases where that is not the whole story.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineImportSheet(incoming: RoutineDraft, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Normalized HERE so the preview shows the routine that will actually land: an empty
    // name becomes the house default on the way in, an emptied set is dropped, and
    // inheritance is consolidated. Previewing the raw draft and saving the normalized one is
    // exactly how a preview and the card it becomes disagree. `normalized` is idempotent, so
    // the store's own pass costs nothing.
    val draft = remember(incoming) { incoming.normalized }
    // Folded ONCE, not per composition: `RoutineSummary.previewing` walks the whole rep
    // sequence three times and mints an id, so a computed read would re-fold a fifty-set
    // routine on every scroll frame and change identity while doing it.
    val summary = remember(draft) { RoutineSummary.previewing(draft) }
    val plan = draft.plan
    /// `executable`, like every other fold in the app: a set with no pulls in it is a row the
    /// author emptied out, not a rest.
    val sets = remember(plan) { plan.executable.sets }

    /// The name this routine will actually LAND under. The store deconflicts on save
    /// (`uniqueName`), and two people keeping the shipped default name is the common case for
    /// a shared routine — a preview promising "Daily no-hangs" four seconds before the card
    /// says "Daily no-hangs 2" is the preview and the card disagreeing.
    ///
    /// TRANSLATION NOTE: iOS reads this synchronously per body pass. `plannedImportName` is a
    /// suspend function here (the routine list lives behind Room), so it is resolved into
    /// state — starting from the routine's own name, which is the answer whenever nothing
    /// collides, so the header never flickers from blank to a name.
    var landingName by remember(draft) { mutableStateOf(plan.name) }
    LaunchedEffect(draft) { landingName = templates.plannedImportName(plan.name) }

    /// Whether THIS sheet's add failed — local state, not a read of the store's error field:
    /// keyed to that, the sheet would open already wearing the accusation whenever an
    /// earlier, unrelated write had failed.
    var saveFailed by remember { mutableStateOf(false) }

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
                // The sheet STAYS OPEN on a rollback: dismissing on failure loses the code as
                // well as the routine, and rescanning is somebody else's phone away.
                Text(
                    tr("That routine couldn't be saved just now — nothing was added. Try again."),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = palette.alarm,
                )
            }

            PrimaryButton(tr("Add to my routines"), icon = Icons.Outlined.Add) {
                scope.launch {
                    // The new card appears on Today by itself — the routine list is store
                    // state, so nothing has to be handed back through the presentation.
                    if (templates.importRoutine(draft) != null) {
                        saveFailed = false
                        onClose()
                    } else {
                        // Surfaced INLINE, and the store's copy of the failure is consumed:
                        // the global "Couldn't save" surface watches the same field, and one
                        // rollback stated twice reads as two.
                        saveFailed = true
                        templates.saveError = null
                    }
                }
            }

            // Quiet, and never destructive-looking: declining a routine costs nothing and
            // undoes nothing.
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
            // The rung's colour is invisible to TalkBack and to greyscale, so the number it
            // stands for is spoken — the same pairing `RoutineCard` makes.
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

/// Two layouts, forked at Android's `.accessibility1` rung — the same fork `RoutineCard`'s
/// plan row makes, and for the same measured reason: side by side, the scaled glyph plus a
/// priority-protected count left the grip sentence a couple of dozen points of the row at
/// the app's type ceiling, on the one screen whose job is stating the grip. Big text is
/// served by words stacked in full width; the glyph is decoration it can spare.
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
            // No line limit: this sentence is what the screen exists to state, so it wraps
            // rather than truncates.
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

/// What this set does DIFFERENTLY — its own timing, its own load. A stranger's routine owes
/// you these before you accept it: a typed 40 kg band you meet for the first time in the
/// runner is exactly the surprise this sheet exists to prevent, and a per-set 3 s hold
/// explains why the estimate above disagrees with the rhythm line below. Nothing renders for
/// the common set that inherits everything.
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

/// Display text, so the DEFAULT locale — the same rule `PlanMath.bandText` states. Wire text
/// is the locale-free half of the app and this is not it.
private fun kgText(kg: Double): String = WeightUnits.number(kg)

/// "6 per side" when the hands take turns, "6 pulls" when they are on the edge together. The
/// pull count comes from `PlanMath.repCount` rather than a `× sideCount` written here — that
/// multiplication has exactly one home in the app, and this is the screen where a silent
/// factor of two would be believed.
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

/// The ROUTINE's rhythm — the values every set inherits unless it overrides. A set that
/// overrides is already folded into the pull counts and the estimate above.
private fun rhythmLine(plan: SessionPlan, setCount: Int): String {
    val parts = mutableListOf(
        L10n.tr("%s hold", PlanMath.durationText(plan.holdSeconds)),
        L10n.tr("%s rest", PlanMath.durationText(plan.restSeconds)),
    )
    // A break "between sets" is a constant dressed as information when there is only one
    // set — the Live Update drops "Set 1 of 1" for the same reason.
    if (setCount > 1) parts.add(L10n.tr("%s between sets", PlanMath.durationText(plan.setBreakSeconds)))
    return parts.joinToString(" · ")
}

/// A WHENEVER routine has no daily target and is never owed, so it says so instead of
/// quoting a number it does not mean.
private fun cadenceLine(draft: RoutineDraft): String {
    if (draft.isOnDemand) return L10n.tr("Whenever you're fresh")
    return when (val n = maxOf(1, draft.sessionsPerDay)) {
        1 -> L10n.tr("Once a day")
        2 -> L10n.tr("Twice a day")
        else -> L10n.tr("%d× a day", n)
    }
}

// MARK: - The two honesty notes

/// Both are shown only when they are TRUE of this routine — a footnote about kilograms under
/// a routine that carries none is noise, and noise is what teaches people to stop reading
/// footnotes.
@Composable
private fun Notes(sets: List<SetPlan>, plan: SessionPlan) {
    val palette = LocalGripPalette.current
    /// `PlanMath.targetBand`'s precedence, read as a predicate: a set carrying typed
    /// kilograms never reaches its percentage, so it is not a percentage set.
    val hasPercent = sets.any { it.targetBand == null && PlanMath.targetPercent(it, plan) != null }
    val hasKilograms = sets.any { it.targetBand != null }
    // Guarded around the STACK, not just inside it: an empty column is still a child, and the
    // document's 18 dp spacing would leave a block of nothing under a routine that prescribes
    // no load at all — which is most of them.
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
