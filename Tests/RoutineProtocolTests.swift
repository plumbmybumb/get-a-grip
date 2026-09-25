// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The known protocols, pinned to the numbers Nuri captured from their sources. Each is
/// checked AFTER `normalized`, because that is what `importRoutine` writes — a band the
/// normalizer demoted or a set it dropped would be a protocol that lands different from
/// its preview.
final class RoutineProtocolTests: XCTestCase {

    private func landed(_ item: RoutineProtocol) -> RoutineDraft { item.draft.normalized }

    private func bands(_ plan: SessionPlan) -> [ClosedRange<Double>?] {
        plan.sets.map(\.targetPercentBand)
    }

    func testEveryProtocolIsSaveableAndStableUnderNormalizing() {
        for item in RoutineProtocol.allCases {
            let draft = landed(item)
            XCTAssertNil(draft.validationIssue, "\(item)")
            XCTAssertEqual(draft.normalized, draft, "\(item) must be normalized already")
            XCTAssertEqual(draft.plan.name, item.title, "\(item)")
            XCTAssertEqual(draft.plan.sets.count, item.draft.plan.sets.count,
                           "\(item): normalizing dropped a set")
        }
    }

    func testEachCallMintsFreshRowIdentity() {
        for item in RoutineProtocol.allCases {
            let a = Set(item.draft.plan.sets.map(\.id))
            let b = Set(item.draft.plan.sets.map(\.id))
            XCTAssertTrue(a.isDisjoint(with: b), "\(item)")
        }
    }

    func testDailyNoHangs() {
        let plan = landed(.dailyNoHangs).plan
        XCTAssertEqual(plan.handMode, .alternateEachRep)
        XCTAssertEqual([plan.holdSeconds, plan.restSeconds, plan.setBreakSeconds], [10, 10, 10],
                       "alternating: a 10 s gap + the other hand's 10 s pull = 20 s a hand")
        XCTAssertEqual(plan.sets.map(\.grip), [
            GripSpec(edgeMM: 20, fingers: .four,       position: .halfCrimp),
            GripSpec(edgeMM: 20, fingers: .frontThree, position: .drag),
            GripSpec(edgeMM: 20, fingers: .frontTwo,   position: .drag),
            GripSpec(edgeMM: 20, fingers: .middleTwo,  position: .drag),
            GripSpec(edgeMM: 20, fingers: .frontTwo,   position: .halfCrimp),
            GripSpec(edgeMM: 20, fingers: .middleTwo,  position: .halfCrimp),
        ])
        XCTAssertEqual(plan.sets.map(\.repsPerSide), [6, 6, 2, 2, 2, 2])
        XCTAssertEqual(bands(plan), Array(repeating: 0.35...0.45, count: 6))
        XCTAssertEqual(PlanMath.totalReps(plan), 40)
        XCTAssertEqual(landed(.dailyNoHangs).sessionsPerDay, 2)
    }

    func testC4WarmUp() {
        let draft = landed(.c4WarmUp)
        let plan = draft.plan
        XCTAssertEqual(plan.handMode, .alternateEachSet)
        XCTAssertEqual([plan.holdSeconds, plan.restSeconds, plan.setBreakSeconds], [5, 10, 30])
        XCTAssertEqual(plan.sets.map(\.repsPerSide), Array(repeating: 2, count: 6))
        XCTAssertEqual(bands(plan), [0.45...0.55, 0.65...0.75, 0.75...0.85,
                                     0.85...0.95, 0.55...0.65, 0.75...0.85])
        XCTAssertTrue(draft.isOnDemand)
    }

    /// A band that gated would pause the clock on a pull above the max on file.
    func testC4MaxDrawsItsBandButNeverGatesOnIt() {
        let draft = landed(.c4Max)
        let plan = draft.plan
        XCTAssertEqual([plan.holdSeconds, plan.restSeconds, plan.setBreakSeconds], [4, 10, 60])
        XCTAssertEqual(plan.sets.map(\.repsPerSide), [3, 3, 3])
        XCTAssertEqual(bands(plan), Array(repeating: 0.80...1.0, count: 3))
        XCTAssertFalse(plan.pausesOutsideTargetBand)
        XCTAssertTrue(draft.isOnDemand)
    }

    /// The ceiling is the prescription, so it gates; hands alternate every pull.
    func testFingerRehabGatesAtItsCeilingAndAlternates() {
        let plan = landed(.fingerRehab).plan
        XCTAssertEqual(plan.handMode, .alternateEachRep)
        XCTAssertEqual([plan.holdSeconds, plan.restSeconds, plan.setBreakSeconds], [10, 50, 50],
                       "alternating: a 50 s gap + the other hand's 10 s pull = a minute a hand")
        XCTAssertEqual(plan.sets.map(\.repsPerSide), Array(repeating: 5, count: 5))
        XCTAssertEqual(bands(plan), Array(repeating: 0.15...0.25, count: 5))
        XCTAssertTrue(plan.pausesOutsideTargetBand)
        XCTAssertNotNil(RoutineProtocol.fingerRehab.caution)
    }
}
