// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.HandModeChipRow
import run.nuri.getagrip.ui.components.HandOrderStrip
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// REST & HANDS — the parts of a session every set genuinely shares.
///
/// **It sits ABOVE the set list on purpose: constants above variables**, expressed as
/// vertical order instead of as screens. Both losing designs buried routine-wide timing
/// below six set rows, so changing one interval meant scrolling past the whole set list
/// every time.
///
/// Hold and rest used to live here as routine-level defaults, until the Max day protocol
/// made the flaw obvious (Nuri, 2026-08-10: "what if for one pull you want 10 seconds and
/// for the other 20?") — timing is a property of a SET, and it moved onto every set row.
/// What is left is only what cannot vary per set: the break between sets, how the hands
/// share the work, and when a rest starts counting.
@Composable
fun RhythmSection(
    draft: RoutineDraft,
    modifier: Modifier = Modifier,
    onChange: (RoutineDraft) -> Unit,
) {
    val palette = LocalGripPalette.current
    val plan = draft.plan

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // A plain row, never a pinned section header — a header that pins leaves content
        // scrolling illegibly behind it.
        CapsLabel(tr("REST & HANDS"))

        Surface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IntValueRow(
                    title = tr("Break between sets"),
                    value = plan.setBreakSeconds,
                    range = 0..240,
                    unit = tr("s"),
                    limit = run.nuri.getagrip.engine.SessionPlan.setBreakRange,
                    control = ValueControl.Dial(listOf(0.0, 30.0, 60.0, 90.0, 120.0, 180.0)),
                ) { onChange(draft.copy(plan = plan.copy(setBreakSeconds = it))) }

                // Under the break rather than in Fine tuning: this decides when every rest
                // in the session actually STARTS, and a rest number whose meaning is set
                // two cards away cannot be trusted.
                ToggleRow(
                    title = tr("Start the rest when I let go"),
                    checked = plan.waitForReleaseBeforeRest,
                    // ON by default, because the alternative silently shortens every rest
                    // you take: the hold completes at exactly 10 s, but standing down off a
                    // 20 mm edge takes another two or three, and those come out of the rest
                    // rather than out of the hang. Off is still a real choice — a fixed
                    // cadence you pace yourself to.
                    explainer = if (plan.waitForReleaseBeforeRest) {
                        tr("The hold ends on time; the rest waits until you are off the edge.")
                    } else {
                        tr("The rest starts the moment the hold ends, whether or not you have let go.")
                    },
                ) { onChange(draft.copy(plan = plan.copy(waitForReleaseBeforeRest = it))) }

                HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.22f))

                // Hands is a genuinely categorical choice, so it stays chips — and it is
                // ALWAYS expanded, because this control exists to be SEEN: the strip under
                // it is the only place the app shows what "alternate each pull" does.
                CapsLabel(tr("HANDS"), Modifier.padding(top = 2.dp))
                HandModeChipRow(plan.handMode) {
                    onChange(draft.copy(plan = plan.copy(handMode = it)))
                }
                // The FIRST set's sequence, because that is the one the reader is about to
                // do; `executable` so an emptied-out row cannot decide it.
                HandOrderStrip(
                    mode = plan.handMode,
                    repsPerSide = plan.executable.sets.firstOrNull()?.repsPerSide ?: 6,
                )
            }
        }
    }
}

/// A switch with its consequence said underneath, either way — a switch whose off-state is
/// silent makes you flip it to find out what it does.
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    explainer: String?,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalGripPalette.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // **THE WHOLE ROW IS THE SWITCH.** Label and control as two independent stops is how
        // TalkBack ends up reading a sentence and then a nameless "Switch, off" — the toggle
        // belongs to the words beside it, and `toggleable` on the row is what says so. It is
        // also the house hit-target rule: a row drawn full-width that is only tappable on a
        // 32 dp switch at its right edge is half dead. The `Switch` then takes a NULL
        // callback: the row owns both the tap and the semantics, and a second handler
        // underneath it would fire the change twice.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
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
        if (explainer != null) {
            Text(
                explainer,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
            )
        }
    }
}

@Preview(name = "RhythmSection", showBackground = true, widthDp = 380)
@Composable
private fun RhythmSectionPreview() {
    GetAGripTheme {
        Column(Modifier.padding(16.dp)) {
            RhythmSection(RoutineDraft.starter) {}
        }
    }
}
