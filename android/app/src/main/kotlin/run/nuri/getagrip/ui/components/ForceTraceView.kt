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
import androidx.compose.ui.graphics.drawscope.translate
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

/// Keeps the curve off the card's edges: at rest the zero line would sit on the boundary,
/// clipped by the corners, reading as a glitch rather than "no load".
private val INSET_TOP = 12.dp
private val INSET_BOTTOM = 10.dp
private val STROKE_WIDTH = 2.5.dp
private val RULE_WIDTH = 1.dp
private val RULE_DASH = 4.dp
private val HEAD_RADIUS = 4.dp
/// The lit head's glow: a radial fade around the live point, readable from across a room.
private val GLOW_RADIUS = 16.dp

/// The fill's left edge ramps up over this much, so history BEGINS rather than starts.
private val FILL_RAMP_WIDTH = 40.dp

/// How long the frame loop sleeps while nothing moves. A fresh sample does not wait: reading
/// the trace in the draw lambda invalidates the DRAW immediately; this only governs when the
/// wall-clock slide resumes.
private const val IDLE_POLL_MILLIS = 250L

/// The live force trace: a rolling window of the gauge's readings, scrolling smoothly.
///
/// Time-based, not index-based: ~80 Hz samples arrive in batches of ~8, so index spacing
/// stutters ten times a second. Points carry a PLAYBACK time (`DeviceStore.TracePoint.t`,
/// monotone, immune to the device counter restarting) and the right edge follows the wall clock.
///
/// **STATELESS about time.** The iOS ancestor kept device-µs ↔ wall-clock anchors; a tare
/// cleared the buffer, the anchors survived, and the graph stayed blank beside a live kg
/// readout until re-entry. State modelling another clock can be poisoned; (now − t) cannot.
/// **Never reintroduce view-held clock state.**
///
/// **It reads the trace ITSELF, inside the draw lambda**: a snapshot read in a draw scope
/// invalidates DRAW only, so ~80 appends a second recompose nothing around this canvas.
///
/// TRANSLATION NOTE: SwiftUI's `TimelineView(.animation(paused:))` becomes a `withFrameNanos`
/// loop writing a tick. Same pause conditions (reduce motion; newest sample older than the
/// window), and the loop's per-frame check replaces iOS's expiry watcher.
@Composable
fun ForceTraceView(
    modifier: Modifier = Modifier,
    /// Drawn as a dashed rule: the load a rep has to beat for its clock to run.
    thresholdKg: Double? = null,
    /// **The range this rep asks for, drawn as a lane.** It REPLACES the threshold rule: the
    /// band's floor is what the clock runs off, so a third line would answer the same question twice.
    targetBand: ClosedFloatingPointRange<Double>? = null,
    /// The phase tint, so the graph and the rest of the screen escalate together.
    tint: Color = LocalGripPalette.current.bleu,
    clock: HostClock = SystemHostClock,
    frozenSamples: List<DeviceStore.TracePoint>? = null,
    /// **Drawn as a LIT object** (iOS `lit`) — the full-screen traces: the runner, the live
    /// gauge, the critical force and max tests. The stroke brightens toward now, the live point
    /// glows, and the lane's edges are solid hairlines. Plain fills and one radial gradient at
    /// one point: no blur, no layer over the whole trace.
    lit: Boolean = false,
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

    LaunchedEffect(reduceMotion, store, clock, frozenSamples) {
        if (frozenSamples != null) return@LaunchedEffect
        while (isActive) {
            // Read from a coroutine: snapshot reads here register no observer.
            val newest = store.trace.lastOrNull()?.t
            val expired = newest == null ||
                clock.wallSeconds() - newest >= TraceGeometry.WINDOW_SECONDS
            // Paused under Reduce Motion: redraw only on new data, not a continuously sliding graph.
            if (reduceMotion || expired) {
                delay(IDLE_POLL_MILLIS)
                continue
            }
            withFrameNanos { tick += 1 }
        }
    }

    // The clip: on a sparse-delivery gauge the off-screen anchor lands well left of x = 0 and
    // would draw outside the card.
    // Decoration, and the app's one 80 Hz drawing: without cleared semantics it could become a
    // TalkBack focus target. The runner's counters row is the accessible channel.
    Canvas(modifier.clipToBounds().clearAndSetSemantics {}) {
        // READING the tick is the whole subscription: a draw-scope read invalidates only the draw.
        @Suppress("UNUSED_VARIABLE")
        val frame = tick
        store.pipelineDiagnostics.drawing(clock.uptimeSeconds())
        val samples = frozenSamples ?: store.trace
        val caps = store.gaugeCapabilities
        val now = frozenSamples?.lastOrNull()?.t ?: clock.wallSeconds()
        val ceiling = axis.ceiling(
            samples = samples,
            thresholdKg = thresholdKg,
            bandHiKg = targetBand?.endInclusive,
            now = now,
            reduceMotion = reduceMotion || frozenSamples != null,
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
            lit = lit,
        )
    }
}

