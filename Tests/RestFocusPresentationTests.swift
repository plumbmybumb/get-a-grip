// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

@MainActor
final class RestFocusPresentationTests: XCTestCase {
    private func session(rest: Int = 10, setBreak: Int = 30,
                         firstSetRest: Int? = nil, nextSetRest: Int? = nil,
                         reps: Int = 2, mode: HandMode = .bothHands,
                         timerOnly: Bool = true) -> RunnerSession {
        var draft = RoutineDraft.blank(named: "Rest preview")
        draft.plan.handMode = mode
        draft.plan.holdSeconds = 1
        draft.plan.restSeconds = rest
        draft.plan.setBreakSeconds = setBreak
        draft.plan.leadInSeconds = 0
        draft.plan.waitForReleaseBeforeRest = true
        draft.plan.sets = [
            SetPlan(grip: GripSpec(edgeMM: 20), repsPerSide: reps, restSeconds: firstSetRest),
            SetPlan(grip: GripSpec(edgeMM: 15), repsPerSide: reps, restSeconds: nextSetRest)
        ]
        let session = RunnerSession(template: SessionTemplate(draft: draft, sortIndex: 0),
                                    device: DeviceStore(client: RecordingProgressorClient()),
                                    timerOnly: timerOnly)
        // Exercise the production publication funnel without starting a wall-clock
        // task, a gauge subscription or a Live Activity.
        session.send(.start)
        return session
    }

    func testPerSetRestOverridesTheRoutineDefaultInThePublishedSnapshot() {
        for (routineRest, override, expected) in [(30, 3, false), (3, 10, true)] {
            let session = session(rest: routineRest, firstSetRest: override)
            session.send(.skipRep)
            XCTAssertEqual(session.snapshot.phase, .resting(slot: 0))
            XCTAssertEqual(session.snapshot.scheduledRestSeconds, override)
            XCTAssertEqual(session.snapshot.showsRestFocus, expected)
        }
    }

    func testNineSecondsStaysCompactAndTenSecondsUsesRestFocus() {
        for (seconds, focused) in [(9, false), (10, true)] {
            let session = session(rest: seconds)
            session.send(.skipRep)
            XCTAssertEqual(session.snapshot.scheduledRestSeconds, seconds)
            XCTAssertEqual(session.snapshot.showsRestFocus, focused)
        }
    }

    func testTareAndStreamRestartPreserveRestPresentationAndTheOriginalDeadline() {
        let plan = SessionPlan(sets: [SetPlan(repsPerSide: 2)], handMode: .alternateEachRep,
                               holdSeconds: 1, restSeconds: 10, leadInSeconds: 0)
        var runner = SessionRunner(plan: plan)
        _ = runner.handle(.start, at: 0)
        _ = runner.handle(.skipRep, at: 1)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        let nextGrip = runner.displaySlot?.grip

        for (event, time) in [(RunnerEvent.tareCommitted, 4.0), (.streamRestarted, 8.0)] {
            _ = runner.handle(event, at: time)
            let snapshot = RunnerSnapshot(
                phase: runner.phase, grip: runner.displaySlot?.grip, side: runner.displaySlot?.side,
                scheduledRestSeconds: RestFocusPresentation.scheduledRestSeconds(
                    phase: runner.phase, slots: runner.slots),
                secondsShown: runner.secondsRemaining(at: time) ?? 0)
            XCTAssertEqual(snapshot.phase, .resting(slot: 0))
            XCTAssertEqual(snapshot.grip, nextGrip)
            XCTAssertEqual(snapshot.side, .right)
            XCTAssertEqual(snapshot.scheduledRestSeconds, 10)
            XCTAssertTrue(snapshot.showsRestFocus)
            XCTAssertEqual(snapshot.secondsShown, Int(11 - time),
                           "Breaking the sample epoch cannot restart the independent rest clock")
        }

        _ = runner.handle(.tick, at: 11)
        XCTAssertEqual(runner.phase, .armed(slot: 1))
        XCTAssertNil(RestFocusPresentation.scheduledRestSeconds(phase: runner.phase, slots: runner.slots))
    }

