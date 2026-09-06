// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import run.nuri.getagrip.store.DeviceStore
import kotlin.math.max
import kotlin.math.min

/// Every number and every decision `ForceTraceView` draws with, as pure arithmetic.
///
/// Split out from the Canvas on purpose: the trace's rules — where a run starts, which
/// point anchors it off the left edge, how the axis eases, what the head reads — are the
/// part that has been wrong on real hardware more than once, and none of them need a
/// screen to be checked. `TraceGeometryTests` is the gate.
///
/// TRANSLATION NOTE: on iOS this all lives inside `ForceTraceView.draw`, where a SwiftUI
/// `Canvas` closure can hold it; the split is the Kotlin shape of the same code, not a
/// change of behaviour.
object TraceGeometry {

    /// How much history is on screen. Six seconds shows a whole 10 s hold's shape without
    /// squeezing the detail out of it.
    const val WINDOW_SECONDS: Double = 6.0

    /// The head's running average — roughly one BLE batch. Long enough to damp batch
    /// noise, short enough that a fast pull's onset doesn't drag a laggy hook at the tip
    /// (at 250 ms the averaged head visibly trailed the line during hard pulls).
    const val HEAD_AVERAGE_SECONDS: Double = 0.12

    /// Past this much drift there is no fresh data, so no synthetic head: the trace slides
    /// away instead of pinning a stale flat line to the right edge forever.
    const val HEAD_FRESHNESS_SECONDS: Double = 0.5

    /// A run reveals itself over its first half second. Derived from the DATA, with no
    /// state at all — how old the oldest drawn sample is IS how long this run has been
    /// going — so it covers resume, tare, reconnect and the first pull of a session
    /// without any of them being special.
    const val FADE_IN_SECONDS: Double = 0.5

    /// The Progressor's own gap floor: 3.5 batches at ten notifications a second.
    const val PROGRESSOR_GAP_SECONDS: Double = 0.35

    /// Longer than this between two samples and the stream stopped.
    ///
    /// **Three of this gauge's own sample periods, floored at the Progressor's 0.35 s.**
    /// The flat 0.35 was a Tindeq number and only ~2.8 periods of an 8 Hz crane scale,
    /// whose duplicate advertisements are best-effort even in the foreground: two missed
    /// frames there is 375 ms, which declared a gap and threw away the entire drawn
    /// history for a graph showing one point.
    ///
    /// And for a BROADCAST gauge there is no gap threshold at all: delivery is bursty by
    /// nature — clumps of advertisements with multi-second holes between them — so every
    /// hole "started a new run" and the whole drawn history vanished at each one, which is
    /// exactly the disappearing graph Nuri's first WH-C06 session showed (2026-08-17, his
    /// ask verbatim: "string together all of those random data points into a graph instead
    /// of dropping it"). Points older than the window still fall off the left edge on
    /// their own; a scale that genuinely left is the silence watchdog's job, not the
    /// renderer's.
    fun streamGapSeconds(nominalSampleRate: Double, bridgesSparseDelivery: Boolean): Double =
        if (bridgesSparseDelivery) {
            Double.MAX_VALUE
        } else {
            max(PROGRESSOR_GAP_SECONDS, 3.0 / max(1.0, nominalSampleRate))
        }

    /// Three-point moving average of the DRAWING only — never of the values the engine
    /// times reps with. The ends are left alone: there is no neighbour to average with,
    /// and inventing one would move the newest point, which is the one being read.
    fun smoothed(samples: List<DeviceStore.TracePoint>, index: Int, runStart: Int = 0): Double {
        if (index <= runStart || index >= samples.size - 1) return samples[index].kg
        return (samples[index - 1].kg + samples[index].kg + samples[index + 1].kg) / 3.0
    }

    /// **THE NEWEST UNBROKEN RUN.** A gap in the buffer means the stream stopped —
    /// backgrounded, disconnected, tared — and the points either side of it are minutes
    /// apart in the hand even though they are adjacent in the array. Drawing across it put
    /// a horizontal bridge through the middle of the graph and an impossible cliff at each
    /// end (Nuri, 2026-08-09: "you go to your home screen and come back, the graph is all
    /// messed up"). A gap is a boundary, not data.
    fun runStart(samples: List<DeviceStore.TracePoint>, gapSeconds: Double): Int {
        var scan = samples.size - 1
        while (scan > 0) {
            if (samples[scan].t - samples[scan - 1].t > gapSeconds) return scan
            scan -= 1
        }
        return 0
    }

