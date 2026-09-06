// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class TrainingSafeguardTests: XCTestCase {
    func testImportedMaxTableRejectsNonFiniteAndNonPositiveValues() {
        for invalid in [Double.infinity, -.infinity, .nan, 0, -5] {
            let table = MaxTable(keyed: [MaxTable.key(grip: GripSpec().key, side: .both): invalid])
            XCTAssertNil(table.max(grip: GripSpec().key, side: .left))
        }
        let table = MaxTable(keyed: [MaxTable.key(grip: GripSpec().key, side: .both): 40])
        XCTAssertEqual(table.max(grip: GripSpec().key, side: .left), 40)
    }

    func testLaterPercentageSetWithSameGripStillReportsMissingBenchmark() {
        var plan = SessionPlan()
        plan.sets = [SetPlan(targetLoKg: 5), SetPlan(targetLoPercent: 0.25), SetPlan(targetLoPercent: 0.3)]
        XCTAssertEqual(PlanMath.untargetedGripCount(plan, maxes: MaxTable()), 1)
        XCTAssertEqual(PlanMath.missingBenchmarkGripCount(plan, maxes: MaxTable()), 1)
    }

    func testFixedTargetsUnplannedTargetsAndSkippedSetsDoNotRequireBenchmark() {
        var plan = SessionPlan()
        plan.sets = [SetPlan(targetLoKg: 5), SetPlan(), SetPlan(repsPerSide: 0, targetLoPercent: 0.25)]
        XCTAssertEqual(PlanMath.missingBenchmarkGripCount(plan, maxes: MaxTable()), 0)
    }

    func testPercentageTargetsNeedBothHandsBeforeWarningDisappears() {
        var plan = RoutineDraft.starter.plan
        plan.sets = [SetPlan(targetLoPercent: 0.25)]
        var maxes = MaxTable()
        maxes.record(40, grip: GripSpec().key, side: .left)
        XCTAssertEqual(PlanMath.missingBenchmarkGripCount(plan, maxes: maxes), 1)
        maxes.record(50, grip: GripSpec().key, side: .right)
        XCTAssertEqual(PlanMath.missingBenchmarkGripCount(plan, maxes: maxes), 0)
    }
}
