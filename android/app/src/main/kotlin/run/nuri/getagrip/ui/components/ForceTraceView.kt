// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components


import androidx.compose.foundation.Canvas
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.ble.SystemHostClock
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// Keeps the curve off the card's edges. Without the bottom inset a resting gauge draws
/// its zero line exactly on the boundary, where the rounded corners clip it and it reads
/// as a rendering glitch rather than as "no load".
private val INSET_TOP = 12.dp
private val INSET_BOTTOM = 10.dp
private val STROKE_WIDTH = 2.5.dp
private val RULE_WIDTH = 1.dp
private val RULE_DASH = 4.dp
private val HEAD_RADIUS = 4.dp

/// The fill's left edge ramps up over this much, so history BEGINS rather than starts.
private val FILL_RAMP_WIDTH = 40.dp

/// How long the frame loop sleeps while there is nothing moving to draw. A fresh sample
/// does not have to wait for it: the trace list is read inside the draw lambda, so an
/// append invalidates the DRAW immediately — this interval only governs when the smooth
/// wall-clock slide resumes, and by then the run has just begun anyway.
private const val IDLE_POLL_MILLIS = 250L

/// The live force trace: a rolling window of the gauge's readings, scrolling smoothly.
///
/// Time-based, not index-based: the Progressor delivers ~80 Hz samples in batches of about
/// eight, so index spacing plus redraw-on-arrival stutters ten times a second. Points
/// carry a PLAYBACK time (`DeviceStore.TracePoint.t` — monotone, built at ingestion,
/// immune to the device's counter restarting on tare/reconnect/re-start), and the window's
/// right edge advances with the wall clock.
///
/// **This view is deliberately STATELESS about time.** Its iOS ancestor kept anchor state
/// (device-µs ↔ wall-clock pairs) and died on real hardware: a tare cleared the buffer, the
/// anchors survived with pre-tare values, and there was no path back — a blank graph beside
/// a live kg readout until the screen was re-entered. State that models another clock can
/// be poisoned; geometry from (now − t) cannot. **Never reintroduce view-held clock
/// state.**
///
/// **It reads the trace ITSELF**, from `LocalDeviceStore`, and reads it inside the draw
/// lambda. That is the whole performance story: the buffer is appended to ~80 times a
/// second, and a snapshot read inside a draw scope invalidates the DRAW phase only, so
/// nothing around this canvas recomposes. Handed the list as a parameter instead, every
/// sample would recompose the caller.
///
/// TRANSLATION NOTE: SwiftUI's `TimelineView(.animation(paused:))` becomes a
/// `withFrameNanos` loop writing a tick the draw lambda reads. Its pause conditions are the
/// same two — reduce motion, and a newest sample already older than the window — and the
/// iOS expiry watcher's job (noticing that a stopped trace has finally slid off) is done
/// here by the loop's own per-frame check.
@Composable
fun ForceTraceView(
    modifier: Modifier = Modifier,
    /// Drawn as a dashed rule: the load a rep has to beat for its clock to run.
    thresholdKg: Double? = null,
    /// **The range this rep is asking for, drawn as a lane to land the curve in.** When
    /// there is one it REPLACES the threshold rule rather than joining it: the band's floor
    /// is what the clock now runs off, so a third horizontal line would be a second answer
    /// to the same question.
    targetBand: ClosedFloatingPointRange<Double>? = null,
    /// The phase tint, so the graph and the rest of the screen escalate together.
    tint: Color = LocalGripPalette.current.bleu,
    clock: HostClock = SystemHostClock,
) {
    val store = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    DisposableEffect(store) {
        store.pipelineDiagnostics.graphOpened()
        onDispose { store.pipelineDiagnostics.graphClosed() }
    }
    val axis = remember { TraceGeometry.AxisMemory() }
    val paths = remember { TracePaths() }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(reduceMotion, store, clock) {
        while (isActive) {
            // Read from a coroutine, not from composition or draw: snapshot reads out here
            // register no observer, so polling the newest sample costs nothing.
            val newest = store.trace.lastOrNull()?.t
            val expired = newest == null ||
                clock.wallSeconds() - newest >= TraceGeometry.WINDOW_SECONDS
            // Paused under Reduce Motion, so the canvas redraws only when data changes:
            // someone who asked for less motion should not be given a continuously sliding
            // graph.
            if (reduceMotion || expired) {
                delay(IDLE_POLL_MILLIS)
                continue
            }
            withFrameNanos { tick += 1 }
        }
    }

    // The clip matters: the off-screen anchor can land well left of x = 0 on a
    // sparse-delivery gauge, and without it the overhanging segment would draw outside the
    // card rather than being invisible, as intended, past the edge.
    // Decoration, and the ONE drawing in the app that redraws at 80 Hz. Every other Canvas
    // here carries this; without it a caller that put a `semantics` node above this one would
    // make an 80 Hz picture a TalkBack focus target. The runner's counters row is the
    // accessible channel for what the trace shows.
    Canvas(modifier.clipToBounds().clearAndSetSemantics {}) {
        // READING the tick here is the whole subscription — a snapshot read inside a draw
        // scope invalidates the draw phase and nothing else, which is why the frame clock
        // cannot recompose anything around this canvas.
        @Suppress("UNUSED_VARIABLE")
        val frame = tick
        store.pipelineDiagnostics.drawing(clock.uptimeSeconds())
        val samples = store.trace
        val caps = store.gaugeCapabilities
        val now = clock.wallSeconds()
        val ceiling = axis.ceiling(
            samples = samples,
            thresholdKg = thresholdKg,
            bandHiKg = targetBand?.endInclusive,
            now = now,
            reduceMotion = reduceMotion,
        )
        drawTrace(
            paths = paths,
            samples = samples,
            now = now,
            ceiling = ceiling,
            thresholdKg = thresholdKg,
            targetBand = targetBand,
            tint = tint,
            neutral = palette.inkTertiary,
            gapSeconds = TraceGeometry.streamGapSeconds(caps.nominalSampleRate, caps.isBroadcast),
        )
    }
}

