// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.today

import run.nuri.getagrip.ui.l10n.LocalizedPattern
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.DayRecord
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.l10n.trQuantity
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.benchmarkBore
import run.nuri.getagrip.ui.components.climbNotch
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.tour.TourTarget
import run.nuri.getagrip.ui.tour.tourAnchor

/// Fourteen days, oldest to newest, ending today.
///
/// No streak, badge, score or praise — and **no aggregate number in the header**: "11 of 14
/// days" is a score, the first step toward a streak. The strip IS the summary; the aggregate
/// exists only in the spoken value, because a picture cannot be spoken.
@Composable
fun ConsistencyCard(
    days: List<DayRecord>,
    modifier: Modifier = Modifier,
    onShowHistory: () -> Unit = {},
    /// Logging lives HERE, not in the routine's ⋯ menu: a gym session is not a fact about the
    /// routine, and the control sits next to the strip it changes.
    onLogSession: () -> Unit,
) {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        // The tour lights the whole strip, including the row that logs into it.
        modifier = modifier.fillMaxWidth().tourAnchor(TourTarget.Consistency),
    ) {
        // 12, not the house 16, vertically: the least load-bearing card on Today gave up the points
        // the page needed to fit.
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp)
                        .clickable(role = Role.Button, onClick = onShowHistory),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CapsLabel(tr("Last 14 days"))
                }
                LogSessionButton(onLogSession)
            }

            // Only the history body navigates; Log is a sibling target, so it never also switches tabs.
            Column(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .testTag("today.history")
                    .clickable(role = Role.Button, onClick = onShowHistory)
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = L10n.tr("History")
                        stateDescription = spokenSummary(days)
                        onClick(label = L10n.tr("History")) { onShowHistory(); true }
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConsistencyStrip(days)
                days.firstOrNull()?.let { first ->
                    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
                        CapsLabel(AXIS_DATE.format(first.day.localDate()), Modifier.weight(1f))
                        CapsLabel(tr("Today"))
                    }
                }
                if (ConsistencyEmptyState.showsFirstUseHint(days)) {
                    Text(
                        tr("Your sessions will show up here."),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                    )
                }
            }
        }
    }
}

private val AXIS_DATE = LocalizedPattern("d MMM")

@Composable
private fun LogSessionButton(onLogSession: () -> Unit) {
    val palette = LocalGripPalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onLogSession)
            .pressFeedback(interaction)
            .padding(horizontal = Metrics.buttonHorizontalPadding, vertical = Metrics.buttonVerticalPadding)
            // Not "at the climbing gym": the sheet also logs gauge-free hangs, and naming one hides the
            // other from TalkBack.
            .clearAndSetSemantics {
                // `clearAndSetSemantics` wipes `clickable`'s role; restate it, or TalkBack reads a sentence
                // nobody knows to double-tap.
                role = Role.Button
                contentDescription = L10n.tr("Log a session you did elsewhere — climbing, or hangs off the gauge")
                onClick(label = L10n.tr("Log a session")) { onLogSession(); true }
            },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Terrain, contentDescription = null, tint = palette.graphite, modifier = Modifier.size(16.dp))
        Text(
            tr("Log a session"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.graphite,
        )
    }
}

// MARK: - The strip

/// How one day draws. Every state is SHAPE-encoded, never colour-only, so the strip survives
/// greyscale and colourblindness intact.
internal sealed interface DayMark {
    /// Before any routine existed. A hairline, not a hole: empty circles would say someone failed
    /// on days they did not own the app. The most important honesty detail on the screen.
    data object BeforeHistory : DayMark
    data object Missed : DayMark
    /// Under target. The CONTINUOUS fraction: exactly full/half/empty at two a day, truthful at three.
    data class Partial(val fraction: Double) : DayMark
    data object Full : DayMark
    /// A climbing-gym day: FULL (a climb completes the day), notched so it is not a hangboard day.
    data object Climbed : DayMark
    /// A max-testing day: FULL, bleu, and bored — History's glyph, so it is learned once.
    data object Benchmarked : DayMark
}

internal fun markFor(record: DayRecord): DayMark {
    if (!record.tracked) return DayMark.BeforeHistory
    // FIRST: a climb settles the day, so climb-plus-nothing never falls through to `Missed`.
    if (record.climb != null) return DayMark.Climbed
    if (record.benchmarked) return DayMark.Benchmarked
    if (record.completed == 0) return DayMark.Missed
    if (record.fraction >= 1) return DayMark.Full
    return DayMark.Partial(record.fraction)
}

@Composable
private fun ConsistencyStrip(days: List<DayRecord>) {
    // CAPPED, and load-bearing: fourteen unclamped dots at accessibility scale are ~406 dp
    // against a 402 dp screen, and the column sizes from its children, so it widened the whole
    // page and clipped the chip, title and summary off BOTH edges.
    //
    // A strip is a sparkline and must FIT; History's month grid has the readable detail.
    val scaled = 12.dp * LocalDensity.current.fontScale
    val dot: Dp = minOf(scaled, 22.dp)
    val summary = remember(days) { spokenSummary(days) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(dot + 6.dp)
            // ONE element: fourteen focusable dots is swipe torture.
            .clearAndSetSemantics {
                contentDescription = L10n.tr("Last 14 days")
                stateDescription = summary
            },
        verticalAlignment = Alignment.Top,
    ) {
        days.forEachIndexed { index, record ->
            // Equal-width cells with no spacing: the pitch DERIVES from the width, fitting every device
            // and text size.
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                DayCell(record, dot, isToday = index == days.size - 1)
            }
        }
    }
}

