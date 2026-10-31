// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.runner

import run.nuri.getagrip.ui.units.WeightUnits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.runner.MaxCandidate
import run.nuri.getagrip.runner.SessionOutcome
import run.nuri.getagrip.runner.WorkoutSummaryState
import run.nuri.getagrip.runner.SessionSummaryDecision
import run.nuri.getagrip.ui.components.EffortPicker
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.HoldToDiscardButton
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// What just happened, and the one question worth asking about it.
///
/// The grade is the point: peak and average kilograms are what the gauge measured, but how
/// hard it *felt* is the thing no sensor knows and the only input a future progression
/// suggestion can use. It is one tap and always skippable — a summary that blocks on a
/// question gets dismissed reflexively, and then the answer is noise.
///
/// It writes NOTHING. `onDone` hands the outcome and the decision back to `RunnerHost`, which
/// hands them to the integrator — so the whole screen is previewable with no store behind it.
@Composable
fun SessionSummaryScreen(
    outcome: SessionOutcome,
    sessionsPerDayTarget: Int,
    modifier: Modifier = Modifier,
    state: WorkoutSummaryState = remember { WorkoutSummaryState() },
    submissionScope: kotlinx.coroutines.CoroutineScope = rememberCoroutineScope(),
    onDone: suspend (SessionOutcome, SessionSummaryDecision) -> Boolean,
) {
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current

    /// FROZEN at first appearance — recording a candidate updates the max table, and a live
    /// computation would then drop the row it should be flipping to a checkmark.
    val candidates = remember(outcome) { outcome.maxCandidates }

    val reps = outcome.results
    val heldSeconds = outcome.totalHeldSeconds.roundToInt()

    fun finish(save: Boolean) {
        // Guard against a double tap producing two logs — the button is on screen while the
        // caller's save round-trips.
        if (state.finished) return
        state.finished = true
        submissionScope.launch {
            val success = onDone(
                outcome,
                SessionSummaryDecision(
                    save = save && outcome.didAnyWork,
                    rpe = if (save) state.rpe else null,
                    newMaxes = if (save) candidates.filter { it.id in state.chosenMaxIDs } else emptyList(),
                ),
            )
            state.saveFailed = !success
            if (!success) state.finished = false
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding, vertical = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(Metrics.spacing),
        ) {
            SessionSummaryHeader(
                caption = outcome.routineName,
                title = if (outcome.didAnyWork) tr("Session done") else tr("Session ended"),
                note = if (sessionsPerDayTarget > 1) tr("This routine asks for %d a day.", sessionsPerDayTarget) else null,
            )

            SessionSummaryStats(
                completed = outcome.completedReps,
                planned = outcome.plannedReps,
                heldSeconds = heldSeconds,
                peakKg = if (outcome.timerOnly) null else outcome.peakKg,
            )

            if (outcome.didAnyWork && candidates.isNotEmpty()) {
                NewMaxCard(candidates, state.chosenMaxIDs, palette,
                    expanded = state.maxesExpanded,
                    onToggle = { state.maxesExpanded = !state.maxesExpanded },
                ) { candidate ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.chosenMaxIDs = if (candidate.id in state.chosenMaxIDs) state.chosenMaxIDs - candidate.id else state.chosenMaxIDs + candidate.id
                }
            }

            if (outcome.didAnyWork) {
                GradeCard(state.rpe, palette) { level ->
                    state.rpe = level
                }
            }

            SetBreakdown(reps, palette, state.setsExpanded) { state.setsExpanded = !state.setsExpanded }
        }
        // This sibling reserves its own space; expanded details scroll above the actions.
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = Metrics.hPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.saveFailed) {
                Text(tr("Couldn't save this workout. Try again."),
                     color = palette.armed, style = MaterialTheme.typography.bodySmall)
            }
            PrimaryButton(
                title = if (outcome.didAnyWork) tr("Save and finish") else tr("Finish"),
                modifier = Modifier.widthIn(max = Metrics.maxContentWidth),
            ) { finish(save = true) }

            if (!outcome.didAnyWork) {
                Text(
                    tr("No pulls held. Nothing to log."),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                HoldToDiscardButton { finish(save = false) }
            }
        }
    }
}

/// The routine above, the outcome below. History's session detail passes the day as the
/// title, so the two screens read as one document. Shared, never copied.
@Composable
internal fun SessionSummaryHeader(caption: String, title: String, note: String? = null) {
    val palette = LocalGripPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CapsLabel(caption)
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
    }
}

