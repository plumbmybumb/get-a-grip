// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// "Create with AI": the reader is pinned case by case in `Fixtures/agent/` (`oracle agent
/// verify`, and the Kotlin twin); these are the rules worth stating in words.
final class AgentRoutineTests: XCTestCase {

    /// Throws the reader's own failure, which fails the test.
    private func read(_ text: String) throws -> AgentRoutine.Reading {
        try AgentRoutine.read(text).get()
    }

    /// The example the instructions hand every assistant must itself read cleanly: an
    /// example the app adjusts teaches every model to write something the app adjusts.
    func testTheInstructionsOwnExampleReadsWithNoChanges() throws {
        let instructions = AgentRoutine.instructions
        let reading = try read(instructions.components(separatedBy: "EXAMPLE").last ?? "")
        XCTAssertEqual(reading.notes, [])
        let plan = reading.draft.plan
        XCTAssertEqual(plan.name, "Recovery 7:3")
        XCTAssertEqual(plan.holdSeconds, 7)
        XCTAssertEqual(plan.restSeconds, 3)
        XCTAssertEqual(plan.targetBandGate, .below)
        XCTAssertFalse(plan.waitForReleaseBeforeRest)
        XCTAssertEqual(plan.sets.count, 3)
        XCTAssertEqual(plan.sets[1].grip, GripSpec(edgeMM: 20, fingers: .frontThree, position: .drag))
        XCTAssertEqual(plan.sets[2].holdSeconds, 10)
        XCTAssertEqual(plan.sets[2].targetMaxSeconds, 10)
        XCTAssertEqual(plan.sets[2].targetLoPercent ?? 0, 0.6, accuracy: 1e-9)
        XCTAssertEqual(plan.targetLoPercent ?? 0, 0.2, accuracy: 1e-9)
    }

    /// Imported like a stranger's code: no identity, no reminders switched on.
    func testAReadRoutineIsANewRoutineWithRemindersOff() throws {
        let reading = try read(#"{"sets": [{"edgeMm": 20, "pulls": 6}]}"#)
        XCTAssertNil(reading.draft.templateID)
        XCTAssertFalse(reading.draft.remindersEnabled)
    }

    /// A routine percentage keeps its basis through `normalized`, which moves it onto the
    /// sets — what the preview shows and the store saves.
    func testARoutineTimedMaxTargetSurvivesNormalizing() throws {
        let reading = try read(#"{"target": {"percentOfMax": [80, 90], "max": 7}, "sets": [{"edgeMm": 20, "pulls": 3}]}"#)
        let saved = reading.draft.normalized.plan
        XCTAssertEqual(saved.sets[0].targetMaxSeconds, 7)
        XCTAssertEqual(saved.sets[0].targetHiPercent ?? 0, 0.9, accuracy: 1e-9)
    }

    /// Out of range is changed AND reported — never refused, never silent.
    func testAValueOutOfRangeIsClampedAndReported() throws {
        let reading = try read(#"{"holdSeconds": 200, "sets": [{"edgeMm": 140, "pulls": 6}]}"#)
        XCTAssertEqual(reading.draft.plan.holdSeconds, SetPlan.holdRange.upperBound)
        XCTAssertEqual(reading.draft.plan.sets[0].grip.edgeMM, GripSpec.edgeRange.upperBound)
        XCTAssertEqual(reading.notes.map(\.code), ["hold:200->120", "set1.edge:140->100"])
        XCTAssertEqual(reading.notes[1].message, "Set 1 · Edge: 140 → 100")
    }

    func testThePasteThatIsNotARoutineSaysWhy() {
        XCTAssertEqual(failure("Sounds good! Want me to write it?"), .noRoutine)
        XCTAssertEqual(failure(#"{"name": "x", "sets": [{"edgeMm": 20"#), .unreadable)
        XCTAssertEqual(failure(#"{"sets": []}"#), .noSets)
        XCTAssertEqual(failure(#"{"format": "get-a-grip-routine/2", "sets": [{}]}"#), .newerFormat)
    }

    private func failure(_ text: String) -> AgentRoutine.Failure? {
        if case .failure(let f) = AgentRoutine.read(text) { return f }
        return nil
    }
}
