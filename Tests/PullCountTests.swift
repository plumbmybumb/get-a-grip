// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class PullCountTests: XCTestCase {
    func testLargerCountsSurviveBlobAndRoutineShareRoundTrips() throws {
        for count in [21, 36, 99, 100] {
            var draft = RoutineDraft.blank(named: "Long set")
            draft.plan.sets = [SetPlan(repsPerSide: count)]
            let saved = try JSONDecoder().decode(RoutineDraft.self, from: JSONEncoder().encode(draft))
            let imported = try RoutineShare.draft(from: XCTUnwrap(RoutineShare.url(for: saved)))
            XCTAssertEqual(saved.plan.sets[0].repsPerSide, count)
            XCTAssertEqual(imported.plan.sets[0].repsPerSide, count)
        }
    }

    func testThirtySixPullsPerSideExecuteWithTheCorrectHandsAndCounts() {
        for mode in HandMode.allCases {
            let plan = SessionPlan(sets: [SetPlan(repsPerSide: 36)], handMode: mode,
                                   holdSeconds: 1, restSeconds: 0, leadInSeconds: 0)
            var runner = SessionRunner(plan: plan, timerOnly: true)
            let expected = mode == .bothHands ? 36 : 72
            XCTAssertEqual(runner.plannedRepCount, expected)
            _ = runner.handle(.start, at: 0)
            XCTAssertEqual(runner.repsInCurrentSet, expected)
            for second in 1...expected { _ = runner.handle(.tick, at: Double(second)) }
            XCTAssertTrue(runner.isFinished, "\(mode)")
            XCTAssertEqual(runner.results.count, expected)
            XCTAssertTrue(runner.results.allSatisfy { $0.outcome == .completed && $0.heldSeconds == 1 })
            if mode == .bothHands {
                XCTAssertTrue(runner.results.allSatisfy { $0.side == .both })
            } else {
                XCTAssertEqual(runner.results.filter { $0.side == .left }.count, 36)
                XCTAssertEqual(runner.results.filter { $0.side == .right }.count, 36)
            }
        }
    }

    func testSkippingALargerSetPreservesSummaryAndNextSetPosition() {
        let plan = SessionPlan(sets: [SetPlan(repsPerSide: 36), SetPlan(repsPerSide: 3)],
                               handMode: .alternateEachSet, holdSeconds: 1,
                               restSeconds: 0, leadInSeconds: 0)
        var runner = SessionRunner(plan: plan, timerOnly: true)
        _ = runner.handle(.start, at: 0)
        _ = runner.handle(.skipSet, at: 0)
        XCTAssertEqual(runner.completedRepCount, 72)
        XCTAssertTrue(runner.results.allSatisfy { $0.outcome == .skipped })
        XCTAssertEqual(runner.setNumber, 2)
        XCTAssertEqual(runner.repsInCurrentSet, 6)
        XCTAssertEqual(runner.repNumberInSet, 1)
        XCTAssertEqual(runner.currentSlot?.side, .left)
    }

    func testAggregateTotalsAgreeWithExecutionAcrossEmptySetsAndOverrides() {
        for mode in HandMode.allCases {
            for count in [0, 1, 36, 100] {
                let plan = SessionPlan(sets: [SetPlan(repsPerSide: 0),
                    SetPlan(repsPerSide: count, holdSeconds: 2, restSeconds: 7),
                    SetPlan(repsPerSide: 0), SetPlan(repsPerSide: 3), SetPlan(repsPerSide: 0)],
                    handMode: mode, holdSeconds: 11, restSeconds: 13, setBreakSeconds: 17, leadInSeconds: 5)
                let slots = PlanMath.sequence(for: plan)
                XCTAssertEqual(PlanMath.totalReps(plan), slots.count)
                XCTAssertEqual(PlanMath.totalSeconds(plan), slots.reduce(0) { $0 + $1.totalSeconds })
                XCTAssertEqual(PlanMath.tensionSeconds(plan), slots.reduce(0) { $0 + $1.holdSeconds })
            }
        }
        XCTAssertEqual(PlanMath.totalSeconds(SessionPlan(sets: [SetPlan(repsPerSide: 0)])), 0)
    }

    func testMaximumShareShapeHasRepresentableTotalsWithoutExpandingItsPulls() {
        let plan = SessionPlan(sets: (0..<50).map { _ in SetPlan(repsPerSide: 100) },
                               handMode: .alternateEachRep, holdSeconds: 120,
                               restSeconds: 600, setBreakSeconds: 900, leadInSeconds: 60)
        XCTAssertEqual(PlanMath.totalReps(plan), 10_000)
        XCTAssertEqual(PlanMath.tensionSeconds(plan), 1_200_000)
        XCTAssertEqual(PlanMath.totalSeconds(plan), 7_217_100)
    }

    func testLargestSharedPlanStartsAndSkipsOneSetWithBoundedWork() {
        let plan = SessionPlan(sets: (0..<50).map { _ in SetPlan(repsPerSide: 100) },
                               handMode: .alternateEachRep, leadInSeconds: 0)
        var initTimes: [Double] = []
        var skipTimes: [Double] = []
        for _ in 0..<20 {
            let beforeInit = ProcessInfo.processInfo.systemUptime
            var runner = SessionRunner(plan: plan, timerOnly: true)
            initTimes.append(ProcessInfo.processInfo.systemUptime - beforeInit)
            XCTAssertEqual(runner.plannedRepCount, 10_000)
            _ = runner.handle(.start, at: 0)
            let beforeSkip = ProcessInfo.processInfo.systemUptime
            _ = runner.handle(.skipSet, at: 0)
            skipTimes.append(ProcessInfo.processInfo.systemUptime - beforeSkip)
            XCTAssertEqual(runner.results.count, 200)
            XCTAssertEqual(runner.setNumber, 2)
            XCTAssertEqual(runner.repsInCurrentSet, 200)
        }
        // Diagnostic, not a flaky wall-clock gate on shared CI hardware.
        print("Pull-count 50x100/side: init avg \(initTimes.reduce(0, +) / 20 * 1_000) ms, max \(initTimes.max()! * 1_000) ms; skip avg \(skipTimes.reduce(0, +) / 20 * 1_000) ms, max \(skipTimes.max()! * 1_000) ms")
    }
}
