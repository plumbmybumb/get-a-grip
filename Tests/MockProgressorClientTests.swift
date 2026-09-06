// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

@MainActor
final class MockProgressorClientTests: XCTestCase {
    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(3)
        while !condition(), ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(condition(), "Mock did not deliver the expected connection or samples")
    }

    func testRepeatedTareZeroesTheCurrentLoadWithoutAccumulatingOffsets() async throws {
        let client = MockProgressorClient()
        var samples: [ForceSample] = []
        client.onEvent = { if case .sample(let sample) = $0 { samples.append(sample) } }
        client.connect()
        defer { client.disconnect() }
        try await waitUntil { client.state.isConnected }
        client.startStreaming(cause: .initial)
        try await waitUntil { samples.count >= 16 }
        XCTAssertGreaterThan(samples.last!.kg, 1)

        let next = samples.count
        client.send(.tare)
        client.send(.tare)
        client.startStreaming(cause: .tareRecovery)
        try await waitUntil { samples.count > next }

        XCTAssertEqual(samples[next].kg, 0, accuracy: 1e-9,
                       "Taring twice against one raw load must still mean zero")
        XCTAssertEqual(samples[next].deviceMicros, 0, "A real stream re-kick may reset the device epoch")
        for (index, sample) in samples.enumerated() { XCTAssertEqual(sample.isBatchStart, index % 8 == 0) }
    }

    func testSecondSessionStartsUnloadedAfterEndingOnTheLoadedProfile() async throws {
        let client = MockProgressorClient()
        var samples: [ForceSample] = []
        client.onEvent = { if case .sample(let sample) = $0 { samples.append(sample) } }
        client.connect()
        defer { client.disconnect() }
        try await waitUntil { client.state.isConnected }
        client.send(.tare)
        client.startStreaming(cause: .initial)
        try await waitUntil { samples.count >= 16 }
        XCTAssertGreaterThan(samples.last!.kg, 1)
        client.send(.stopWeightMeasurement)

        let next = samples.count
        // RunnerSession tares before starting each new session, without disconnecting.
        client.send(.tare)
        client.startStreaming(cause: .initial)
        try await waitUntil { samples.count > next }

        XCTAssertEqual(samples[next].kg, 0, accuracy: 1e-9)
        XCTAssertEqual(samples[next].deviceMicros, 0)
        XCTAssertTrue(samples[next...].allSatisfy { $0.kg >= 0 },
                      "The old synthetic load must not become a negative offset")
    }
}
