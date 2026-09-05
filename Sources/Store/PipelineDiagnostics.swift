// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Bounded, non-observable measurements. Never an input to the session clock.
/// Callback arrival excludes radio/OS queueing; drawing excludes display scanout.
@MainActor
final class PipelineDiagnostics {
    struct Packet {
        let received: Double
        let hopMS: Double
        let processingMS: Double
        let gapMS: Double?
        let samples: Int
        var pendingDraw: Bool
        var drawMS: Double?
    }
    private var packets: [Packet?] = Array(repeating: nil, count: 128)
    private var cursor = 0
    private var visibleGraphs = 0
    private var hasPendingDraw = false
    func graphOpened() { visibleGraphs += 1 }
    func graphClosed() {
        visibleGraphs = max(0, visibleGraphs - 1)
        if visibleGraphs == 0 {
            for index in packets.indices { packets[index]?.pendingDraw = false }
            hasPendingDraw = false
        }
    }
    private var received: Double?
    private var started = 0.0
    private var count = 0
    private var previousArrival: Double?

    func begin(arrival: Double, now: Double) { received = arrival; started = now; count = 0 }
    func sample() { if received != nil { count += 1 } }
    func end(now: Double) {
        guard let arrival = received else { return }
        received = nil
        guard count > 0 else { return }
        packets[cursor] = Packet(received: arrival, hopMS: max(0, (started - arrival) * 1000),
            processingMS: max(0, (now - started) * 1000),
            gapMS: previousArrival.map { max(0, (arrival - $0) * 1000) }, samples: count, pendingDraw: visibleGraphs > 0)
        cursor = (cursor + 1) % packets.count
        previousArrival = arrival
        if visibleGraphs > 0 { hasPendingDraw = true }
    }
    func drawing(now: Double) {
        guard hasPendingDraw else { return }
        hasPendingDraw = false
        for index in packets.indices {
            if let packet = packets[index], packet.pendingDraw {
                packets[index]?.pendingDraw = false
                packets[index]?.drawMS = max(0, (now - packet.received) * 1000)
            }
        }
    }
    func reset() {
        packets = Array(repeating: nil, count: 128)
        cursor = 0; received = nil; previousArrival = nil; hasPendingDraw = false
    }
    var snapshot: [Packet] { packets.compactMap { $0 } }
    var report: String {
        let records = snapshot
        func stats(_ values: [Double]) -> String {
            guard !values.isEmpty else { return "not observed" }
            let sorted = values.sorted()
            let p95 = sorted[Int(ceil(Double(sorted.count) * 0.95)) - 1]
            return String(format: "avg %.2f / p95 %.2f / max %.2f ms", locale: Locale(identifier: "en_US_POSIX"),
                          values.reduce(0, +) / Double(values.count), p95, sorted.last!)
        }
        return "Bluetooth pipeline (last \(records.count) sample packets; \(records.reduce(0) { $0 + $1.samples }) samples)\n" +
            "Callback delivery: \(stats(records.map(\.hopMS)))\n" +
            "Packet processing: \(stats(records.map(\.processingMS)))\n" +
            "Packet arrival gaps: \(stats(records.compactMap(\.gapMS)))\n" +
            "Callback to graph render: \(stats(records.compactMap(\.drawMS)))\n" +
            "Includes app scheduling only; excludes pre-callback radio/OS latency and display scanout."
    }
}
