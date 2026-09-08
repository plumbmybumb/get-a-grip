// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Exercise the published screen snapshot, not a second prediction of which hand
/// should follow a rep. These are the same events and fields RunnerView consumes.
@MainActor
final class RunnerTrainingGuidanceTests: XCTestCase {
    func testWorkingBorderUsesBlueAndWarningsTakePriority() {
        var snapshot = RunnerSnapshot(phase: .working(slot: 0), hasSignal: true)
        XCTAssertEqual(snapshot.screenBorderCue(timerOnly: false, measuredSignalIsLive: true), .pulling)
        XCTAssertEqual(RunnerScreenCue.pulling.lineWidth, 4)
        for warning in [\RunnerSnapshot.isDropped, \RunnerSnapshot.isOverTarget,
                        \RunnerSnapshot.linkIsDown, \RunnerSnapshot.isRejectingStaleBatches] {
            snapshot[keyPath: warning] = true
            XCTAssertEqual(snapshot.screenBorderCue(timerOnly: false, measuredSignalIsLive: true), .warning)
            snapshot[keyPath: warning] = false
            XCTAssertEqual(snapshot.screenBorderCue(timerOnly: false, measuredSignalIsLive: true), .pulling)
        }
        XCTAssertEqual(RunnerScreenCue.warning.lineWidth, 6)
    }

    func testMeasuredWorkNeverStaysBlueWithMissingOrStaleSignal() {
        var snapshot = RunnerSnapshot(phase: .working(slot: 0), hasSignal: true)
        XCTAssertEqual(snapshot.screenBorderCue(timerOnly: false, measuredSignalIsLive: false), .warning)
        snapshot.hasSignal = false
        XCTAssertEqual(snapshot.screenBorderCue(timerOnly: false, measuredSignalIsLive: true), .warning)
    }

    func testTimerOnlyBlueDoesNotDependOnAnUnrelatedGauge() {
        let snapshot = RunnerSnapshot(phase: .working(slot: 0),
                                      isRejectingStaleBatches: true, linkIsDown: true, hasSignal: false)
        XCTAssertEqual(snapshot.screenBorderCue(timerOnly: true, measuredSignalIsLive: false), .pulling)
    }

    func testWaitingRestPauseAndFinishedNeverBorrowTheWorkingBorder() {
        let phases: [RunnerPhase] = [.idle, .leadIn(slot: 0), .armed(slot: 0),
            .resting(slot: 0), .paused(before: .working(slot: 0)),
            .paused(before: .releasing(slot: 0)), .finished]
        for phase in phases {
            let snapshot = RunnerSnapshot(phase: phase, isDropped: true, hasSignal: true)
            XCTAssertNil(snapshot.screenBorderCue(timerOnly: false, measuredSignalIsLive: true), "\(phase)")
            XCTAssertNil(snapshot.screenBorderCue(timerOnly: true, measuredSignalIsLive: false), "\(phase)")
        }
        let releasing = RunnerSnapshot(phase: .releasing(slot: 0), isDropped: true)
        XCTAssertEqual(releasing.screenBorderCue(timerOnly: false, measuredSignalIsLive: false), .releasing)
        XCTAssertEqual(RunnerScreenCue.releasing.lineWidth, 6)
    }

    func testMeasuredPullChangesBlueToRedBackToBlueThenOrangeAndRest() {
        let session = session()
        func cue() -> RunnerScreenCue? {
            // These synthetic samples go straight to the engine, bypassing the
            // DeviceStore callback that normally records signal presence.
            var snapshot = session.snapshot
            snapshot.hasSignal = true
            return snapshot.screenBorderCue(timerOnly: false, measuredSignalIsLive: true)
        }
        XCTAssertNil(cue())
        for index in 0...3 {
            session.send(.sample(ForceSample(kg: 10, deviceMicros: UInt32(index * 100_000))))
        }
        XCTAssertEqual(cue(), .pulling)
        session.send(.sample(ForceSample(kg: 0, deviceMicros: 400_000)))
        XCTAssertEqual(cue(), .warning)
        session.send(.sample(ForceSample(kg: 10, deviceMicros: 500_000)))
        XCTAssertEqual(cue(), .pulling)
        for index in 6...20 {
            session.send(.sample(ForceSample(kg: 10, deviceMicros: UInt32(index * 100_000))))
        }
        XCTAssertEqual(cue(), .releasing)
        session.send(.sample(ForceSample(kg: 0, deviceMicros: 2_100_000)))
        XCTAssertNil(cue())
    }

    private func session(mode: HandMode = .alternateEachRep, reps: Int = 2,
                         rest: Int = 20, waitForRelease: Bool = true) -> RunnerSession {
        var draft = RoutineDraft.blank(named: "Training guidance")
        draft.plan.handMode = mode
        draft.plan.holdSeconds = 1
        draft.plan.restSeconds = rest
        draft.plan.setBreakSeconds = 30
        draft.plan.leadInSeconds = 0
        draft.plan.waitForReleaseBeforeRest = waitForRelease
        draft.plan.sets = [SetPlan(repsPerSide: reps),
                           SetPlan(grip: GripSpec(edgeMM: 15), repsPerSide: reps)]
        let session = RunnerSession(template: SessionTemplate(draft: draft, sortIndex: 0),
                                    device: DeviceStore(client: RecordingProgressorClient()))
        session.send(.start)
        return session
    }

