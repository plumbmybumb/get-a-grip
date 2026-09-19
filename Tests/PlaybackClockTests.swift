// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

/// The trace's playback clock under BUNCHED delivery.
///
/// An iPad's Bluetooth stack hands the Progressor's stream over in clumps of a second or
/// more: every sample's device timestamp is perfect, but a whole clump lands in one
/// runloop turn. The clock used to snap forward at the head of each clump and drop the
/// buffer at its tail, so the graph never held more than one clump and drew nothing
/// (Nuri's iPad, 2026-09-19). Contiguous data is now allowed to run a few seconds either
/// side of wall time; only a real backlog — seconds of samples that did not happen now —
/// still restarts the buffer.
@MainActor
final class PlaybackClockTests: XCTestCase {
    private static let microsPerSample: UInt32 = 12_500

    private func makeStreamingStore() -> (DeviceStore, RecordingProgressorClient) {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.connect()
        device.startStreaming(cause: .manualMeasurement)
        return (device, client)
    }

    private func emitClump(_ client: RecordingProgressorClient, samples: Int, from micros: inout UInt32) {
        for _ in 0..<samples {
            client.emit(.sample(ForceSample(kg: 4, deviceMicros: micros)))
            micros &+= Self.microsPerSample
        }
    }

    private func flushes(in device: DeviceStore) -> Int {
        device.diagnosticEntries.filter { if case .traceFlush = $0.event { return true } else { return false } }.count
    }

    /// Two clumps of 1.2 s, delivered 1.2 s apart, keep one continuous, unflushed line.
    func testDeliveryClumpsKeepTheTraceContinuous() async throws {
        let (device, client) = makeStreamingStore()
        var micros: UInt32 = 0
        emitClump(client, samples: 96, from: &micros)
        XCTAssertEqual(device.trace.count, 96, "a clump's tail, ahead of wall time by ~1.2 s, is not a backlog")
        XCTAssertEqual(flushes(in: device), 0)

        try await Task.sleep(for: .seconds(1.2))
        emitClump(client, samples: 96, from: &micros)
        XCTAssertEqual(device.trace.count, 192, "the head of the next clump, ~1.2 s behind wall time, does not restart the run")
        XCTAssertEqual(flushes(in: device), 0)

        let times = device.trace.map(\.t)
        for (earlier, later) in zip(times, times.dropFirst()) {
            XCTAssertGreaterThan(later, earlier, "the playback clock is strictly monotone")
            XCTAssertLessThan(later - earlier, 0.02, "consecutive samples stay one period apart — no snap anywhere in the line")
        }
        let ahead = try XCTUnwrap(times.last) - Date().timeIntervalSinceReferenceDate
        XCTAssertLessThan(abs(ahead), DeviceStore.lateDeliveryLimitSeconds)
    }

    /// Seconds of samples in one turn is a suspended app's backlog, not delivery jitter:
    /// the buffer restarts once the clock runs past the limit, exactly as before.
    func testAGenuineBacklogStillRestartsTheTrace() {
        let (device, client) = makeStreamingStore()
        var micros: UInt32 = 0
        emitClump(client, samples: 480, from: &micros)   // 6 s of device time in one turn
        XCTAssertGreaterThanOrEqual(flushes(in: device), 1, "six seconds ahead of wall time is a backlog")
        XCTAssertLessThan(device.trace.count, 480, "the run restarted at the flush")
        XCTAssertGreaterThan(device.trace.count, 0, "and the samples after it started a fresh run")
    }

    private func packet(_ client: RecordingProgressorClient, from micros: inout UInt32) {
        for index in 0..<8 {
            client.emit(.sample(ForceSample(kg: 4, deviceMicros: micros, isBatchStart: index == 0)))
            micros &+= Self.microsPerSample
        }
    }