    func testDisconnectDuringPausedReleasePublishesTheUpcomingRestAndReconnectKeepsIt() {
        let session = session(mode: .alternateEachRep, timerOnly: false)
        for index in 0...12 {
            session.send(.sample(ForceSample(kg: 10, deviceMicros: UInt32(index * 100_000))))
        }
        XCTAssertEqual(session.snapshot.phase, .releasing(slot: 0))
        session.send(.pause)
        XCTAssertFalse(session.snapshot.showsRestFocus)

        // A lost gauge cannot report release. The existing engine starts the rest,
        // while preserving pause; the presentation must follow that transition.
        session.send(.connectionLost)
        XCTAssertEqual(session.snapshot.phase, .paused(before: .resting(slot: 0)))
        XCTAssertTrue(session.snapshot.linkIsDown)
        XCTAssertEqual(session.snapshot.side, .right)
        XCTAssertEqual(session.snapshot.scheduledRestSeconds, 10)
        XCTAssertTrue(session.snapshot.showsRestFocus)
        let frozenSeconds = session.snapshot.secondsShown

        session.send(.connectionRestored)
        session.send(.tareCommitted)
        session.send(.streamRestarted)
        session.send(.tick)
        XCTAssertFalse(session.snapshot.linkIsDown)
        XCTAssertEqual(session.snapshot.phase, .paused(before: .resting(slot: 0)))
        XCTAssertEqual(session.snapshot.secondsShown, frozenSeconds)
        XCTAssertEqual(session.snapshot.side, .right)
        XCTAssertEqual(session.snapshot.scheduledRestSeconds, 10)
        XCTAssertTrue(session.snapshot.showsRestFocus)

        session.send(.abort)
        XCTAssertTrue(session.snapshot.isFinished)
        XCTAssertNil(session.snapshot.scheduledRestSeconds)
        XCTAssertFalse(session.snapshot.showsRestFocus)
    }

    func testSetBreakUsesTheCompletedSlotWhileTheGripLooksAhead() {
        let session = session(rest: 3, setBreak: 12, firstSetRest: 3, nextSetRest: 3, reps: 1)
        session.send(.skipRep)
        XCTAssertTrue(session.snapshot.isSetBreak)
        XCTAssertEqual(session.snapshot.grip?.edgeMM, 15)
        XCTAssertEqual(session.snapshot.setNumber, 2)
        XCTAssertEqual(session.snapshot.scheduledRestSeconds, 12,
                       "The next grip's own rest does not determine this set break")
        XCTAssertTrue(session.snapshot.showsRestFocus)
    }

    func testShortSetBreakDoesNotBorrowTheNextSetsLongRest() {
        let session = session(rest: 30, setBreak: 3, nextSetRest: 45)
        session.send(.skipRep)
        XCTAssertTrue(session.snapshot.showsRestFocus)
        session.send(.skipRep)
        XCTAssertTrue(session.snapshot.isSetBreak)
        XCTAssertEqual(session.snapshot.grip?.edgeMM, 15)
        XCTAssertEqual(session.snapshot.scheduledRestSeconds, 3)
        XCTAssertFalse(session.snapshot.showsRestFocus)
    }