    private func completeFirstHold(_ session: RunnerSession) {
        for index in 0...12 {
            session.send(.sample(ForceSample(kg: 10, deviceMicros: UInt32(index * 100_000))))
        }
    }

    func testReleaseBorderPersistsUntilAMeasuredReleaseAndThenShowsNextHand() {
        let session = session()
        XCTAssertNil(session.snapshot.nextRestHand)
        completeFirstHold(session)
        XCTAssertEqual(session.snapshot.phase, .releasing(slot: 0))
        XCTAssertTrue(session.snapshot.showsReleaseBorder)
        XCTAssertEqual(session.snapshot.side, .left, "Do not switch hands while still loaded")
        XCTAssertNil(session.snapshot.nextRestHand)
        for _ in 0..<100 { session.send(.tick) }
        session.send(.sample(ForceSample(kg: 10, deviceMicros: 1_300_000)))
        XCTAssertTrue(session.snapshot.showsReleaseBorder, "Time and another loaded sample cannot dismiss it")
        session.send(.sample(ForceSample(kg: 0, deviceMicros: 1_400_000)))
        XCTAssertFalse(session.snapshot.showsReleaseBorder)
        XCTAssertEqual(session.snapshot.phase, .resting(slot: 0))
        XCTAssertEqual(session.snapshot.nextRestHand, .right)
        XCTAssertEqual(session.snapshot.nextRestHandPrompt, String(localized: "RIGHT HAND NEXT"))
        XCTAssertEqual(session.snapshot.secondsShown, 20)
    }

    func testPausedReleaseDoesNotLeaveAnInstructionThatPausedSamplesCannotClear() {
        let session = session()
        completeFirstHold(session)
        session.send(.pause)
        XCTAssertFalse(session.snapshot.showsReleaseBorder)
        XCTAssertNil(session.snapshot.nextRestHand)
        session.send(.resume)
        XCTAssertTrue(session.snapshot.showsReleaseBorder)
        session.send(.sample(ForceSample(kg: 0, deviceMicros: 1_400_000)))
        XCTAssertFalse(session.snapshot.showsReleaseBorder)
    }

    func testAbortingSkippingAndLosingTheLinkClearTheReleaseBorder() {
        for event in [RunnerEvent.abort, .skipSet, .connectionLost] {
            let session = session()
            completeFirstHold(session)
            XCTAssertTrue(session.snapshot.showsReleaseBorder)
            session.send(event)
            XCTAssertFalse(session.snapshot.showsReleaseBorder, "\(event)")
        }
    }

    func testGroupedHandsDoNotInventAnAlternatingHandAndPausedRestKeepsTheNextHand() {
        let session = session(mode: .alternateEachSet)
        completeFirstHold(session)
        session.send(.sample(ForceSample(kg: 0, deviceMicros: 1_400_000)))
        XCTAssertEqual(session.snapshot.nextRestHand, .left, "The next grouped pull remains on the left")
        session.send(.pause)
        XCTAssertEqual(session.snapshot.nextRestHand, .left)
        XCTAssertEqual(session.snapshot.nextRestHandPrompt, String(localized: "LEFT HAND NEXT"))
        XCTAssertFalse(session.snapshot.showsReleaseBorder)
    }

    func testSetBoundaryShowsActualNextSetHandAndKeepsSetBreakCountdown() {
        let session = session(reps: 1)
        // Skip the first two unstarted pulls: the second is the end of this set.
        session.send(.skipRep)
        XCTAssertEqual(session.snapshot.nextRestHand, .right)
        session.send(.skipRep)
        XCTAssertTrue(session.snapshot.isSetBreak)
        XCTAssertEqual(session.snapshot.setNumber, 2)
        XCTAssertEqual(session.snapshot.nextRestHand, .left)
        XCTAssertEqual(session.snapshot.secondsShown, 30)
    }

    func testBothHandsIsExplicitAndReleaseIsNotInventedWhenDisabled() {
        let session = session(mode: .bothHands, waitForRelease: false)
        completeFirstHold(session)
        XCTAssertFalse(session.snapshot.showsReleaseBorder)
        XCTAssertEqual(session.snapshot.nextRestHand, .both)
        XCTAssertEqual(session.snapshot.nextRestHandPrompt, String(localized: "BOTH HANDS NEXT"))
    }

    func testNoRestDoesNotShowANextRestInstruction() {
        let session = session(rest: 0)
        completeFirstHold(session)
        XCTAssertEqual(session.snapshot.phase, .armed(slot: 1))
        XCTAssertNil(session.snapshot.nextRestHand)
        XCTAssertFalse(session.snapshot.showsReleaseBorder)
    }

    func testSkippingAPullKeepsTheBorderIfTheEngineStillRequiresRelease() {
        let session = session()
        completeFirstHold(session)
        session.send(.skipRep)
        XCTAssertEqual(session.snapshot.phase, .releasing(slot: 1))
        XCTAssertTrue(session.snapshot.showsReleaseBorder,
                      "A skip cannot hide a live release gate just because the slot changed")
        session.send(.sample(ForceSample(kg: 0, deviceMicros: 1_400_000)))
        XCTAssertFalse(session.snapshot.showsReleaseBorder)
    }
}