private class TracePaths {
    val line = Path()
    val fill = Path()
    val layer = Paint()

    // The gradients, rebuilt only when what they depend on changes (the tint moves with the
    // phase, the extent with the canvas), never per frame.
    private val fillCache = CachedBrush()
    private val litStrokeCache = CachedBrush()
    private val glowCache = CachedBrush()

    fun fillGradient(tint: Color, height: Float): Brush = fillCache.get(tint, height) {
        Brush.verticalGradient(
            colors = listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0.02f)),
            startY = 0f,
            endY = height,
        )
    }

    /// History dims toward the left and NOW is full strength, so the eye lands on the end
    /// that matters.
    fun litStrokeGradient(tint: Color, width: Float): Brush = litStrokeCache.get(tint, width) {
        Brush.horizontalGradient(colors = listOf(tint.copy(alpha = 0.45f), tint), startX = 0f, endX = width)
    }

    /// Centred on (radius, radius): the caller translates it onto the head, so a moving
    /// point needs no new shader.
    fun glowGradient(tint: Color, radius: Float): Brush = glowCache.get(tint, radius) {
        Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0f)),
            center = Offset(radius, radius),
            radius = radius,
        )
    }
}

/// One brush, remembered against the tint and the one extent it was built for.
private class CachedBrush {
    private var tint = Color.Unspecified
    private var extent = Float.NaN
    private var brush: Brush? = null

