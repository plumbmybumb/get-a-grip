// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.runner

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.ui.theme.DarkPalette
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// The tracks the system draws bars on, as iOS's `systemFill` (the hold bar) and the lighter
/// `tertiarySystemFill` (the routine pills), matched per scheme.
internal fun GripPalette.timeTrack(): Color =
    Color(0xFF787880).copy(alpha = if (this == DarkPalette) 0.36f else 0.20f)

internal fun GripPalette.pillTrack(): Color =
    Color(0xFF767680).copy(alpha = if (this == DarkPalette) 0.24f else 0.12f)

/// STACKED's top row: ONE bar that is always there, so the panel never changes shape between
/// pull and rest (owner, 2026-09-25).
///
/// - Pulling: today's hold bar exactly (the system track, bleu fill growing with held time).
///   Released but still on the edge: full. Armed: empty, waiting.
/// - Rest, set break, count-in: the rest's own countdown, DRAINING from full to empty in the
///   calm steel. Drain, not fill, because it is time REMAINING, and it makes both seams
///   continuous — a finished hold is a FULL bleu bar and a new rest is a FULL steel bar (only
///   the colour changes), and a rest that has run out is an EMPTY bar exactly where the next
///   hold starts.
/// - Paused: frozen — the engine reads a paused countdown against `pausedAt`.
///
/// A LEAF: it alone reads `repProgress` and `phaseRemainingFraction`, both sample- or
/// tick-rate. The fraction is the engine's own countdown, the one behind the rest numeral.
@Composable
internal fun StackedTimeBar(session: RunnerSession, mode: TimeBarMode, identity: Int, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val key = TimeBarKey(isRest = mode == TimeBarMode.countdown, identity = identity)
    Box(
        modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth().height(STACKED_TIME_BAR_HEIGHT)
            .clip(CircleShape).background(palette.timeTrack())
            .testTag("runner.timeBar").clearAndSetSemantics {},
    ) {
        // Each pull and each rest gets a fresh layer: the hold that ends fades out FULL, the
        // rest that ends fades out EMPTY — never a fill draining back the wrong way.
        Crossfade(targetState = key, animationSpec = Motion.state(reduceMotion), label = "time bar") { shown ->
            val fraction = if (shown == key) timeBarFraction(mode, session.repProgress, session.phaseRemainingFraction)
            else if (shown.isRest) 0f else 1f
            val animated by animateFloatAsState(
                targetValue = fraction,
                animationSpec = if (reduceMotion) snap() else Motion.measuredProgress(),
                label = "time bar fill",
            )
            // A rectangle clipped by the track, as the system bar draws it: the last second
            // of a rest is a sliver on the rounded end, not a round DOT.
            Box(Modifier.fillMaxHeight().fillMaxWidth(animated)
                .background(if (shown.isRest) palette.calm else palette.bleu))
        }
    }
}

private data class TimeBarKey(val isRest: Boolean, val identity: Int)

internal val STACKED_TIME_BAR_HEIGHT = 6.dp
internal val STACKED_PILL_HEIGHT = 3.dp
internal val UNDERLINE_HEIGHT = 2.dp

/// The routine's pills: every pull of every set, done in ink, skipped lighter, and the pull
/// this is all about marked. SECONDARY to the time bar above: slimmer, on a lighter track.
///
/// While a pull runs its pill is marked in INK — the time bar is the one bleu thing on the
/// panel. At rest the bar turns steel and the next pill takes the bleu, pointing forward.
/// Done stays at 0.6 ink: at 3 pt, 0.46 measured 2.5:1 against the track (thin shapes are
/// mostly antialiased edge).
///
/// Coarse: it reads only its model, which changes when a pull is recorded or the phase moves.
@Composable
internal fun StackedRoutinePills(model: SessionProgressModel, isLive: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val track = palette.pillTrack()
    val done = palette.inkPrimary.copy(alpha = 0.6f)
    val skipped = palette.inkPrimary.copy(alpha = 0.2f)
    val next = if (isLive) palette.inkPrimary.copy(alpha = 0.85f) else palette.bleu.copy(alpha = 0.72f)
    Canvas(modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth().height(STACKED_PILL_HEIGHT)
        .testTag("runner.routinePills").clearAndSetSemantics {}) {
        val cells = ZoomLayout.cells(model.setSizes, size.width, unit = density)
        drawSlots(cells, cells.indices, track)
        drawSlots(cells, model.finished.indices.filter { model.finished[it] }, done)
        drawSlots(cells, model.finished.indices.filter { !model.finished[it] }, skipped)
        model.current?.let { drawSlots(cells, listOf(it), next) }
    }
}

/// UNDERLINE: today's bar untouched, and under it the whole routine as a 2 pt line — hairline
/// set breaks, done in ink, the current pull in bleu.
@Composable
internal fun RoutineUnderline(model: SessionProgressModel, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val track = palette.timeTrack()
    val done = palette.inkPrimary.copy(alpha = 0.55f)
    val skipped = palette.inkPrimary.copy(alpha = 0.26f)
    val bleu = palette.bleu
    Canvas(modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth().height(UNDERLINE_HEIGHT)
        .testTag("runner.routineUnderline").clearAndSetSemantics {}) {
        val cells = ZoomLayout.cells(model.setSizes, size.width, pullGap = 0f, setGap = 2f, unit = density)
        drawSlots(cells, cells.indices, track)
        drawSlots(cells, model.finished.indices.filter { model.finished[it] }, done)
        drawSlots(cells, model.finished.indices.filter { !model.finished[it] }, skipped)
        model.current?.takeIf { it in cells.indices }?.let { current ->
            // A tick at least 3 pt wide, so a 60-pull routine still shows it.
            val cell = cells[current]
            val width = maxOf(3 * density, cell.endInclusive - cell.start)
            val x = minOf(cell.start, size.width - width)
            drawRoundRect(bleu, Offset(x, 0f), Size(width, size.height), CornerRadius(size.height / 2))
        }
    }
}

/// Round-ended at the line's own height, so a slot is a short piece of the same capsule.
/// Slots that TOUCH meet square: rounding them would notch a continuous line at every pull.
private fun DrawScope.drawSlots(cells: List<ClosedFloatingPointRange<Float>>, include: Iterable<Int>, color: Color) {
    val radius = size.height / 2
    for (index in include) {
        if (index !in cells.indices) continue
        val cell = cells[index]
        val width = cell.endInclusive - cell.start
        if (width <= 0.01f) continue
        val touches = (index > 0 && cell.start - cells[index - 1].endInclusive < 0.5f) ||
            (index + 1 < cells.size && cells[index + 1].start - cell.endInclusive < 0.5f)
        val r = if (touches) 0f else minOf(radius, width / 2)
        drawRoundRect(color, Offset(cell.start, 0f), Size(width, size.height), CornerRadius(r))
    }
}
