// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.criticalforce

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.data.latestHands
import run.nuri.getagrip.data.latestVisit
import run.nuri.getagrip.data.newest
import run.nuri.getagrip.data.initial
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.engine.CriticalForceRules
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.LocalDayClock
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.maxes.relative
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.armedText
import run.nuri.getagrip.ui.units.WeightUnits

/// Critical force on Today: ONE line, never a card of its own.
///
/// Today is the daily ritual, and a critical force test happens every four to eight weeks,
/// so it earns a line and not a block. The line states the latest result and is the door
/// to the next test. It turns amber only when a retest is due, because amber means "this
/// is waiting on you" everywhere else in the app.
///
/// Reads the store itself, in a leaf, so a saved test redraws this line and nothing else.
///
/// TRANSLATION NOTE: "Retest due" is drawn in `armedText`, the readable amber ink, rather
/// than the raw `armed` fill: bright orange on a light card measures under 3:1 as text.
@Composable
fun CriticalForceTodayLine(
    onTest: (GripSpec, CriticalForceHands) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val clock = LocalDayClock.current
    val records = templates.criticalForceRecords
    // The newest VISIT: both hands when they were tested one at a time, left before right.
    val visit = records.latestVisit
    // The newest test, for the retest clock and for a single-hand line.
    val latest: CriticalForceRecordEntity? = records.newest
    // Read off `DayClock`, so it flips when the day rolls under an open phone.
    val isDue = latest != null &&
        clock.today - DayStamp.trainingDayOf(latest.recordedAt) >= CriticalForceRules.retestAfterDays
    // Stacked at accessibility sizes: in one row the label and the kilograms want more width
    // than the phone has (measured on iOS at the largest size, 2026-09-25).
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val interaction = remember { MutableInteractionSource() }

    // Two hands: "L 17.2 · R 16.1 kg". The percentages would not fit, and the card on
    // Benchmarks has them.
    val value = if (visit.size > 1) {
        visit.joinToString(" · ") { "${it.side.initial} ${WeightUnits.number(it.criticalForceKg)}" } +
            " ${WeightUnits.symbol}"
    } else latest?.let { WeightUnits.text(it.criticalForceKg) }
    val percent = if (visit.size > 1) null else latest?.percentOfMax?.let { tr("%d %% of max", roundedPercent(it)) }
    val empty = tr("Test your endurance · 4 min")
    val due = tr("Retest due")
    val spoken = listOfNotNull(
        L10n.tr("Critical force"),
        if (latest == null) empty else value,
        percent,
        if (isDue) due else null,
    ).joinToString(", ")
    val tested = latest?.let { L10n.tr("Tested %s", relative(it.recordedAt)) }
    val hint = if (latest == null) L10n.tr("Opens the four-minute critical force test.")
    else L10n.tr("Opens a new critical force test.")

    fun open() {
        val hands = records.latestHands ?: CriticalForceHands.OneAtATime(Side.left)
        if (latest != null) onTest(latest.grip, hands)
        else onTest(templates.recentGrips.firstOrNull() ?: GripSpec(), CriticalForceHands.OneAtATime(Side.left))
    }

    Surface(
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = palette.card,
        modifier = modifier
            .fillMaxWidth()
            .testTag("today.criticalForce")
            .pressFeedback(interaction, scales = false),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                // The WHOLE row is the tap target, not the label's opaque content.
                .clickable(interactionSource = interaction, indication = null, onClick = ::open)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = "$spoken. $hint"
                    tested?.let { stateDescription = it }
                    onClick { open(); true }
                }
                .padding(horizontal = 16.dp, vertical = if (stacked) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val content: @Composable () -> Unit = {
                CapsLabel(tr("Critical force"))
                if (latest != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            value.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                            maxLines = 1,
                        )
                        percent?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary,
                                maxLines = 1)
                        }
                    }
                } else {
                    Text(empty, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                }
                if (isDue && stacked) DueText(due)
            }
            if (stacked) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
            } else {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { content() }
            }
            // Trailing, and only when a retest is due. The age alone is not worth the width: it
            // pushed the "% of max" off a phone-width line, and "due" is the one thing about age
            // that asks anything of you.
            if (isDue && !stacked) DueText(due)
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DueText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
        color = LocalGripPalette.current.armedText, maxLines = 1)
}
