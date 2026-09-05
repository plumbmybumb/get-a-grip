// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import java.util.Locale

/** Bounded in-memory timings, never observable and never used to credit training time.
 * Render timing ends when the graph starts drawing, not when pixels reach the panel.
 * Arrival is the earliest application callback; OS/radio delay before it is unmeasured.
 */
class PipelineDiagnostics {
    data class Packet(val received: Double, val hopMs: Double, val processingMs: Double,
                      val gapMs: Double?, val samples: Int, var pendingDraw: Boolean, var drawMs: Double? = null)
    private val packets = arrayOfNulls<Packet>(128)
    private var cursor = 0
    private var visibleGraphs = 0
    private var hasPendingDraw = false
    fun graphOpened() { visibleGraphs++ }
    fun graphClosed() {
        visibleGraphs = (visibleGraphs - 1).coerceAtLeast(0)
        if (visibleGraphs == 0) {
            for (packet in packets) packet?.pendingDraw = false
            hasPendingDraw = false
        }
    }
    private var received: Double? = null
    private var started = 0.0
    private var count = 0
    private var previousArrival: Double? = null

    fun begin(arrival: Double, now: Double) { received = arrival; started = now; count = 0 }
    fun sample() { if (received != null) count++ }
    fun end(now: Double) {
        val arrival = received ?: return
        received = null
        if (count == 0) return
        packets[cursor] = Packet(arrival, ((started - arrival) * 1000).coerceAtLeast(0.0),
            ((now - started) * 1000).coerceAtLeast(0.0),
            previousArrival?.let { ((arrival - it) * 1000).coerceAtLeast(0.0) }, count, pendingDraw = visibleGraphs > 0)
        cursor = (cursor + 1) % packets.size
        previousArrival = arrival
        if (visibleGraphs > 0) hasPendingDraw = true
    }
    fun drawing(now: Double) {
        if (!hasPendingDraw) return
        hasPendingDraw = false
        for (packet in packets) if (packet != null && packet.pendingDraw) {
            packet.pendingDraw = false
            packet.drawMs = ((now - packet.received) * 1000).coerceAtLeast(0.0)
        }
    }
    fun reset() { packets.fill(null); cursor = 0; received = null; previousArrival = null; hasPendingDraw = false }
    fun snapshot(): List<Packet> = packets.filterNotNull().map { it.copy() }
    fun report(): String {
        val records = snapshot()
        fun stats(values: List<Double>): String {
            if (values.isEmpty()) return "not observed"
            val sorted = values.sorted()
            val p95 = sorted[kotlin.math.ceil(sorted.size * .95).toInt() - 1]
            return String.format(Locale.ROOT, "avg %.2f / p95 %.2f / max %.2f ms", values.average(), p95, sorted.last())
        }
        return "Bluetooth pipeline (last ${records.size} sample packets; ${records.sumOf { it.samples }} samples)\n" +
            "Callback delivery: ${stats(records.map { it.hopMs })}\n" +
            "Packet processing: ${stats(records.map { it.processingMs })}\n" +
            "Packet arrival gaps: ${stats(records.mapNotNull { it.gapMs })}\n" +
            "Callback to graph draw: ${stats(records.mapNotNull { it.drawMs })}\n" +
            "Includes app scheduling only; excludes pre-callback radio/OS latency and display scanout."
    }
}