    inline fun get(tint: Color, extent: Float, build: () -> Brush): Brush {
        val cached = brush
        if (cached != null && tint == this.tint && extent == this.extent) return cached
        return build().also { this.tint = tint; this.extent = extent; brush = it }
    }
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
    lit: Boolean,
) {
    val insetTop = INSET_TOP.toPx()
    val plotHeight = maxOf(1f, size.height - insetTop - INSET_BOTTOM.toPx())

    fun y(kg: Double): Float {
        val fraction = kg.coerceIn(0.0, ceiling) / ceiling
        return insetTop + plotHeight - fraction.toFloat() * plotHeight
    }

    val dash = PathEffect.dashPathEffect(floatArrayOf(RULE_DASH.toPx(), RULE_DASH.toPx()))

    if (targetBand != null) {
        // A LANE, not two lines: a filled band is a place to be. Neutral ink, because the TRACE
        // carries the phase colour and a tinted lane would compete with it.
        val top = y(targetBand.endInclusive)
        val bottom = y(targetBand.start)
        drawRect(
            color = neutral.copy(alpha = if (lit) 0.11f else 0.13f),
            topLeft = Offset(0f, top),
            size = Size(size.width, maxOf(1f, bottom - top)),
        )
        // Lit: solid hairlines. On an open screen dashed rules read as chart furniture.
        for (edge in listOf(top, bottom)) {
            drawLine(
                color = neutral.copy(alpha = if (lit) 0.38f else 0.5f),
                start = Offset(0f, edge),
                end = Offset(size.width, edge),
                strokeWidth = RULE_WIDTH.toPx(),
                pathEffect = if (lit) null else dash,
            )
        }
    } else if (thresholdKg != null && thresholdKg > 0 && thresholdKg < ceiling) {
        val line = y(thresholdKg)
        drawLine(
            color = neutral.copy(alpha = if (lit) 0.35f else 0.55f),
            start = Offset(0f, line),
            end = Offset(size.width, line),
            strokeWidth = RULE_WIDTH.toPx(),
            pathEffect = dash,
        )
    }

    if (samples.size < 2) return
    val newest = samples.last()

    // How far the right edge has slid past the newest point: wall-clock, while `t` is slewed
    // playback time, so radio jitter doesn't move the trace.
    val drift = maxOf(0.0, now - newest.t)

    val runStart = TraceGeometry.runStart(samples, gapSeconds)
    val anchor = TraceGeometry.anchorIndex(samples, runStart, newest.t, drift, size.width)

    // **THE TRACE FADES BACK IN.** After the home screen the buffer restarts, and a graph that
    // just appears mid-card reads as a glitch. Derived from the DATA, with no state to poison.
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
            y(TraceGeometry.smoothed(samples, index, runStart)),
        )
        if (firstDrawn == null) {
            line.moveTo(point.x, point.y)
            firstDrawn = point
        } else {
            line.lineTo(point.x, point.y)
        }
    }
    val first = firstDrawn ?: return

    // While data flows the head rides the right edge (`TraceGeometry.headAverageKg`); the 0.5 s
    // staleness cap means no synthetic head after stop/disconnect.
    val head: Offset
    if (drift < TraceGeometry.HEAD_FRESHNESS_SECONDS) {
        head = Offset(size.width, y(TraceGeometry.headAverageKg(samples)))
        line.lineTo(head.x, head.y)
    } else {
        val last = samples.size - 1
        head = Offset(
            TraceGeometry.x(samples[last].t, newest.t, drift, size.width),
            y(TraceGeometry.smoothed(samples, last, runStart)),
        )
    }

    // Soft fill under the curve reads as "load", the stroke as "now".
    //
    // **Closed at the line's OWN first x, never at 0.** A full buffer hid the bug; any short
    // buffer (fresh session, tare, return from home) drew a diagonal up from the bottom-left
    // corner — the "weird shadow under the graph" (Nuri).
    val fill = paths.fill.apply {
        reset()
        addPath(line)
        lineTo(head.x, size.height)
        lineTo(first.x, size.height)
        close()
    }

    // FADED IN FROM THE LEFT, or a short buffer drops a full-strength cliff mid-card that reads
    // as load never pulled. Only for a run that BEGINS on-screen: ramping a run already past the
    // left edge re-anchored to whichever sample was the off-screen anchor, so the shading's edge
    // JUMPED as points aged out (Nuri, 2026-08-18). Off-screen runs fill solid to the clip.
    val rampWidth = FILL_RAMP_WIDTH.toPx()
    val needsRamp = first.x > 0f
    drawIntoCanvas { canvas ->
        if (needsRamp) canvas.saveLayer(Rect(Offset.Zero, size), paths.layer)
        drawPath(path = fill, brush = paths.fillGradient(tint, size.height), alpha = runAlpha)
        if (needsRamp) {
            // DstIn multiplies the fill's alpha by this gradient only inside its rect — the "fill black
            // to keep" half of the iOS mask.
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

    val stroke = Stroke(width = STROKE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (lit) {
        drawPath(path = line, brush = paths.litStrokeGradient(tint, size.width), alpha = runAlpha, style = stroke)
        // The glow marks the live point from across a room.
        val glow = GLOW_RADIUS.toPx()
        translate(head.x - glow, head.y - glow) {
            drawCircle(brush = paths.glowGradient(tint, glow), radius = glow, center = Offset(glow, glow), alpha = runAlpha)
        }
    } else {
        drawPath(path = line, color = tint, alpha = runAlpha, style = stroke)
    }

    drawCircle(color = tint, radius = HEAD_RADIUS.toPx(), center = head, alpha = runAlpha)
}