    func testReleaseGateKeepsTheCurrentHandUntilReleaseThenPauseRetainsRestFocus() {
        let session = session(mode: .alternateEachRep, timerOnly: false)
        for index in 0...12 {
            session.send(.sample(ForceSample(kg: 10, deviceMicros: UInt32(index * 100_000))))
        }
        XCTAssertEqual(session.snapshot.phase, .releasing(slot: 0))
        XCTAssertEqual(session.snapshot.side, .left)
        XCTAssertNil(session.snapshot.scheduledRestSeconds)
        XCTAssertFalse(session.snapshot.showsRestFocus)

        session.send(.sample(ForceSample(kg: 0, deviceMicros: 1_400_000)))
        XCTAssertEqual(session.snapshot.phase, .resting(slot: 0))
        XCTAssertEqual(session.snapshot.side, .right)
        XCTAssertEqual(session.snapshot.scheduledRestSeconds, 10)
        XCTAssertTrue(session.snapshot.showsRestFocus)

        session.send(.pause)
        let frozenSeconds = session.snapshot.secondsShown
        session.send(.tick)
        XCTAssertEqual(session.snapshot.phase, .paused(before: .resting(slot: 0)))
        XCTAssertEqual(session.snapshot.secondsShown, frozenSeconds)
        XCTAssertTrue(session.snapshot.showsRestFocus)
        session.send(.resume)
        XCTAssertTrue(session.snapshot.showsRestFocus)

        session.send(.skipSet)
        XCTAssertNil(session.snapshot.scheduledRestSeconds)
        XCTAssertFalse(session.snapshot.showsRestFocus,
                       "Skipping to the next set restores the active-pull layout immediately")
    }

    func testLongRestKeepsItsLayoutThroughTheFinalSecondsAndEndsAtTheNextPull() {
        var plan = SessionPlan(sets: [SetPlan(repsPerSide: 2)], handMode: .bothHands,
                               holdSeconds: 1, restSeconds: 10, leadInSeconds: 0)
        plan.waitForReleaseBeforeRest = false
        var runner = SessionRunner(plan: plan, timerOnly: true)
        _ = runner.handle(.start, at: 0)
        _ = runner.handle(.tick, at: 1)
        XCTAssertEqual(runner.phase, .resting(slot: 0))
        for time in [1.0, 8.0, 9.0, 10.0, 10.999] {
            _ = runner.handle(.tick, at: time)
            let snapshot = RunnerSnapshot(
                phase: runner.phase,
                scheduledRestSeconds: RestFocusPresentation.scheduledRestSeconds(
                    phase: runner.phase, slots: runner.slots),
                secondsShown: runner.secondsRemaining(at: time) ?? 0)
            XCTAssertTrue(snapshot.showsRestFocus, "Layout must remain stable at time \(time)")
        }
        let awaitingFinalTick = RunnerSnapshot(
            phase: runner.phase,
            scheduledRestSeconds: RestFocusPresentation.scheduledRestSeconds(
                phase: runner.phase, slots: runner.slots),
            secondsShown: runner.secondsRemaining(at: 11) ?? 0)
        XCTAssertEqual(awaitingFinalTick.secondsShown, 0)
        XCTAssertTrue(awaitingFinalTick.showsRestFocus,
                      "A zero readout alone cannot dismiss rest before the engine advances")
        _ = runner.handle(.tick, at: 11)
        XCTAssertEqual(runner.phase, .working(slot: 1))
        XCTAssertNil(RestFocusPresentation.scheduledRestSeconds(phase: runner.phase, slots: runner.slots))
    }

    func testOnlyRestCanUseTheExpandedLayoutEvenWithALingeringDuration() {
        for phase in [RunnerPhase.idle, .leadIn(slot: 0), .armed(slot: 0), .working(slot: 0),
                      .releasing(slot: 0), .paused(before: .releasing(slot: 0)),
                      .paused(before: .working(slot: 0)), .finished] {
            let snapshot = RunnerSnapshot(phase: phase, scheduledRestSeconds: 30)
            XCTAssertFalse(snapshot.showsRestFocus, "\(phase)")
        }
        let unknownDuration = RunnerSnapshot(phase: .resting(slot: 0))
        XCTAssertFalse(unknownDuration.showsRestFocus)
        XCTAssertNil(RestFocusPresentation.scheduledRestSeconds(phase: .resting(slot: 0), slots: []))
    }
}