private class TracePaths {
    val line = Path()
    val fill = Path()
    val layer = Paint()
}

private fun DrawScope.drawTrace(
    paths: TracePaths,
    samples: List<DeviceStore.TracePoint>,
    now: Double,
    ceiling: Double,
    thresholdKg: Double?,
    targetBand: ClosedFloatingPointRange<Double>?,
    tint: Color,
    neutral: Color,
    gapSeconds: Double,
) {
    val insetTop = INSET_TOP.toPx()
    val plotHeight = maxOf(1f, size.height - insetTop - INSET_BOTTOM.toPx())

    fun y(kg: Double): Float {
        val fraction = kg.coerceIn(0.0, ceiling) / ceiling
        return insetTop + plotHeight - fraction.toFloat() * plotHeight
    }

    val dash = PathEffect.dashPathEffect(floatArrayOf(RULE_DASH.toPx(), RULE_DASH.toPx()))

    if (targetBand != null) {
        // A LANE, not two lines. The pair of dashed rules alone left the eye to work out
        // which side of each one it was on; a filled band is a place to be, and the curve
        // is either in it or it isn't. Neutral ink deliberately — the TRACE carries the
        // phase colour, and a tinted lane behind a tinted curve would put two competing
        // signals in the same square inch.
        val top = y(targetBand.endInclusive)
        val bottom = y(targetBand.start)
        drawRect(
            color = neutral.copy(alpha = 0.13f),
            topLeft = Offset(0f, top),
            size = Size(size.width, maxOf(1f, bottom - top)),
        )
        for (edge in listOf(top, bottom)) {
            drawLine(
                color = neutral.copy(alpha = 0.5f),
                start = Offset(0f, edge),
                end = Offset(size.width, edge),
                strokeWidth = RULE_WIDTH.toPx(),
                pathEffect = dash,
            )
        }
    } else if (thresholdKg != null && thresholdKg > 0 && thresholdKg < ceiling) {
        val line = y(thresholdKg)
        drawLine(
            color = neutral.copy(alpha = 0.55f),
            start = Offset(0f, line),
            end = Offset(size.width, line),
            strokeWidth = RULE_WIDTH.toPx(),
            pathEffect = dash,
        )
    }

    if (samples.size < 2) return
    val newest = samples.last()

    // How far the window's right edge has slid past the newest point. Wall-clock, so it
    // grows every frame; `t` is the store's slewed playback time, so radio jitter doesn't
    // move the trace.
    val drift = maxOf(0.0, now - newest.t)

    val runStart = TraceGeometry.runStart(samples, gapSeconds)
    val anchor = TraceGeometry.anchorIndex(samples, runStart, newest.t, drift, size.width)

    // **THE TRACE FADES BACK IN.** Coming back from the home screen the buffer starts again
    // from nothing, and a graph that simply appears — two seconds wide, mid-card — reads as
    // a glitch rather than as a recording resuming. Derived from the DATA, with no state at
    // all: nothing to reset, nothing to poison.
    val runAlpha = minOf(
        1.0,
        (now - samples[runStart].t) / TraceGeometry.FADE_IN_SECONDS,
    ).coerceAtLeast(0.0).toFloat()
    if (runAlpha <= 0f) return

    val line = paths.line.apply { reset() }
    var firstDrawn: Offset? = null
    for (index in anchor until samples.size) {
        val point = Offset(
            TraceGeometry.x(samples[index].t, newest.t, drift, size.width),
            y(TraceGeometry.smoothed(samples, index)),
        )
        if (firstDrawn == null) {
            line.moveTo(point.x, point.y)
            firstDrawn = point
        } else {
            line.lineTo(point.x, point.y)
        }
    }
    val first = firstDrawn ?: return

    // WHILE DATA IS FLOWING, the head rides the right edge — see
    // `TraceGeometry.headAverageKg`. The 0.5 s staleness cap keeps the old behaviour after
    // stop/disconnect: no fresh data, no synthetic head.
    val head: Offset
    if (drift < TraceGeometry.HEAD_FRESHNESS_SECONDS) {
        head = Offset(size.width, y(TraceGeometry.headAverageKg(samples)))
        line.lineTo(head.x, head.y)
    } else {
        val last = samples.size - 1
        head = Offset(
            TraceGeometry.x(samples[last].t, newest.t, drift, size.width),
            y(TraceGeometry.smoothed(samples, last)),
        )
    }

    // Soft fill under the curve reads as "load", the stroke reads as "now".
    //
    // **Closed at the line's OWN first x, never at 0.** With a full buffer the curve
    // already starts off the left edge and the two are the same point, which is why this
    // went unnoticed for so long — but any short buffer (a fresh session, a tare, coming
    // back from the home screen) starts the line mid-canvas, and closing at 0 drew a
    // diagonal from the bottom-left corner up to it: the "weird shadow under the graph" in
    // Nuri's screenshots. The fill drops straight down from where the data actually begins.
    val fill = paths.fill.apply {
        reset()
        addPath(line)
        lineTo(head.x, size.height)
        lineTo(first.x, size.height)
        close()
    }

    // FADED IN FROM THE LEFT. Closing the fill under its first point is correct, but on a
    // short buffer it drops a full-strength vertical cliff in the middle of the card, which
    // reads as a wall of load that was never pulled. The ramp exists for a run that BEGINS
    // on-screen ONLY: a run already extending past the left edge has no beginning to
    // soften, and ramping it anyway re-anchored the fade to whichever sample happened to be
    // the off-screen anchor, so the shading's left edge JUMPED each time a point aged out
    // while the stroke above it slid smoothly (Nuri, 2026-08-18). Off-screen runs fill
    // solid; the view's clip is the boundary, and a clipped edge cannot jump.
    val rampWidth = FILL_RAMP_WIDTH.toPx()
    val needsRamp = first.x > 0f
    drawIntoCanvas { canvas ->
        if (needsRamp) canvas.saveLayer(Rect(Offset.Zero, size), paths.layer)
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.02f)),
                startY = 0f,
                endY = size.height,
            ),
            alpha = runAlpha,
        )
        if (needsRamp) {
            // DstIn multiplies the fill's alpha by this gradient's, and only inside the
            // rect it covers — everything to the right of the ramp is untouched, which is
            // the "fill black to keep" half of the iOS mask.
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = first.x,
                    endX = first.x + rampWidth,
                ),
                topLeft = Offset(first.x, 0f),
                size = Size(rampWidth, size.height),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }

    drawPath(
        path = line,
        color = tint,
        alpha = runAlpha,
        style = Stroke(
            width = STROKE_WIDTH.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    drawCircle(color = tint, radius = HEAD_RADIUS.toPx(), center = head, alpha = runAlpha)
}
