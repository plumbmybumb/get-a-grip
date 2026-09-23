// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// History's trend deck, now built off the main actor from copied rows. These pin the
/// rules it carried over from the view: which routines get a card and under what name,
/// which grips a card offers and in what order, and what one point on a line means.
final class TrendModelTests: XCTestCase {
    private let crimp = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
    private let front3 = GripSpec(edgeMM: 20, fingers: FingerSet(token: "IMR"), position: .halfCrimp)
    private let base = Date(timeIntervalSinceReferenceDate: 800_000_000)

    private func rep(_ grip: GripSpec, kg: Double, held: Double = 10,
                     outcome: RepOutcome = .completed) -> RepSummary {
        var rep = RepSummary()
        rep.grip = grip
        rep.avgKg = kg
        rep.peakKg = kg + 2
        rep.heldSeconds = held
        rep.outcome = outcome
        return rep
    }

    private func row(_ reps: [RepSummary], routine: UUID?, name: String = "Frozen",
                     daysAgo: Double) -> TrendModel.Row {
        TrendModel.Row(id: UUID(), templateID: routine, templateName: name,
                       startedAt: base.addingTimeInterval(-daysAgo * 86_400),
                       resultsData: BlobCodec.encode(reps) ?? Data())
    }

    func testCardsFollowTheMostRecentlyTrainedRoutineAndItsLiveName() {
        let daily = UUID(), maxDay = UUID()
        let rows = [   // newest first, as History's query sorts them
            row([rep(crimp, kg: 30)], routine: maxDay, name: "Old max name", daysAgo: 1),
            row([rep(crimp, kg: 12)], routine: daily, daysAgo: 2),
            row([rep(crimp, kg: 11)], routine: daily, daysAgo: 3),
        ]
        let model = TrendModel.build(rows: rows, routineNames: [daily: "Daily", maxDay: "Max day"])
        XCTAssertEqual(model.routines.map(\.name), ["Max day", "Daily"])
        XCTAssertEqual(model.routines.map(\.key), [maxDay.uuidString, daily.uuidString])
    }

    func testADeletedRoutineGetsNoCardAndANamelessOneKeepsItsFrozenName() {
        let gone = UUID()
        let rows = [
            row([rep(crimp, kg: 12)], routine: gone, daysAgo: 1),
            row([rep(crimp, kg: 12)], routine: nil, name: "Before IDs", daysAgo: 2),
        ]
        let model = TrendModel.build(rows: rows, routineNames: [:])
        XCTAssertEqual(model.routines.map(\.name), ["Before IDs"])
    }

    /// Gauge-free sessions log 0 kg and skipped reps never finished; neither is a load.
    func testOnlyMeasuredCompletedRepsChart() {
        let daily = UUID()
        let rows = [
            row([rep(crimp, kg: 0)], routine: daily, daysAgo: 1),
            row([rep(crimp, kg: 14, outcome: .skipped)], routine: daily, daysAgo: 2),
        ]
        XCTAssertTrue(TrendModel.build(rows: rows, routineNames: [daily: "Daily"]).routines.isEmpty,
                      "a routine with nothing measured gets no card")
    }

    func testGripsAreMostTrainedFirstAndSeriesAreTimeWeightedOldestFirst() throws {
        let daily = UUID()
        let rows = [
            row([rep(front3, kg: 9), rep(crimp, kg: 20, held: 10), rep(crimp, kg: 10, held: 30)],
                routine: daily, daysAgo: 1),
            row([rep(crimp, kg: 12)], routine: daily, daysAgo: 5),
        ]
        let routine = try XCTUnwrap(TrendModel.build(rows: rows, routineNames: [daily: "Daily"])
            .routines.first)
        XCTAssertEqual(routine.grips.map(\.key), [crimp.key, front3.key])
        XCTAssertEqual(routine.grips.map(\.count), [3, 1])
        let series = try XCTUnwrap(routine.series[crimp.key])
        XCTAssertEqual(series.map(\.avgKg), [12, 12.5],
                       "oldest first; (20·10 + 10·30) / 40 for the newer session")
        XCTAssertEqual(series.map(\.id), [rows[1].id, rows[0].id])
    }

    func testASharedSelectionHoldsOnlyWhereTheRoutineTrainedIt() throws {
        let daily = UUID()
        let routine = try XCTUnwrap(TrendModel.build(
            rows: [row([rep(crimp, kg: 12)], routine: daily, daysAgo: 1)],
            routineNames: [daily: "Daily"]).routines.first)
        XCTAssertEqual(routine.selectedGrip(nil), crimp.key)
        XCTAssertEqual(routine.selectedGrip(front3.key), crimp.key)
        XCTAssertEqual(routine.selectedGrip(crimp.key), crimp.key)
    }
}
