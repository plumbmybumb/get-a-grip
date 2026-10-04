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
import androidx.compose.ui.unit.Dp
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

/// The runner's time bar (iOS `RunnerTimeBar`): ONE bar that is always there, so the panel
/// never changes shape between pull and rest (owner, 2026-09-25).
///
/// - Pulling: the hold bar (the system track, bleu fill growing with held time).
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
internal fun RunnerTimeBar(session: RunnerSession, mode: TimeBarMode, identity: Int, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val key = TimeBarKey(isRest = mode == TimeBarMode.countdown, identity = identity)
    Box(
        modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth().height(TIME_BAR_HEIGHT)
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

internal val TIME_BAR_HEIGHT = 6.dp
internal val PILL_HEIGHT = 3.dp

/// The routine's pills (iOS `RoutinePills`): every pull of every set, done in ink, skipped lighter, and the pull
/// this is all about marked. SECONDARY to the time bar above: slimmer, on a lighter track.
///
/// The current pill wears the runner's own phase tint (`RunnerTint`, Nuri 2026-10-04): bleu
/// while the clock runs, amber while it waits on you (pull, re-grip, ease off, let go),
/// steel at rest — so the line and the screen's border always say the same thing.
/// Done stays at 0.6 ink: at 3 pt, 0.46 measured 2.5:1 against the track (thin shapes are
/// mostly antialiased edge).
///
/// Coarse: it reads only its model, which changes when a pull is recorded or the phase moves.
///
/// `thickness` is the PREFERRED height: the timer-only runner, with no trace to share the
/// screen with, draws the pills thicker (iOS `RoutinePills.thickness`). A dense plan still
/// thins them so every pill stays at least 1.5× as long as it is tall — see `drawnThickness`.
@Composable
internal fun RoutinePills(
    model: SessionProgressModel,
    currentTint: Color,
    modifier: Modifier = Modifier,
    thickness: Dp = PILL_HEIGHT,
) {
    val palette = LocalGripPalette.current
    val track = palette.pillTrack()
    val done = palette.inkPrimary.copy(alpha = 0.6f)
    val skipped = palette.inkPrimary.copy(alpha = 0.2f)
    Canvas(modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth().height(thickness)
        .testTag("runner.routinePills").clearAndSetSemantics {}) {
        val cells = RoutinePillLayout.cells(model.setSizes, size.width, unit = density)
        val drawn = drawnThickness(size.height, PILL_HEIGHT.toPx(), cells)
        val top = (size.height - drawn) / 2
        drawSlots(cells, cells.indices, track, top, drawn)
        drawSlots(cells, model.finished.indices.filter { model.finished[it] }, done, top, drawn)
        drawSlots(cells, model.finished.indices.filter { !model.finished[it] }, skipped, top, drawn)
        model.current?.let { drawSlots(cells, listOf(it), currentTint, top, drawn) }
    }
}

/// Never thinner than the measured runner's pills, never so thick that a pill's length falls
/// under 1.5× its height — a circle is a session in this app.
internal fun drawnThickness(preferred: Float, minimum: Float, cells: List<ClosedFloatingPointRange<Float>>): Float {
    val first = cells.firstOrNull() ?: return minimum
    if (preferred <= minimum) return preferred
    return minOf(preferred, maxOf(minimum, (first.endInclusive - first.start) / 1.5f))
}

/// Round-ended at the line's own height, so a slot is a short piece of the same capsule.
/// Slots that TOUCH meet square: rounding them would notch a continuous line at every pull.
private fun DrawScope.drawSlots(
    cells: List<ClosedFloatingPointRange<Float>>,
    include: Iterable<Int>,
    color: Color,
    top: Float,
    height: Float,
) {
    val radius = height / 2
    for (index in include) {
        if (index !in cells.indices) continue
        val cell = cells[index]
        val width = cell.endInclusive - cell.start
        if (width <= 0.01f) continue
        val touches = (index > 0 && cell.start - cells[index - 1].endInclusive < 0.5f) ||
            (index + 1 < cells.size && cells[index + 1].start - cell.endInclusive < 0.5f)
        val r = if (touches) 0f else minOf(radius, width / 2)
        drawRoundRect(color, Offset(cell.start, top), Size(width, height), CornerRadius(r))
    }
}
