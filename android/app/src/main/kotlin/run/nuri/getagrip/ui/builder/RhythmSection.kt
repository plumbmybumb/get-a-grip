// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.HandModeChipRow
import run.nuri.getagrip.ui.components.HandOrderStrip
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// REST & HANDS — the parts of a session every set genuinely shares.
///
/// **ABOVE the set list: constants above variables** — see `RoutineBuilderScreen`.
///
/// Hold and rest moved onto each set when the Max day protocol exposed the flaw (Nuri,
/// 2026-08-10: "what if for one pull you want 10 seconds and for the other 20?"). What is left
/// cannot vary per set: the set break, how hands share the work, when a rest starts counting.
@Composable
fun RhythmSection(
    /// Only what this card draws — see `RhythmValues`; the whole draft redrew it per name keystroke.
    rhythm: RhythmValues,
    modifier: Modifier = Modifier,
    update: DraftUpdate,
) {
    val palette = LocalGripPalette.current
    fun edit(transform: (SessionPlan) -> SessionPlan) = update { it.copy(plan = transform(it.plan)) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // A plain row: a pinned header leaves content scrolling illegibly behind it.
        CapsLabel(tr("REST & HANDS"))

        Surface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IntValueRow(
                    title = tr("Break between sets"),
                    value = rhythm.setBreakSeconds,
                    range = 0..240,
                    unit = tr("s"),
                    limit = SessionPlan.setBreakRange,
                    control = ValueControl.Dial(listOf(0.0, 30.0, 60.0, 90.0, 120.0, 180.0)),
                ) { seconds -> edit { it.copy(setBreakSeconds = seconds) } }

                // Under the break, not in Fine tuning: it decides when every rest STARTS, and a rest number
                // whose meaning is set two cards away cannot be trusted.
                ToggleRow(
                    title = tr("Start the rest when I let go"),
                    checked = rhythm.waitForReleaseBeforeRest,
                    // ON by default: otherwise the two or three seconds of standing down off a 20 mm edge come
                    // out of every rest. Off is a real choice — a fixed cadence you pace yourself to.
                    explainer = if (rhythm.waitForReleaseBeforeRest) {
                        tr("The hold ends on time; the rest waits until you are off the edge.")
                    } else {
                        tr("The rest starts the moment the hold ends, whether or not you have let go.")
                    },
                ) { waits -> edit { it.copy(waitForReleaseBeforeRest = waits) } }

                HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.22f))

                // Categorical, so chips — ALWAYS expanded: the strip below is the only place the app shows
                // what "alternate each pull" does.
                CapsLabel(tr("HANDS"), Modifier.padding(top = 2.dp))
                HandModeChipRow(rhythm.handMode) { mode -> edit { it.copy(handMode = mode) } }
                // The FIRST set's sequence, the one about to be done; `executable` so an emptied row cannot decide it.
                HandOrderStrip(
                    mode = rhythm.handMode,
                    repsPerSide = rhythm.firstRepsPerSide,
                    startingHand = rhythm.startingHand,
                )
                if (rhythm.handMode.sideCount > 1) {
                    // The strip has no legend, so it cannot say which hand starts (Nuri, 2026-09-18). The
                    // sentence says it; the button swaps it.
                    val startsRight = rhythm.startingHand == Side.right
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (startsRight) tr("Starts on the right hand") else tr("Starts on the left hand"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        SwapHandsAction(startsRight = startsRight) { hand -> edit { it.copy(startingHand = hand) } }
                    }
                }
            }
        }
    }
}

/// The one writer of `startingHand` (Nuri, 2026-09-18), beside the sentence naming the start:
/// tap and both flip. Hidden under Both hands. The spoken description names the tap's OUTCOME.
@Composable
private fun SwapHandsAction(startsRight: Boolean, onSwap: (Side) -> Unit) {
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val outcome = if (startsRight) tr("Start with the left hand") else tr("Start with the right hand")
    TextButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSwap(if (startsRight) Side.left else Side.right)
        },
        interactionSource = interaction,
        shape = CircleShape,
        border = BorderStroke(1.dp, palette.inkTertiary.copy(alpha = 0.35f)),
        colors = ButtonDefaults.textButtonColors(contentColor = palette.inkSecondary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .pressFeedback(interaction, scales = false)
            .semantics { contentDescription = outcome },
    ) {
        Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(tr("Swap"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/// A switch with its consequence said underneath either way: a silent off-state makes you flip
/// it to learn what it does.
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
        // **THE WHOLE ROW IS THE SWITCH.** As two stops, TalkBack reads a sentence then a nameless
        // "Switch, off"; `toggleable` on the row ties the toggle to its words. It is also the
        // hit-target rule: a full-width row tappable only on a 32 dp switch is half dead. The `Switch`
        // takes a NULL callback, or the change would fire twice.
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
            RhythmSection(RhythmValues.of(RoutineDraft.starter.plan)) {}
        }
    }
}
