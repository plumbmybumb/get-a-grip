// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// History's "Load per grip" line is `TrainingLoad.topLoadKg` — the hardest 5 % of held time.
/// `Fixtures/trend/top-load.json` holds the vectors both engines must reproduce (the Kotlin
/// reader is `TopLoadFixtureTests.kt`), so the two apps cannot plot two different numbers for
/// the same session.
///
/// Read straight off the repository: a simulator test process sees the host's file system,
/// so the fixture is the same bytes Kotlin reads, with no copy in the bundle to drift.
final class TopLoadFixtureTests: XCTestCase {
    private struct File: Decodable {
        struct Case: Decodable {
            struct Rep: Decodable {
                let avgKg: Double
                let heldSeconds: Double
                let outcome: String
            }
            let name: String
            let fraction: Double
            let reps: [Rep]
            let expectKg: Double?
        }
        let cases: [Case]
    }

    private static let fixture = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Fixtures/trend/top-load.json")

    func testTopLoadMatchesTheSharedFixture() throws {
        let file = try JSONDecoder().decode(File.self, from: Data(contentsOf: Self.fixture))
        XCTAssertGreaterThanOrEqual(file.cases.count, 10, "the fixture lost its cases")
        for vector in file.cases {
            let reps = try vector.reps.map { rep -> RepSummary in
                var summary = RepSummary()
                summary.avgKg = rep.avgKg
                summary.heldSeconds = rep.heldSeconds
                summary.outcome = try XCTUnwrap(RepOutcome(rawValue: rep.outcome), vector.name)
                return summary
            }
            let actual = TrainingLoad.topLoadKg(of: reps, fraction: vector.fraction)
            if let expected = vector.expectKg {
                let value = try XCTUnwrap(actual, "\(vector.name): expected \(expected)")
                XCTAssertEqual(value, expected, accuracy: 1e-9, vector.name)
            } else {
                XCTAssertNil(actual, "\(vector.name): blank, never a number")
            }
        }
    }

    func testTheDefaultFractionIsFivePercent() throws {
        // 100 s held: the top 5 s are 2 s at 40 kg and 3 s of the 30 kg rep.
        func rep(_ kg: Double, _ seconds: Double) -> RepSummary {
            var rep = RepSummary()
            rep.avgKg = kg
            rep.heldSeconds = seconds
            rep.outcome = .completed
            return rep
        }
        let value = try XCTUnwrap(TrainingLoad.topLoadKg(of: [rep(30, 10), rep(40, 2), rep(10, 88)]))
        XCTAssertEqual(value, 34, accuracy: 1e-9)
    }
}
