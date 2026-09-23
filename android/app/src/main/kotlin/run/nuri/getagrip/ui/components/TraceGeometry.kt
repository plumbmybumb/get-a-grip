// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import run.nuri.getagrip.store.DeviceStore
import kotlin.math.max
import kotlin.math.min

/// Every number and every decision `ForceTraceView` draws with, as pure arithmetic.
///
/// Split from the Canvas because these rules (run start, off-edge anchor, axis easing, head)
/// have been wrong on hardware more than once and need no screen to check.
/// `TraceGeometryTests` is the gate.
///
/// TRANSLATION NOTE: iOS keeps this inside `ForceTraceView.draw`; the split is shape, not
/// behaviour.
object TraceGeometry {

    /// Six seconds shows a whole 10 s hold's shape without squeezing out its detail.
    const val WINDOW_SECONDS: Double = 6.0

    /// The head's running average — roughly one BLE batch. At 250 ms the head visibly trailed a
    /// hard pull's onset.
    const val HEAD_AVERAGE_SECONDS: Double = 0.12

    /// Past this drift there is no fresh data and no synthetic head: the trace slides away rather
    /// than pinning a stale flat line to the edge.
    const val HEAD_FRESHNESS_SECONDS: Double = 0.5

    /// A run reveals itself over its first half second. Derived from the DATA (the oldest drawn
    /// sample's age), so resume, tare, reconnect and a first pull need no special cases.
    const val FADE_IN_SECONDS: Double = 0.5

    /// The Progressor's own gap floor: 3.5 batches at ten notifications a second.
    const val PROGRESSOR_GAP_SECONDS: Double = 0.35

    /// Longer than this between two samples and the stream stopped.
    ///
    /// **Three of this gauge's sample periods, floored at the Progressor's 0.35 s.** A flat 0.35
    /// is only ~2.8 periods of an 8 Hz crane scale, so two missed frames declared a gap and threw
    /// the whole drawn history away.
    ///
    /// A BROADCAST gauge has no gap threshold at all: bursty advertisements with multi-second
    /// holes made every hole a new run, and the graph vanished (Nuri's first WH-C06 session,
    /// 2026-08-17). Old points still fall off the left edge; a scale that left is the silence
    /// watchdog's job, not the renderer's.
    fun streamGapSeconds(nominalSampleRate: Double, bridgesSparseDelivery: Boolean): Double =
        if (bridgesSparseDelivery) {
            Double.MAX_VALUE
        } else {
            max(PROGRESSOR_GAP_SECONDS, 3.0 / max(1.0, nominalSampleRate))
        }

    /// Three-point moving average of the DRAWING only — never of the values the engine times reps
    /// with. The ends are left alone: inventing a neighbour would move the newest point.
    fun smoothed(samples: List<DeviceStore.TracePoint>, index: Int, runStart: Int = 0): Double {
        if (index <= runStart || index >= samples.size - 1) return samples[index].kg
        return (samples[index - 1].kg + samples[index].kg + samples[index + 1].kg) / 3.0
    }

    /// **THE NEWEST UNBROKEN RUN.** A gap means the stream stopped (backgrounded, disconnected,
    /// tared); points either side are adjacent in the array but minutes apart. Drawing across it
    /// bridged the graph with impossible cliffs (Nuri, 2026-08-09). A gap is a boundary, not data.
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
    /// Drawing only on-screen points made the line's start SNAP forward as the oldest one aged
    /// out — invisible at 80 Hz, a visible chunk vanishing on a broadcast gauge (Nuri,
    /// 2026-08-17). ONE off-screen anchor per run, drawn every frame, slides the trace
    /// continuously off the left edge. It can land far off screen; the view's clip makes that
    /// harmless.
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

    /// WHILE DATA IS FLOWING, the head rides the right edge. The newest sample is one BLE batch
    /// (~100 ms) old, so placing it at its own timestamp left a sliver that breathed at 10 Hz —
    /// the delivery cadence this view exists to hide. History stays placed by timestamp.
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

    /// The axis target: the band's ceiling sits ON the axis with headroom, or the lane is jammed
    /// against the card's top.
    fun axisTarget(maxSeen: Double, thresholdKg: Double?, bandHiKg: Double?): Double =
        max(
            max(10.0, maxSeen * 1.25),
            max((thresholdKg ?: 0.0) * 1.6, (bandHiKg ?: 0.0) * 1.25),
        )

    /// Cross-frame axis state: LATCHED and EASED, never a per-frame function of the buffer.
    ///
    /// `samples.max()` rubber-banded: each pull grew the axis, and six seconds later, when the
    /// peak left the buffer, the graph stretched back with the threshold line. The max is
    /// latched for the view's life (one stable scale per session, like `peakKg`) and the ceiling
    /// eases, so a new peak mid-session is a glide, not a snap.
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