    /// Where a sample sits horizontally, in pixels, given how far the window's right edge
    /// has already slid past the newest point. Wall-clock drift, so it grows every frame;
    /// `t` is the store's slewed playback time, so radio jitter doesn't move the trace.
    fun x(
        sampleT: Double,
        newestT: Double,
        drift: Double,
        width: Float,
    ): Float {
        val age = (newestT - sampleT) + drift
        return width - (age / WINDOW_SECONDS).toFloat() * width
    }

    /// **THE ANCHOR: the newest point still older than the window.**
    ///
    /// Drawing only points already on screen made the line's visible start SNAP forward
    /// the instant the oldest on-screen point aged past the edge — at 80 Hz the next point
    /// is a fraction of a pixel further right and the snap is invisible, but a broadcast
    /// gauge's readings land seconds apart, so the same snap is a visible chunk of trace
    /// vanishing at once (Nuri, 2026-08-17: "it should just continue off screen
    /// uninterrupted"). Walking forward from the run's own start to the last point whose x
    /// is still left of 0 gives ONE off-screen anchor per run; the segment that crosses
    /// x = 0 is drawn from it every frame, so the trace slides continuously off the left
    /// edge instead of jumping. The anchor can land well off screen — the view's clip is
    /// what makes that harmless.
    fun anchorIndex(
        samples: List<DeviceStore.TracePoint>,
        runStart: Int,
        newestT: Double,
        drift: Double,
        width: Float,
    ): Int {
        var anchor = runStart
        while (anchor < samples.size - 1 && x(samples[anchor + 1].t, newestT, drift, width) < 0f) {
            anchor += 1
        }
        return anchor
    }

    /// WHILE DATA IS FLOWING, the head rides the right edge. The newest sample is always
    /// one BLE batch (~100 ms) old, so placing the head at its own timestamp left a sliver
    /// of card between fill and edge that breathed at 10 Hz — the firmware's delivery
    /// cadence made visible, which is exactly what this view exists to hide. The
    /// historical points stay placed by their own timestamps, so the anti-stutter geometry
    /// is untouched.
    fun headAverageKg(samples: List<DeviceStore.TracePoint>): Double {
        if (samples.isEmpty()) return 0.0
        val newestT = samples.last().t
        var sum = 0.0
        var count = 0.0
        var i = samples.size - 1
        while (i >= 0 && newestT - samples[i].t <= HEAD_AVERAGE_SECONDS) {
            sum += samples[i].kg
            count += 1.0
            i -= 1
        }
        return if (count > 0) sum / count else smoothed(samples, samples.size - 1)
    }

    /// The ceiling the axis is aiming at. The band's ceiling has to be ON the axis with
    /// headroom above it, or the lane you are aiming for sits jammed against the top edge
    /// of the card.
    fun axisTarget(maxSeen: Double, thresholdKg: Double?, bandHiKg: Double?): Double =
        max(
            max(10.0, maxSeen * 1.25),
            max((thresholdKg ?: 0.0) * 1.6, (bandHiKg ?: 0.0) * 1.25),
        )

    /// Cross-frame axis state: LATCHED and EASED, never a per-frame function of the
    /// rolling buffer.
    ///
    /// Deriving the ceiling from `samples.max()` was the rubber-banding: the buffer only
    /// remembers six seconds, so every pull grew the axis on the way up, and six seconds
    /// later — when that pull's peak slid out of the buffer — the whole graph stretched
    /// back, dragging the threshold line with it. An axis keyed to what happened six
    /// seconds ago reads as random motion. So the max is latched for the life of the view
    /// (a training screen wants one stable scale per session, exactly like `peakKg`) and
    /// the displayed ceiling eases toward its target, so the one legitimate rescale — a
    /// new personal peak mid-session — is a glide, not a snap.
    class AxisMemory {
        var maxSeen: Double = 0.0
            private set
        var displayed: Double = 0.0
            private set
        private var lastFrame: Double = 0.0

        fun ceiling(
            samples: List<DeviceStore.TracePoint>,
            thresholdKg: Double?,
            bandHiKg: Double?,
            now: Double,
            reduceMotion: Boolean,
        ): Double {
            // Walked without allocating a mapped array — this runs every frame.
            for (sample in samples) if (sample.kg > maxSeen) maxSeen = sample.kg
            val target = axisTarget(maxSeen, thresholdKg, bandHiKg)
            val dt = min(max(now - lastFrame, 0.0), 0.1)
            lastFrame = now
            displayed = if (displayed == 0.0 || reduceMotion) {
                target
            } else {
                displayed + (target - displayed) * min(1.0, dt * 10.0)
            }
            return displayed
        }
    }
}
