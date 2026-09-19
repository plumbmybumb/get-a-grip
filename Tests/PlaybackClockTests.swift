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
