// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import XCTest
@testable import Doigt

/// History's session detail is a frozen read-back: every number from the log, and the one
/// recovered link — which maxes a session saved — claimed only on facts the save froze.
final class SessionDetailContentTests: XCTestCase {

    private let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
    private let finished = Date(timeIntervalSince1970: 1_800_000_000)

    private func rep(_ side: Side, peak: Double, outcome: RepOutcome = .completed) -> RepSummary {
        RepSummary(setIndex: 0, repIndex: 0, side: side, grip: grip, targetSeconds: 10,
                   heldSeconds: 10, peakKg: peak, avgKg: peak - 1, outcome: outcome)
    }

    private func record(_ side: Side, kg: Double, after seconds: TimeInterval = 30,
                        measured: Bool = true, window: Int = 0,
                        grip: GripSpec? = nil) -> SessionDetailContent.RecordFact {
        .init(grip: grip ?? self.grip, side: side, kg: kg, seconds: window, measured: measured,
              recordedAt: finished.addingTimeInterval(seconds))
    }

    private func saved(_ reps: [RepSummary], _ records: [SessionDetailContent.RecordFact],
                       kind: SessionKind = .hang) -> [SessionDetailContent.SavedMax] {
        SessionDetailContent.savedMaxes(kind: kind, day: DayStamp(trainingDayOf: finished),
                                        finishedAt: finished, reps: reps, records: records)
    }

    func testAMaxCopiedFromACompletedPullOfThatHandIsThisSessions() {
        let found = saved([rep(.left, peak: 31.25), rep(.right, peak: 28.5)],
                          [record(.left, kg: 31.25)])
        XCTAssertEqual(found.map(\.kg), [31.25])
        XCTAssertEqual(found.first?.side, .left)
    }

    func testANearMissIsNotClaimed() {
        let reps = [rep(.left, peak: 31.25)]
        // Another hand, another grip, another number, a skipped pull, a typed max, a timed
        // max, before the session ended, or the next day: none of them came from this save.
        XCTAssertTrue(saved(reps, [record(.right, kg: 31.25)]).isEmpty)
        XCTAssertTrue(saved(reps, [record(.left, kg: 31.25,
                                          grip: GripSpec(edgeMM: 15, fingers: .four, position: .halfCrimp))]).isEmpty)
        XCTAssertTrue(saved(reps, [record(.left, kg: 31.2)]).isEmpty)
        XCTAssertTrue(saved([rep(.left, peak: 31.25, outcome: .skipped)], [record(.left, kg: 31.25)]).isEmpty)
        XCTAssertTrue(saved(reps, [record(.left, kg: 31.25, measured: false)]).isEmpty)
        XCTAssertTrue(saved(reps, [record(.left, kg: 31.25, window: 10)]).isEmpty)
        XCTAssertTrue(saved(reps, [record(.left, kg: 31.25, after: -3600)]).isEmpty)
        XCTAssertTrue(saved(reps, [record(.left, kg: 31.25, after: 2 * 86_400)]).isEmpty)
    }

    func testHandLoggedSessionsClaimNoMaxes() {
        for kind in [SessionKind.hangManual, .climbVolume, .climbLimit] {
            XCTAssertTrue(saved([], [record(.left, kg: 31.25)], kind: kind).isEmpty, "\(kind)")
        }
    }

    func testABenchmarkDayListsThatDaysMeasuredMaxesHeaviestFirst() {
        let found = saved([], [record(.left, kg: 30), record(.right, kg: 32, after: 600),
                               record(.both, kg: 40, measured: false),
                               record(.both, kg: 50, after: 3 * 86_400)],
                          kind: .benchmark)
        XCTAssertEqual(found.map(\.kg), [32, 30])
    }

    func testTheSnapshotCopiesTheLogAndNeverInventsALoad() throws {
        var plan = SessionPlan(name: "R", sets: [SetPlan(grip: grip, repsPerSide: 2)])
        plan.handMode = .bothHands
        let reps = [rep(.both, peak: 0), rep(.both, peak: 0, outcome: .skipped)]
        let log = WorkoutLog(plan: plan, templateID: nil, templateName: "R", sessionsPerDayTarget: 1,
                             reps: reps, startedAt: finished.addingTimeInterval(-120),
                             finishedAt: finished, day: DayStamp(trainingDayOf: finished))
        log.rpe = RPE.solid.rawValue
        let content = SessionDetailContent(log: log, name: "Renamed", reps: reps, records: [])
        XCTAssertEqual(content.name, "Renamed")
        XCTAssertEqual(content.completedReps, 1)
        XCTAssertEqual(content.plannedReps, PlanMath.totalReps(plan.executable))
        XCTAssertEqual(content.grade, .solid)
        XCTAssertEqual(content.minutes, 2)
        // A timer-only run: nothing read a load, so nothing is shown as one.
        XCTAssertFalse(content.measured)
        // Pills from the PLAN, finished from the reps, and no "current" one in a record.
        let progress = try XCTUnwrap(content.progress)
        XCTAssertEqual(progress.setSizes.reduce(0, +), PlanMath.totalReps(plan.executable))
        XCTAssertEqual(progress.finished, [true, false])
        XCTAssertNil(progress.current)
    }

    func testIdentityIsTheLogs() {
        let log = WorkoutLog(logged: .climbLimit, day: DayStamp(trainingDayOf: finished), at: finished,
                             sessionsPerDayTarget: 1, minutes: 90)
        let a = SessionDetailContent(log: log, name: "A", reps: [], records: [])
        let b = SessionDetailContent(log: log, name: "B", reps: [], records: [])
        XCTAssertEqual(a, b)
        XCTAssertNil(a.progress)
        XCTAssertEqual(a.minutes, 90)
    }
}