@Composable
private fun DayCell(record: DayRecord, dot: Dp, isToday: Boolean) {
    val palette = LocalGripPalette.current
    Box(Modifier.size(width = dot, height = dot + 6.dp), contentAlignment = Alignment.TopCenter) {
        Glyph(markFor(record), dot)
        if (isToday) {
            // An under-tick, not a halo: 0 of 2 at 8 a.m. is still winnable, not a failure, and a halo
            // would fight the fill.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = dot + 3.dp)
                    .size(width = dot, height = 3.dp)
                    .background(palette.graphite.copy(alpha = 0.6f), CircleShape),
            )
        }
    }
}

@Composable
private fun Glyph(mark: DayMark, dot: Dp) {
    val palette = LocalGripPalette.current
    when (mark) {
        DayMark.BeforeHistory -> Box(
            Modifier
                .padding(top = dot / 2 - 1.dp)
                .size(width = dot, height = 1.5.dp)
                .background(palette.inkTertiary.copy(alpha = 0.22f), CircleShape),
        )
        // Visible, not accusatory. Never a red X.
        DayMark.Missed -> Box(
            Modifier.size(dot).border(1.2.dp, palette.inkTertiary.copy(alpha = 0.45f), CircleShape),
        )
        is DayMark.Partial -> {
            val graphite = palette.graphite
            val ring = palette.inkTertiary.copy(alpha = 0.45f)
            Canvas(Modifier.size(dot)) {
                val r = size.minDimension / 2f
                // CLIPPED from the leading edge, not an arc: at 12 dp a half circle reads as "half", a pie
                // slice as something else.
                clipRect(right = size.width * mark.fraction.toFloat()) {
                    drawCircle(graphite, radius = r, center = center)
                }
                drawCircle(
                    ring,
                    radius = r - 0.6.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
        }
        DayMark.Full -> Box(Modifier.size(dot).clip(CircleShape).background(palette.graphite))
        DayMark.Climbed -> Box(
            Modifier.size(dot).clip(CircleShape).background(palette.graphite).climbNotch(true),
        )
        DayMark.Benchmarked -> Box(
            Modifier.size(dot).clip(CircleShape).background(palette.bleu).benchmarkBore(true, dot * 0.38f),
        )
    }
}

/// The only aggregate allowed, phrased as a description, not a score. Pre-history days are
/// excluded from "missed", as in the drawing.
internal fun spokenSummary(days: List<DayRecord>): String {
    var complete = 0
    var partial = 0
    var missed = 0
    var untracked = 0
    var climbed = 0
    var benchmarked = 0
    days.dropLast(1).forEach { record ->
        when (markFor(record)) {
            DayMark.BeforeHistory -> untracked++
            DayMark.Missed -> missed++
            is DayMark.Partial -> partial++
            DayMark.Full -> complete++
            DayMark.Climbed -> climbed++
            DayMark.Benchmarked -> benchmarked++
        }
    }

    val counts = buildList {
        if (complete > 0) add(L10n.tr("%d %s complete", complete, L10n.tr(if (complete == 1) "day" else "days")))
        // Named: the notch is a distinction the drawing makes, so speech makes it too.
        if (climbed > 0) add(L10n.tr("%d at the climbing gym", climbed))
        if (benchmarked > 0) add(L10n.tr("%d max testing", benchmarked))
        if (partial > 0) add(trQuantity("%d partial", partial))
        if (missed > 0) add(trQuantity("%d missed", missed))
    }

    val sentences = mutableListOf(
        if (counts.isEmpty()) L10n.tr("No sessions in the last two weeks") else counts.joinToString(", ")
    )
    if (untracked > 0) {
        sentences.add(
            L10n.tr(
                "%d %s before this routine existed",
                untracked,
                L10n.tr(if (untracked == 1) "day" else "days"),
            )
        )
    }
    days.lastOrNull()?.let { today ->
        val target = maxOf(1, today.target)
        sentences.add(
            L10n.tr(
                "Today so far: %d of %d %s",
                today.completed,
                target,
                L10n.tr(if (target == 1) "session" else "sessions"),
            )
        )
    }
    return sentences.joinToString(". ") + "."
}

@Preview(name = "ConsistencyCard", showBackground = true, widthDp = 380)
@Composable
private fun ConsistencyCardPreview() {
    val today = DayStamp.today()
    val days = (13 downTo 0).map { back ->
        val day = today - back
        DayRecord(
            day = day,
            completed = if (back % 5 == 0) 1 else 2,
            target = 2,
            tracked = back < 11,
            climb = if (back == 3) SessionKind.climbLimit else null,
            benchmarked = back == 6,
        )
    }
    GetAGripTheme {
        Box(Modifier.padding(20.dp)) { ConsistencyCard(days) {} }
    }
}


internal object ConsistencyEmptyState {
    fun showsFirstUseHint(days: List<DayRecord>): Boolean =
        days.any { !it.tracked } && days.all { it.completed == 0 && it.climb == null && !it.benchmarked }
}
