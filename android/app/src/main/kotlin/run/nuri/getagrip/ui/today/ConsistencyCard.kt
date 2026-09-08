// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.today

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
import java.time.format.DateTimeFormatter
import java.util.Locale
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
/// No streak, no badge, no score, no praise — and deliberately **no aggregate number in the
/// header**: "11 of 14 days" is a score, and a score is the first step toward a streak. The
/// strip IS the summary. The aggregate exists only in the spoken value, where it has to be a
/// description because a picture cannot be spoken.
@Composable
fun ConsistencyCard(
    days: List<DayRecord>,
    modifier: Modifier = Modifier,
    /// Logging a climb lives HERE rather than in the routine card's ⋯ menu, which is for
    /// managing the routine — a gym session is not a fact about the routine. It sits on the
    /// strip because the strip is what it changes: the control is next to the thing it
    /// affects, which is the whole of good mapping.
    onLogSession: () -> Unit,
) {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        // The tour lights the whole strip: "one mark a day", and the row that logs a climb
        // or a gauge-free hang into it.
        modifier = modifier.fillMaxWidth().tourAnchor(TourTarget.Consistency),
    ) {
        // 12 rather than the house 16 vertically: this is the least load-bearing card on
        // Today, and it is where the last few points came from when the page had to fit
        // under a large title without scrolling.
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CapsLabel(tr("Last 14 days"), Modifier.weight(1f))
                LogSessionButton(onLogSession)
            }

            ConsistencyStrip(days)

            days.firstOrNull()?.let { first ->
                // The strip speaks the whole fortnight in one sentence; the axis is a visual
                // aid to it, not a second element to swipe through.
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

private val AXIS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

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
            // Not "at the climbing gym": this sheet also logs hangs done away from the
            // gauge, and a label naming only one of them hides the other entirely from
            // anyone who never sees the button's own text.
            .clearAndSetSemantics {
                // `clearAndSetSemantics` wipes the role `clickable` published, so it has to
                // be restated here — a control TalkBack calls neither button nor link is a
                // sentence it reads and nobody knows to double-tap.
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
    /// Earlier than any routine existed. A hairline, not a hole — drawing those days as
    /// empty circles would tell someone they failed on days they did not own the app. This
    /// is the most important honesty detail on the screen.
    data object BeforeHistory : DayMark
    data object Missed : DayMark
    /// At least one session, under target. The fill is the CONTINUOUS fraction, which
    /// reduces to exactly full/half/empty at two sessions a day and stays truthful at three.
    data class Partial(val fraction: Double) : DayMark
    data object Full : DayMark
    /// A day spent at the climbing gym. FULL — a climb completes the day — but drawn with a
    /// notch so it is not mistaken for a hangboard day.
    data object Climbed : DayMark
    /// A max-testing day. FULL, bleu, and bored — the same glyph History's grid draws, so
    /// the vocabulary is learned once.
    data object Benchmarked : DayMark
}

internal fun markFor(record: DayRecord): DayMark {
    if (!record.tracked) return DayMark.BeforeHistory
    // Asked FIRST: a climb settles the day whatever the hang count beside it, so a
    // climb-plus-nothing day must never fall through to `Missed`.
    if (record.climb != null) return DayMark.Climbed
    if (record.benchmarked) return DayMark.Benchmarked
    if (record.completed == 0) return DayMark.Missed
    if (record.fraction >= 1) return DayMark.Full
    return DayMark.Partial(record.fraction)
}

@Composable
private fun ConsistencyStrip(days: List<DayRecord>) {
    // CAPPED, and the cap is load-bearing. Each cell's ideal width is this dot, so fourteen
    // of them at an unclamped accessibility scale come to ~406 dp against a 402 dp screen —
    // and because the enclosing column sizes itself from its children, that widened the
    // whole content column and clipped the device chip, the routine title and the summary
    // row off BOTH edges of the screen.
    //
    // A strip is a sparkline: it exists to be glanced at, and it has to FIT. Anyone who
    // needs the detail at a readable size has History's month grid, which is built for it.
    val scaled = 12.dp * LocalDensity.current.fontScale
    val dot: Dp = minOf(scaled, 22.dp)
    val summary = remember(days) { spokenSummary(days) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(dot + 6.dp)
            // ONE element. Fourteen focusable dots is swipe torture, and day-by-day detail
            // belongs to History's month view.
            .clearAndSetSemantics {
                contentDescription = L10n.tr("Last 14 days")
                stateDescription = summary
            },
        verticalAlignment = Alignment.Top,
    ) {
        days.forEachIndexed { index, record ->
            // Spacing 0 with equal-width cells: the pitch DERIVES from the available width,
            // so the strip fits every device and every text size without a magic number.
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
            // An under-tick, not a halo: today is still winnable, and at 0 of 2 at 8 a.m. it
            // must not read as a failure. A halo would also fight the fractional fill
            // sitting inside it.
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
                // The fill is CLIPPED from the leading edge rather than drawn as an arc: a
                // half-filled circle has to read as "half", and a pie slice reads as a
                // different quantity at 12 dp.
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

/// The only place an aggregate is allowed to exist, and it is phrased as a description
/// rather than a score. Days before the routine existed are excluded from the missed count
/// here exactly as they are in the drawing.
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
        // Named, not folded into "complete": the notch is a distinction the drawing makes,
        // so the spoken version has to make it too.
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