    /// The buffer's whole point: a packet is still in the FUTURE when it lands, so the
    /// head always has a next point to glide toward — and no deeper than a margin, so the
    /// pen runs as little behind the hand as a smooth line can. Regular 100 ms packets for
    /// a second, each one's first sample marked as the real client marks it.
    func testEveryPacketIsStillPendingWhenItLandsAndNoDeeperThanAMargin() async throws {
        let (device, client) = makeStreamingStore()
        var micros: UInt32 = 0
        for index in 0..<10 {
            let arrival = Date().timeIntervalSinceReferenceDate
            packet(client, from: &micros)
            let first = try XCTUnwrap(device.trace.dropLast(7).last)
            XCTAssertGreaterThan(first.t - arrival, 0.0, "packet \(index): its first sample is still pending when it lands")
            XCTAssertLessThan(first.t - arrival, 0.2, "packet \(index): but only by a margin")
            let newest = try XCTUnwrap(device.trace.last)
            XCTAssertLessThan(newest.t - arrival, 0.5, "packet \(index): and the whole packet is due within half a second")
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(flushes(in: device), 0)
        XCTAssertEqual(device.traceUnderruns, 0)
        XCTAssertEqual(device.playbackMargin, DeviceStore.playbackMarginFloor, accuracy: 0.02,
                       "regular packets sit on the margin's floor")
        XCTAssertEqual(device.playbackLead, device.playbackMargin + 0.1, accuracy: 0.02,
                       "the lead is the margin plus one 100 ms packet")
    }

    /// The margin follows the radio: a packet that lands 300 ms later than its own span
    /// deepens the buffer by about that much, and a stall does not — a resumed app's
    /// half-minute is not delivery jitter.
    func testTheMarginDeepensWithLatePacketsButNotWithStalls() async throws {
        let (device, client) = makeStreamingStore()
        var micros: UInt32 = 0
        packet(client, from: &micros)
        try await Task.sleep(for: .milliseconds(100))
        packet(client, from: &micros)
        try await Task.sleep(for: .milliseconds(400))
        packet(client, from: &micros)
        XCTAssertGreaterThan(device.playbackMargin, 0.25, "300 ms of lateness is remembered as the margin to keep")
        XCTAssertLessThan(device.playbackMargin, 0.45)

        device.dropStaleTrace()   // the foreground path after a suspension
        try await Task.sleep(for: .milliseconds(200))
        packet(client, from: &micros)
        XCTAssertLessThan(device.playbackMargin, 0.45, "the gap to the first packet after a resume is not learned")
    }

    /// When the radio is later than the margin the pen HOLDS and resumes: the late packet is
    /// stamped just ahead of now, not due at once as a chunk, and the hold it caused is a
    /// gap short enough for the trace to draw across as a plateau.
    func testALatePacketHoldsInsteadOfChunking() async throws {
        let (device, client) = makeStreamingStore()
        var micros: UInt32 = 0
        for _ in 0..<3 {
            packet(client, from: &micros)
            try await Task.sleep(for: .milliseconds(100))
        }
        let before = try XCTUnwrap(device.trace.last)
        try await Task.sleep(for: .milliseconds(400))   // the buffer has been dry for ~300 ms
        let arrival = Date().timeIntervalSinceReferenceDate
        packet(client, from: &micros)
        let first = try XCTUnwrap(device.trace.dropLast(7).last)
        XCTAssertEqual(device.traceUnderruns, 1)
        XCTAssertGreaterThanOrEqual(first.t, arrival, "the late packet is pending, not a chunk")
        XCTAssertLessThan(first.t - arrival, 0.1, "and only by the margin that was in force")
        XCTAssertGreaterThan(first.t - before.t, 0.3, "the hold is in the timeline")
        XCTAssertLessThan(first.t - before.t, 0.75, "and short enough to draw across")
    }

    /// A counter reset a second or more back is still a stall: the clock snaps forward
    /// and the line breaks, because the timeline itself broke.
    func testAnUntrustedGapStillSnapsForward() async throws {
        let (device, client) = makeStreamingStore()
        var micros: UInt32 = 0
        emitClump(client, samples: 8, from: &micros)
        try await Task.sleep(for: .seconds(1.3))
        // The device counter restarted: the delta goes backwards, which is untrusted.
        var reset: UInt32 = 0
        emitClump(client, samples: 8, from: &reset)
        let times = device.trace.map(\.t)
        let resumed = try XCTUnwrap(times.dropFirst(8).first)
        let before = try XCTUnwrap(times.prefix(8).last)
        let gap = resumed - before
        XCTAssertGreaterThan(gap, 1.0, "an untrusted delta more than a second behind snaps to wall time")
    }
}
