// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

@MainActor
final class BluetoothPipelineTests: XCTestCase {
    func testPacketPublishesOnceWithoutLosingIntermediateForceOrDeviceTimestamps() {
        let client = RecordingProgressorClient()
        let store = DeviceStore(client: client)
        client.connect()
        store.startStreaming(cause: .manualMeasurement)
        defer { store.stopStreaming(cause: .userStopped) }
        let revision = store.sampleRevision
        var received: [ForceSample] = []
        store.onSample = { sample in
            received.append(sample)
            XCTAssertEqual(store.currentKg, sample.kg)
            XCTAssertEqual(store.lastSample, sample)
            XCTAssertEqual(store.sampleRevision, revision)
        }
        let samples = [9.0, 2.0, 0.0].enumerated().map { index, kg in
            ForceSample(kg: kg, deviceMicros: (UInt32.max - 1000) &+ UInt32(index * 12500), isBatchStart: index == 0)
        }
        client.onPacketBoundary?(.began(receivedAt: ProcessInfo.processInfo.systemUptime))
        for sample in samples { client.onEvent?(.sample(sample)) }
        client.onPacketBoundary?(.ended)
        XCTAssertEqual(received, samples)
        XCTAssertEqual(store.sampleRevision, revision + 1)
        XCTAssertEqual(store.currentKg, 0)
        XCTAssertEqual(store.peakKg, 9)
        XCTAssertFalse(store.isLoadedForTare)
        XCTAssertEqual(store.trace.map(\.kg), [9, 2, 0])
        XCTAssertEqual(store.pipelineDiagnostics.snapshot.first?.samples, 3)
    }

    func testStandaloneSamplesAndTraceResetStillPublishImmediately() {
        let client = RecordingProgressorClient()
        let store = DeviceStore(client: client)
        client.connect()
        store.startStreaming(cause: .manualMeasurement)
        defer { store.stopStreaming(cause: .userStopped) }
        let revision = store.sampleRevision
        client.onEvent?(.sample(ForceSample(kg: 4, deviceMicros: 1)))
        XCTAssertGreaterThan(store.sampleRevision, revision)
        XCTAssertEqual(store.currentKg, 4)
        XCTAssertTrue(store.isLoadedForTare)
        let sampled = store.sampleRevision
        store.resetPeak()
        XCTAssertGreaterThan(store.sampleRevision, sampled)
        XCTAssertEqual(store.peakKg, 0)
    }

    func testTimingStagesAndHiddenGraphAreDistinguished() {
        let timing = PipelineDiagnostics()
        timing.begin(arrival: 10, now: 10.004)
        timing.sample()
        timing.end(now: 10.006)
        timing.graphOpened()
        timing.drawing(now: 20)
        XCTAssertNil(timing.snapshot.first?.drawMS, "Opening a graph must not count time it was hidden")
        timing.begin(arrival: 20, now: 20.003)
        timing.sample(); timing.sample()
        timing.end(now: 20.005)
        timing.drawing(now: 20.016)
        let last = timing.snapshot.last!
        XCTAssertEqual(last.hopMS, 3, accuracy: 0.001)
        XCTAssertEqual(last.processingMS, 2, accuracy: 0.001)
        XCTAssertEqual(last.drawMS!, 16, accuracy: 0.001)
        XCTAssertEqual(last.samples, 2)
        timing.drawing(now: 30)
        XCTAssertEqual(timing.snapshot.last!.drawMS!, 16, accuracy: 0.001)
    }

    func testDiagnosticsAreBoundedAndResetBetweenConnections() {
        let timing = PipelineDiagnostics()
        for index in 0..<300 {
            timing.begin(arrival: Double(index), now: Double(index))
            timing.sample(); timing.end(now: Double(index))
        }
        XCTAssertEqual(timing.snapshot.count, 128)
        timing.reset()
        XCTAssertTrue(timing.snapshot.isEmpty)
        timing.begin(arrival: 1000, now: 1000)
        timing.sample(); timing.end(now: 1000)
        XCTAssertNil(timing.snapshot.first?.gapMS)
    }
}
