// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class FingerCurlTests: XCTestCase {
    private let curl = GripSpec(position: .fingerCurl)

    func testCurlWireIdentityAndHandShape() throws {
        let wire = Data(#"{"edgeMM":20,"fingers":"IMRL","position":"fingerCurl"}"#.utf8)
        let decoded = try XCTUnwrap(BlobCodec.decode(GripSpec.self, from: wire))
        XCTAssertEqual(decoded, curl)
        XCTAssertEqual(BlobCodec.encode(decoded), wire)
        XCTAssertEqual(curl.key, "20|IMRL|fingerCurl")
        XCTAssertEqual(GripSpec().key, "20|IMRL|halfCrimp")
        XCTAssertEqual(curl.position.closure, GripPosition.halfCrimp.closure)
        XCTAssertNotEqual(curl.position.shortName, GripPosition.fullCrimp.shortName)
    }

    func testCurlTargetsNeverBorrowHalfCrimpMaxes() {
        var plan = SessionPlan()
        plan.targetLoPercent = 0.25
        plan.targetHiPercent = 0.25
        let set = SetPlan(grip: curl)
        var maxes = MaxTable()
        maxes.record(80, grip: GripSpec().key, side: .left)
        maxes.record(100, grip: GripSpec().key, side: .both)
        XCTAssertNil(PlanMath.targetBand(set, in: plan, side: .left, maxes: maxes))
        maxes.record(40, grip: curl.key, side: .left)
        XCTAssertEqual(PlanMath.targetBand(set, in: plan, side: .left, maxes: maxes), 10...10)
        XCTAssertNil(PlanMath.targetBand(set, in: plan, side: .right, maxes: maxes))
        XCTAssertEqual(maxes.exact(grip: GripSpec().key, side: .left), 80)
    }

    func testCurlAndHalfCrimpStaySeparateInPlanTotalsAndExport() {
        var plan = SessionPlan()
        plan.sets = [SetPlan(grip: GripSpec()), SetPlan(grip: curl), SetPlan(grip: curl)]
        let totals = PlanMath.gripTotals(plan)
        XCTAssertEqual(totals.map(\.grip.key), [GripSpec().key, curl.key])
        XCTAssertEqual(totals[1].totalReps, 2 * totals[0].totalReps)
        XCTAssertEqual(AnalysisExport.positionCode(.fingerCurl), "CURL")
        XCTAssertEqual(AnalysisExport.positionEnglish(.fingerCurl),
                       "finger curl (isometric, starting in half crimp)")
    }
}