/// Pulls of planned, time under tension, peak. `peakKg` null is a session no gauge measured
/// (timer-only): the card says "—", never "0.0 kg", which would claim a measurement.
@Composable
internal fun SessionSummaryStats(
    completed: Int,
    planned: Int,
    heldSeconds: Int,
    peakKg: Double?,
) {
    val palette = LocalGripPalette.current
    val timerOnly = peakKg == null
    // ONE key per whole sentence, not three appended fragments: which clause goes where
    // is language-specific, and a fragment beginning with a comma cannot be moved.
    val spoken = if (peakKg == null) {
        tr("%d of %d pulls completed, %d seconds under tension", completed, planned, heldSeconds)
    } else {
        WeightUnits.tr(
            "%d of %d pulls completed, %d seconds under tension, peak %s kilograms",
            completed,
            planned,
            heldSeconds,
            kgText(peakKg),
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }) {
        // Preserve readable labels and numbers instead of squeezing larger text into thirds.
        val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
        val values = listOf(
            Triple(tr("Pulls"), "$completed", tr("of %d", planned)),
            Triple(tr("Under tension"), PlanMath.clockText(heldSeconds), null),
            Triple(tr("Peak"), peakKg?.let { kgText(it) } ?: tr("—"), if (timerOnly) null else WeightUnits.symbol),
        )
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((title, value, suffix) in values) {
                    Stat(title, value, suffix, palette, Modifier.fillMaxWidth(), horizontal = true)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((title, value, suffix) in values) {
                    Stat(title, value, suffix, palette, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun Stat(
    title: String,
    value: String,
    suffix: String?,
    palette: GripPalette,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    Surface(shape = RoundedCornerShape(Metrics.radiusInner), color = palette.card, modifier = modifier) {
        if (horizontal) {
            Row(
                Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The title takes what the figure leaves: a 50/50 split wrapped "Under / tension"
                // beside an empty half. The unit rides INLINE after the figure ("17.0 kg",
                // "6 of 6") and reserves no line when there is none, so "1:00" sits centred.
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatValue(value, palette, Modifier.alignByBaseline())
                    if (!suffix.isNullOrEmpty()) {
                        Text(
                            suffix,
                            Modifier.alignByBaseline(),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.inkSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                // Bottom alignment keeps values level if one translated label wraps,
                // without reserving an empty second label line at normal sizes.
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                StatValue(value, palette)
                Spacer(Modifier.height(4.dp))
                // Reserve the same unit line in all three cards so values share a baseline.
                Text(suffix.orEmpty(), style = MaterialTheme.typography.labelMedium, color = palette.inkSecondary, minLines = 1)
            }
        }
    }
}

@Composable
private fun StatValue(value: String, palette: GripPalette, modifier: Modifier = Modifier) {
    Text(
        value,
        modifier,
        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
        color = palette.inkPrimary,
        textAlign = TextAlign.Center,
    )
}

/// A pull inside a session that beat a grip's working max, offered as the new max per HAND
/// (Nuri, 2026-08-10: "it should offer to update your max in that grip type, depending on the
/// side you're pulling on"). One tap; `measured` provenance — the gauge genuinely saw it.
///
/// Deliberately does NOT mark a benchmark day: this session already logged, and settling the
/// day would cancel the evening ritual. That rule belongs to the integrator's `recordMax`
/// call, which is why `SessionSummaryDecision` carries the candidates rather than writing them.
@Composable
private fun NewMaxCard(
    candidates: List<MaxCandidate>,
    chosen: Set<String>,
    palette: GripPalette,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (MaxCandidate) -> Unit,
) {
    Surface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.armed.copy(alpha = 0.05f).compositeOver(palette.card)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .semantics {
                        contentDescription = L10n.tr("New peaks to review")
                        stateDescription = L10n.tr(if (expanded) "Expanded" else "Collapsed")
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(tr("New peaks to review"), style = MaterialTheme.typography.titleSmall,
                     color = palette.armed, modifier = Modifier.weight(1f))
                Text(candidates.size.toString(), color = palette.armed)
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                     contentDescription = null, tint = palette.armed)
            }
            if (expanded) {
                Text(tr("Pick the peaks to save as maxes."),
                     style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                Column {
                    candidates.forEachIndexed { index, candidate ->
                        MaxRow(candidate, saved = candidate.id in chosen, palette = palette) { onSave(candidate) }
                        if (index < candidates.lastIndex) HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.15f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MaxRow(
    candidate: MaxCandidate,
    saved: Boolean,
    palette: GripPalette,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = saved, role = Role.Checkbox, onClick = onSave)
            .semantics {
                contentDescription = sideLine(candidate)
                stateDescription = L10n.tr(if (saved) "Selected" else "Use as max")
            }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FingerGlyph(candidate.grip.fingers, position = candidate.grip.position,
            dot = 8.dp, gap = 3.dp, tint = if (saved) palette.armed else palette.inkSecondary)
        PeakFigure(candidate.grip, candidate.side, candidate.kg, candidate.previous, Modifier.weight(1f))
        Checkbox(checked = saved, onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = palette.armed,
                uncheckedColor = palette.inkTertiary.copy(alpha = 0.65f)))
    }
}

private fun sideLine(candidate: MaxCandidate): String {
    val kg = kgText(candidate.kg)
    return WeightUnits.tr("%s · %s · %s kg", handLabel(candidate.side), candidate.grip.shortName, kg)
}

/// A peak as the summary states it: the weight, the hand beside it, the grip, and the max it
/// would replace. Shared with History's session detail, which lists the maxes a session saved.
@Composable
internal fun PeakFigure(
    grip: GripSpec,
    side: Side,
    kg: Double,
    previous: Double?,
    modifier: Modifier = Modifier,
    /// A timed max's window; 0 is a peak, and says nothing extra.
    seconds: Int = 0,
) {
    val palette = LocalGripPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.align(Alignment.CenterVertically),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(kgText(kg), Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
                Text(WeightUnits.symbol, Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
            }
            Text(handLabel(side), Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary)
        }
        Text(if (seconds > 0) "${grip.line} · ${tr("%d s", seconds)}" else grip.line,
            style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
        previous?.let {
            Text(WeightUnits.tr("Previous: %s kg", kgText(it)), style = MaterialTheme.typography.labelSmall, color = palette.inkTertiary)
        }
    }
}

internal fun handLabel(side: Side): String = L10n.tr(when (side) {
    Side.left -> "Left hand"
    Side.right -> "Right hand"
    Side.both -> "Both hands"
})

@Composable
private fun GradeCard(grade: RPE?, palette: GripPalette, onPick: (RPE?) -> Unit) {
    Surface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card) {
        EffortPicker(grade?.rawValue, RPE.entries.map { it.displayName },
            tr("How hard did it feel?"), "effort.overall", Modifier.padding(20.dp)) {
            onPick(it?.let(RPE::fromRaw))
        }
    }
}

/// Per set, so a bad set is visible rather than averaged away.
///
/// `showsReps` (History's session detail) lists every pull under its set — hand, time held,
/// peak, and how it ended — because an old session is opened to see exactly that. The live
/// summary keeps one line per set. `measured` false (timer-only) drops the peak, which would
/// read "0.0 kg".
@Composable
internal fun SetBreakdown(reps: List<RepSummary>, palette: GripPalette,
                          expanded: Boolean, showsReps: Boolean = false, measured: Boolean = true,
                          onToggle: () -> Unit) {
    val order = LinkedHashMap<Int, MutableList<RepSummary>>()
    for (rep in reps) order.getOrPut(rep.setIndex) { mutableListOf() }.add(rep)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(Metrics.radiusInner), color = palette.card) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .semantics {
                        contentDescription = L10n.tr("Sets")
                        stateDescription = L10n.tr(if (expanded) "Expanded" else "Collapsed")
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(tr("Sets"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = palette.inkSecondary)
                Text(order.size.toString(), color = palette.inkSecondary)
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = palette.inkSecondary)
            }
        }
        if (expanded) for ((_, inSet) in order) {
            val done = inSet.count { it.outcome == RepOutcome.completed }
            val grip = inSet.firstOrNull()?.grip
            // Read outside the semantics lambda, which is not composable.
            val spoken = tr("%s: %d of %d completed", grip?.spoken ?: tr("Set"), done, inSet.size)
            Surface(shape = RoundedCornerShape(Metrics.radiusInner), color = palette.card) {
              Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = spoken
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (grip != null) {
                        FingerGlyph(grip.fingers, position = grip.position, dot = 8.dp, gap = 3.dp)
                        Text(
                            grip.line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    Text(
                        "$done/${inSet.size}",
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        // Amber, not red: an incomplete set is a fact, not an alarm.
                        color = if (done == inSet.size) palette.inkPrimary else palette.armed,
                    )
                }
                if (showsReps) for (rep in inSet) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = palette.inkTertiary.copy(alpha = 0.15f))
                    RepLine(rep, measured, palette)
                }
              }
            }
        }
    }
}

/// One pull: the hand, what was held of what was asked, the peak, and — only when it did not
/// simply complete — how it ended, in amber (a fact, not an alarm). ONE TalkBack stop.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RepLine(rep: RepSummary, measured: Boolean, palette: GripPalette) {
    val seconds = String.format(java.util.Locale.getDefault(), "%.1f", rep.heldSeconds)
    val held = tr("%s of %d s", seconds, rep.targetSeconds)
    val peak = if (measured && rep.peakKg > 0) "${kgText(rep.peakKg)} ${WeightUnits.symbol}" else null
    val ending = repEnding(rep.outcome)
    val spoken = buildList {
        add(handLabel(rep.side))
        add(tr("held %s of %d seconds", seconds, rep.targetSeconds))
        if (peak != null) add(WeightUnits.tr("peak %s kilograms", kgText(rep.peakKg)))
        if (ending != null) add(ending)
    }.joinToString(", ")
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    // A flow, so at large text the figures drop under the hand instead of ellipsizing.
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(L10n.tr(when (rep.side) {
            Side.left -> "Left"
            Side.right -> "Right"
            Side.both -> "Both"
        }), style = style, color = palette.inkPrimary)
        if (ending != null) {
            Text(ending, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = palette.armed)
        }
        Spacer(Modifier.weight(1f))
        Text(held, style = style, color = palette.inkSecondary)
        if (peak != null) Text(peak, style = style, fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
    }
}

/// null for a pull that simply completed.
internal fun repEnding(outcome: RepOutcome): String? = when (outcome) {
    RepOutcome.completed -> null
    RepOutcome.skipped -> L10n.tr("Skipped")
    RepOutcome.earlyRelease -> L10n.tr("Released early")
    RepOutcome.aborted -> L10n.tr("Stopped")
}

// MARK: - Previews

private fun previewOutcome(didWork: Boolean = true): SessionOutcome {
    val grip = GripSpec(20, FingerSet.four)
    val reps = if (didWork) {
        listOf(
            RepSummary(0, 0, Side.left, grip, 7, 7.0, 31.2, 28.4, outcome = RepOutcome.completed),
            RepSummary(0, 1, Side.right, grip, 7, 6.4, 28.9, 26.1, outcome = RepOutcome.completed),
            RepSummary(1, 0, Side.left, GripSpec(20, FingerSet.frontTwo), 7, 0.0, 0.0, 0.0, outcome = RepOutcome.skipped),
        )
    } else {
        emptyList()
    }
    return SessionOutcome(
        plan = SessionPlan(name = L10n.tr("Daily no-hangs")),
        routineName = L10n.tr("Daily no-hangs"),
        results = reps,
        startedAt = Instant.now(),
        finishedAt = Instant.now(),
        peakKg = 31.2,
        avgKg = 27.3,
        totalHeldSeconds = 13.4,
        completedReps = reps.count { it.outcome == RepOutcome.completed },
        plannedReps = reps.size,
        timerOnly = false,
        gaugeKind = null,
        didAnyWork = didWork,
        maxCandidates = if (didWork) {
            listOf(MaxCandidate(grip, Side.left, 31.2, previous = 29.0))
        } else {
            emptyList()
        },
        id = UUID.randomUUID(),
    )
}

@Preview(name = "Summary · light", showBackground = true, heightDp = 900)
@Composable
private fun SummaryLightPreview() {
    GetAGripTheme(darkTheme = false) {
        Surface(color = LocalGripPalette.current.field) {
            SessionSummaryScreen(previewOutcome(), sessionsPerDayTarget = 2) { _, _ -> true }
        }
    }
}

@Preview(name = "Summary · dark", showBackground = true, heightDp = 900)
@Composable
private fun SummaryDarkPreview() {
    GetAGripTheme(darkTheme = true) {
        Surface(color = LocalGripPalette.current.field) {
            SessionSummaryScreen(previewOutcome(), sessionsPerDayTarget = 2) { _, _ -> true }
        }
    }
}
