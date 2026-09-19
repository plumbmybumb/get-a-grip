// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// **"Pull 35 of 36" — one definition, five readouts.**
///
/// The runner's counter row, the rest focus, the watch, the Live Activity and the
/// VoiceOver label all say this sentence, and they used to compute it four different
/// ways. Two of those ways were wrong: an unclamped `completed + 1` reported "pull 37
/// of 36" (a position cannot exceed the plan it came from), and an unfloored one would
/// say "Pull 0 of 12" for a session that is visibly running.
///
/// `RunnerSnapshot.pullPosition` is now the only arithmetic, so this file is where the
/// clamp lives. Mutation-checked: dropping either half of it fails a test below.
@MainActor
final class RunnerSnapshotPositionTests: XCTestCase {

    // MARK: - The clamp itself

    func testPositionNeverExceedsThePlan() {
        // RECORDED reps include skipped ones, so the count genuinely reaches — and can
        // sit at — the plan's own total. The position must stop there.
        XCTAssertEqual(RunnerSnapshot(completedRepCount: 35, plannedRepCount: 36).pullPosition, 36)
        XCTAssertEqual(RunnerSnapshot(completedRepCount: 36, plannedRepCount: 36).pullPosition, 36)
        XCTAssertEqual(RunnerSnapshot(completedRepCount: 99, plannedRepCount: 36).pullPosition, 36)
    }

    func testPositionIsFlooredAtOne() {
        XCTAssertEqual(RunnerSnapshot(completedRepCount: 0, plannedRepCount: 12).pullPosition, 1)
        // Before a plan is known at all — the idle snapshot — the floor is what answers.
        XCTAssertEqual(RunnerSnapshot().pullPosition, 1)
        XCTAssertEqual(RunnerSnapshot(completedRepCount: 0, plannedRepCount: 0).pullPosition, 1)
    }

    func testPositionCountsForwardThroughThePlan() {
        for completed in 0..<6 {
            XCTAssertEqual(RunnerSnapshot(completedRepCount: completed, plannedRepCount: 6).pullPosition,
                           completed + 1)
        }
    }

    // MARK: - Driven through a real session

    /// The original bug's exact shape: a routine skipped all the way through reported
    /// one more pull than it contained.
    func testSkippingEveryPullNeverReportsMoreThanThePlan() {
        var draft = RoutineDraft.blank(named: "Skipped through")
        draft.plan.leadInSeconds = 0
        draft.plan.handMode = .bothHands
        draft.plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 2),
                           SetPlan(grip: GripSpec(), repsPerSide: 2)]
        let session = RunnerSession(template: SessionTemplate(draft: draft, sortIndex: 0),
                                    device: DeviceStore(client: RecordingProgressorClient()),
                                    timerOnly: true)
        session.send(.start)
        let planned = session.snapshot.plannedRepCount
        XCTAssertGreaterThan(planned, 0)

        // Past the end on purpose: the extra skips are what the clamp is for.
        for _ in 0...(planned + 2) {
            let position = session.snapshot.pullPosition
            XCTAssertGreaterThanOrEqual(position, 1)
            XCTAssertLessThanOrEqual(position, planned,
                                     "A summary can never exceed the plan it came from")
            session.send(.skipRep)
        }
        XCTAssertLessThanOrEqual(session.snapshot.pullPosition, planned)
    }
}
