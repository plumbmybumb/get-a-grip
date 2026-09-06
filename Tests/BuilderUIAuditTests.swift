// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

@MainActor
final class BuilderUIAuditTests: XCTestCase {
    func testLegacyTargetsBecomeEditableWithoutChangingExecutionOrOtherDraftValues() {
        let inherited = SetPlan(grip: GripSpec(), repsPerSide: 0)
        let kilograms = SetPlan(grip: GripSpec(), targetLoKg: 6, targetHiKg: 9)
        let percentage = SetPlan(grip: GripSpec(), targetLoPercent: 0.4, targetHiPercent: 0.5)
        var original = RoutineDraft()
        original.plan.name = "  Unfinished  "
        original.plan.sets = [inherited, kilograms, percentage]
        original.plan.targetLoPercent = 0.2
        original.plan.targetHiPercent = 0.3
        let edited = BuilderDraftPreparation.editable(original)
        XCTAssertEqual(edited.plan.name, original.plan.name)
        XCTAssertEqual(edited.plan.sets[0].repsPerSide, 0)
        XCTAssertEqual(edited.plan.sets[1], kilograms)
        XCTAssertEqual(edited.plan.sets[2], percentage)
        XCTAssertNil(edited.plan.targetPercentBand)
        XCTAssertEqual(original.plan.targetPercentBand, 0.2...0.3)
        XCTAssertEqual(BuilderDraftPreparation.editable(edited), edited)
        for maxKg in [nil, 0.5, 20.0, 60.0] as [Double?] {
            for index in original.plan.sets.indices {
                XCTAssertEqual(PlanMath.targetBand(edited.plan.sets[index], in: edited.plan, maxKg: maxKg),
                    PlanMath.targetBand(original.plan.sets[index], in: original.plan, maxKg: maxKg))
            }
        }
    }

    func testClearingMaterializedTargetDoesNotRevealAnInvisibleRoutineFallback() {
        var original = RoutineDraft()
        original.plan.sets = [SetPlan(grip: GripSpec())]
        original.plan.targetLoPercent = 0.2
        original.plan.targetHiPercent = 0.3
        var edited = BuilderDraftPreparation.editable(original)
        edited.plan.sets[0].targetLoPercent = nil
        edited.plan.sets[0].targetHiPercent = nil
        XCTAssertNil(PlanMath.targetBand(edited.plan.sets[0], in: edited.plan, maxKg: 30))
        XCTAssertEqual(BuilderDraftPreparation.editable(.blank(named: "")), .blank(named: ""))
    }

    func testBandDraggingPreservesWidthAtBothWallsAndFitsOversizedIncomingValues() {
        XCTAssertEqual(BandTrimmerMath.translated(lower: 5, upper: 10, delta: 100, scale: 0...20, step: 0.5), 15...20)
        XCTAssertEqual(BandTrimmerMath.translated(lower: 5, upper: 10, delta: -100, scale: 0...20, step: 0.5), 0...5)
        XCTAssertEqual(BandTrimmerMath.translated(lower: -5, upper: 30, delta: 1, scale: 0...20, step: 0.5), 0...20)
        XCTAssertEqual(BandTrimmerMath.translated(lower: 0, upper: 0, delta: 1, scale: 0...0, step: 0.5), 0...0)
        XCTAssertEqual(BandTrimmerMath.translated(lower: 1, upper: 2, delta: 1, scale: 1...100, step: 5), 1...2)
    }

    func testConsistencyHintDoesNotPretendALapsedUserIsNew() {
        func record(_ tracked: Bool, completed: Int = 0) -> DayRecord {
            DayRecord(day: DayStamp(raw: 20_000), completed: completed, target: 2, tracked: tracked, climb: nil)
        }
        XCTAssertTrue(ConsistencyEmptyState.showsFirstUseHint([record(false), record(true)]))
        XCTAssertFalse(ConsistencyEmptyState.showsFirstUseHint([record(true), record(true)]))
        XCTAssertFalse(ConsistencyEmptyState.showsFirstUseHint([record(false), record(true, completed: 1)]))
        XCTAssertFalse(ConsistencyEmptyState.showsFirstUseHint([]))
    }

    func testBatteryPercentageUsesOneBoundedRoundingRule() {
        XCTAssertEqual(BatteryDisplay.percentage(0.556), 56)
        XCTAssertEqual(BatteryDisplay.percentage(0.554), 55)
        XCTAssertEqual(BatteryDisplay.percentage(-0.1), 0)
        XCTAssertEqual(BatteryDisplay.percentage(1.2), 100)
        XCTAssertEqual(BatteryDisplay.percentage(.nan), 0)
    }
}
