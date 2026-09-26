// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.HandModeChipRow
import run.nuri.getagrip.ui.components.HandOrderStrip
import run.nuri.getagrip.ui.components.HandsHeader
import run.nuri.getagrip.ui.components.HouseSegmentedRow
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// PAGE 1 — the Rhythm every set follows: hold, rest, set break and when a rest starts, then
/// the hands, as the critical force setup draws them.
///
/// **One screen while creating** (a 360 × 740 dp phone at default text): three `− value +`
/// rows on the dial's own ladder instead of three dials, which cost a screen and a half.
/// Sets still override hold and rest per row, behind "Custom timing".
@Composable
fun RhythmSection(
    /// Only what this page draws — see `RhythmValues`; the whole draft redrew it per name keystroke.
    rhythm: RhythmValues,
    modifier: Modifier = Modifier,
    update: DraftUpdate,
) {
    val palette = LocalGripPalette.current
    val largeText = LocalDensity.current.fontScale >= 1.5f

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                // Each row takes only its own number, so a step on one leaves the other two skipped.
                TimingStepper(TimingKind.Hold, rhythm.holdSeconds) { v ->
                    update { it.copy(plan = it.plan.copy(holdSeconds = v)) }
                }
                RowDivider()
                TimingStepper(TimingKind.Rest, rhythm.restSeconds) { v ->
                    update { it.copy(plan = it.plan.copy(restSeconds = v)) }
                }
                RowDivider()
                TimingStepper(TimingKind.SetBreak, rhythm.setBreakSeconds) { v ->
                    update { it.copy(plan = it.plan.copy(setBreakSeconds = v)) }
                }
                RowDivider()
                // Here, not in Fine tuning: it decides when every rest STARTS. ON by default: otherwise
                // the seconds of standing down off a 20 mm edge come out of every rest.
                ToggleRow(
                    title = tr("Start the rest when I let go"),
                    checked = rhythm.waitForReleaseBeforeRest,
                    minHeight = 46.dp,
                ) { waits -> update { it.copy(plan = it.plan.copy(waitForReleaseBeforeRest = waits)) } }
            }
        }

        // The critical force setup's hands, exactly: the label row carries which hand goes first,
        // one segmented control underneath, no card.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val alternates = rhythm.handMode.sideCount > 1
            HandsHeader(
                menuTitle = if (alternates) sideTitle(rhythm.startingHand) else null,
                side = rhythm.startingHand,
                sideTitle = ::sideTitle,
                hiddenReason = tr("Both hands pull together, so neither goes first."),
                reservesMenuHeight = true,
                modifier = Modifier.padding(start = 4.dp),
            ) { side -> update { it.copy(plan = it.plan.copy(startingHand = side)) } }
            if (largeText) {
                // Three segments would truncate: the wrapping chips, in full words, come back here.
                HandModeChipRow(rhythm.handMode) { mode -> update { it.copy(plan = it.plan.copy(handMode = mode)) } }
            } else {
                HouseSegmentedRow(
                    labels = HandMode.entries.map { it.segmentName },
                    selectedIndex = rhythm.handMode.ordinal,
                ) { index -> update { it.copy(plan = it.plan.copy(handMode = HandMode.entries[index])) } }
            }
            // The FIRST set's sequence, the one about to be done — centred under the control.
            HandOrderStrip(
                mode = rhythm.handMode,
                repsPerSide = rhythm.firstRepsPerSide,
                startingHand = rhythm.startingHand,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp).wrapContentWidth(Alignment.CenterHorizontally),
            )
        }
    }
}

private fun sideTitle(side: Side): String =
    if (side == Side.right) L10n.tr("Right first") else L10n.tr("Left first")

@Composable
private fun RowDivider() {
    HorizontalDivider(color = LocalGripPalette.current.inkTertiary.copy(alpha = 0.22f))
}

/// Which routine clock a `TimingStepper` edits.
enum class TimingKind { Hold, Rest, SetBreak }

/// Hold, rest or set break as a ladder stepper — ONE definition, used by the Rhythm page and
/// a set's Custom timing, so the two cannot drift. Every parameter is a value, so an unchanged
/// row is skipped.
@Composable
fun TimingStepper(kind: TimingKind, value: Int, modifier: Modifier = Modifier, onValueChange: (Int) -> Unit) {
    val seconds = tr("s")
    val spoken = tr("seconds")
    when (kind) {
        TimingKind.Hold -> IntValueRow(
            title = tr("Hold"), value = value, range = 1..60, unit = seconds, modifier = modifier,
            limit = SetPlan.holdRange, control = HOLD_LADDER, spokenUnit = spoken, onValueChange = onValueChange,
        )
        TimingKind.Rest -> IntValueRow(
            title = tr("Rest between pulls"), value = value, range = 0..60, unit = seconds, modifier = modifier,
            limit = SetPlan.restRange, control = REST_LADDER, spokenUnit = spoken, onValueChange = onValueChange,
        )
        TimingKind.SetBreak -> IntValueRow(
            title = tr("Break between sets"), value = value, range = 0..240, unit = seconds, modifier = modifier,
            limit = SessionPlan.setBreakRange, control = BREAK_LADDER, spokenUnit = spoken, onValueChange = onValueChange,
        )
    }
}

/// Every detent the shipping protocols use (3 s C4 holds, 5/7/10/12 s repeaters, 15–60 s
/// rests). Hold and rest differ only at the floor: a 0 s rest is a cadence, a 0 s hold is not
/// a hold.
private val SECONDS_LADDER = listOf(3.0, 5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 30.0, 45.0, 60.0)
private val HOLD_LADDER = ValueControl.LadderStepper(listOf(1.0) + SECONDS_LADDER)
private val REST_LADDER = ValueControl.LadderStepper(listOf(0.0) + SECONDS_LADDER)
private val BREAK_LADDER = ValueControl.LadderStepper(listOf(0.0, 15.0, 30.0, 45.0, 60.0, 90.0, 120.0, 180.0, 240.0))

/// The segmented control's short form: the row is labelled HANDS, so the noun goes.
val HandMode.segmentName: String
    get() = when (this) {
        HandMode.alternateEachRep -> L10n.tr("Alternate")
        HandMode.alternateEachSet -> L10n.tr("One at a time")
        HandMode.bothHands -> L10n.tr("Both")
    }

/// A switch whose whole row is the target.
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 44.dp,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalGripPalette.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // **THE WHOLE ROW IS THE SWITCH.** As two stops, TalkBack reads a sentence then a nameless
        // "Switch, off"; `toggleable` on the row ties the toggle to its words. It is also the
        // hit-target rule: a full-width row tappable only on a 32 dp switch is half dead. The `Switch`
        // takes a NULL callback, or the change would fire twice.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = palette.graphite,
                    checkedThumbColor = palette.graphiteInverse,
                ),
            )
        }
    }
}

@Preview(name = "RhythmSection", showBackground = true, widthDp = 380)
@Composable
private fun RhythmSectionPreview() {
    GetAGripTheme {
        Column(Modifier.padding(16.dp)) {
            var draft by remember { mutableStateOf(RoutineDraft.starter) }
            RhythmSection(RhythmValues.of(draft.plan)) { draft = it(draft) }
        }
    }
}
