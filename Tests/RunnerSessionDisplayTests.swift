// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// What the RUNNER SCREEN actually shows, read through the same published snapshot the
/// view binds to.
///
/// This file exists because the engine-level paused test could not prove the numeral.
/// `SessionRunnerTests` has no access to `RunnerSession.secondsShown`, so it re-derived
/// the display arithmetic itself — and a test that recomputes what it is checking stays
/// green when the thing it is checking is deleted. The paused-display branch is exactly
/// that kind of bug: `secondsRemaining` rejects a wrapped `.paused` phase, and the
/// fallback used to reset the numeral to the rep's FULL hold length. Tolerable when the
/// number was one figure among many; not tolerable now it is the hero of the screen.
///
/// Mutation-checked 2026-08-16: restoring that fallback fails
/// `testPausingAHoldFreezesTheDisplayedNumeral` with the numeral snapping back to the
/// full ten seconds, which is the bug exactly.
@MainActor
final class RunnerSessionDisplayTests: XCTestCase {
    private func session(hold: Int, rest: Int, leadIn: Int) -> RunnerSession {
        var draft = RoutineDraft.blank(named: "Timer")
        draft.plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 2)]
        draft.plan.holdSeconds = hold
        draft.plan.restSeconds = rest
        draft.plan.leadInSeconds = leadIn
        let template = SessionTemplate(draft: draft, sortIndex: 0)
        return RunnerSession(template: template,
                             device: DeviceStore(client: RecordingProgressorClient()),
                             timerOnly: true)
    }

    func testMeasuredProgressRetainsSubPercentStepsWithoutInvalidatingTheScreen() {
        var draft = RoutineDraft.blank(named: "Measured progress")
        draft.plan.holdSeconds = 60
        draft.plan.leadInSeconds = 0
        draft.plan.handMode = .bothHands
        draft.plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 2)]
        let session = RunnerSession(template: SessionTemplate(draft: draft, sortIndex: 0),
                                    device: DeviceStore(client: RecordingProgressorClient()))
        // Drive the real measured funnel, without a wall-clock ticker or audio engine.
        session.send(.start)
        var previous = session.snapshot
        var screenChanges = 0
        for index in 1...80 {
            session.send(.sample(ForceSample(kg: 10, deviceMicros: UInt32(index * 12_500))))
            if session.snapshot != previous { screenChanges += 1; previous = session.snapshot }
        }
        guard case .working = session.snapshot.phase else {
            return XCTFail("The test must reach a measured hold")
        }
        let before = session.repProgress
        let screen = session.snapshot
        session.send(.sample(ForceSample(kg: 10, deviceMicros: 1_012_500)))
        let after = session.repProgress
        XCTAssertGreaterThan(after, before)
        XCTAssertLessThan(after - before, 0.01, "The view retains movements below one percent")
        XCTAssertEqual(session.snapshot, screen, "Moving the small bar must not change the screen snapshot")
        XCTAssertLessThanOrEqual(screenChanges, 5, "80 samples should only republish coarse screen changes")
        for _ in 0..<20 { session.send(.tick) }
        XCTAssertEqual(session.repProgress, after, "Wall-clock ticks cannot invent measured work")
        session.send(.skipRep)
        XCTAssertEqual(session.repProgress, 0, "A skipped pull clears its progress immediately")
    }

    func testTimerRingMovesWithoutChangingTheWholeScreenSnapshot() async throws {
        let session = session(hold: 10, rest: 20, leadIn: 0)
        session.begin()
        defer { session.end() }
        let screen = session.snapshot
        let initial = try XCTUnwrap(session.phaseRemainingFraction)
        try await Task.sleep(for: .milliseconds(250))
        XCTAssertLessThan(try XCTUnwrap(session.phaseRemainingFraction), initial)
        XCTAssertEqual(session.snapshot, screen,
                       "Subsecond timer motion must stay outside the whole-screen snapshot")
    }

    func testEndingSessionCancelsTheSleepingTickerWithoutOneLastUpdate() async throws {
        let session = session(hold: 10, rest: 20, leadIn: 0)
        session.begin()
        // Let the ticker enter its first sleep, then cancel it from the real teardown.
        try await Task.sleep(for: .milliseconds(20))
        session.end()
        let stoppedAt = session.now
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(session.now, stoppedAt,
                       "A cancelled sleep must return instead of updating a dismissed runner")
    }

    func testSkippingConsecutiveSetsPublishesEachNewGripToTheView() {
        var draft = RoutineDraft.blank(named: "Skip grip changes")
        let grips = [GripSpec(), GripSpec(edgeMM: 10, fingers: .frontTwo),
                     GripSpec(edgeMM: 15, position: .fingerCurl)]
        draft.plan.sets = grips.map { SetPlan(grip: $0, repsPerSide: 2) }
        draft.plan.leadInSeconds = 0
        draft.plan.setBreakSeconds = 0
        let session = RunnerSession(template: SessionTemplate(draft: draft, sortIndex: 0),
                                    device: DeviceStore(client: RecordingProgressorClient()),
                                    timerOnly: true)
        session.begin()
        defer { session.end() }
        session.startIfReady(cause: .initial)
        XCTAssertNil(session.snapshot.newGripID)
        session.send(.skipSet)
        let firstChange = session.snapshot.newGripID
        XCTAssertNotNil(firstChange)
        XCTAssertEqual(session.snapshot.grip, grips[1])
        session.send(.skipSet)
        XCTAssertNotNil(session.snapshot.newGripID)
        XCTAssertNotEqual(session.snapshot.newGripID, firstChange)
        XCTAssertEqual(session.snapshot.grip, grips[2])
    }

    func testGripCueRestLifetimeSurvivesPauseAndEndsOnTheSameGrip() async throws {
        var draft = RoutineDraft.blank(named: "Rest cue")
        draft.plan.handMode = .bothHands
        draft.plan.sets = [SetPlan(grip: GripSpec(), repsPerSide: 1),
                           SetPlan(grip: GripSpec(edgeMM: 10), repsPerSide: 1)]
        draft.plan.holdSeconds = 60
        draft.plan.leadInSeconds = 0
        draft.plan.setBreakSeconds = 1
        let session = RunnerSession(template: SessionTemplate(draft: draft, sortIndex: 0),
                                    device: DeviceStore(client: RecordingProgressorClient()),
                                    timerOnly: true)
        session.begin()
        defer { session.end() }
        session.startIfReady(cause: .initial)
        session.send(.skipRep)
        XCTAssertTrue(session.snapshot.gripChangesNext)
        let id = try XCTUnwrap(session.snapshot.newGripID)
        session.send(.pause)
        XCTAssertTrue(session.snapshot.gripChangesNext)
        XCTAssertEqual(session.snapshot.newGripID, id)
        session.send(.resume)
        let deadline = ContinuousClock.now + .seconds(3)
        while session.snapshot.gripChangesNext && ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertFalse(session.snapshot.gripChangesNext)
        XCTAssertEqual(session.snapshot.newGripID, id, "Rest ends without reintroducing the same grip")
    }

    /// Bounded poll rather than a fixed sleep — the session is driven by its own 100 ms
    /// wall-clock ticker, so how long a phase takes to arrive is not something a test can
    /// assume.
    private func waitForResting(_ session: RunnerSession,
                                timeout: Duration = .seconds(5)) async throws {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if case .resting = session.snapshot.phase { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTFail("never reached the rest phase — the assertion below would have been "
                + "checking a state this test never got to")
    }

    /// Pausing mid-hold must FREEZE the numeral, not reset it to the full hold length.
    func testPausingAHoldFreezesTheDisplayedNumeral() async throws {
        let session = session(hold: 10, rest: 20, leadIn: 0)
        session.begin()
        defer { session.end() }
        session.startIfReady(cause: .initial)

        // The session's own 100 ms ticker drives `secondsShown`; let it bank real time.
        try await Task.sleep(for: .milliseconds(1200))
        let running = session.snapshot.secondsShown
        XCTAssertLessThan(running, 10, "the hold has started counting down")

        session.send(.pause)
        let atPause = session.snapshot.secondsShown
        try await Task.sleep(for: .milliseconds(900))

        XCTAssertEqual(session.snapshot.secondsShown, atPause,
                       "a paused hold neither counts down nor snaps back to the full hold")
        XCTAssertLessThan(session.snapshot.secondsShown, 10,
                          "and specifically it does not reset to the rep's full length — "
                          + "that is the bug this whole file exists for")
    }

    /// The dial and the numeral are one value in two channels; a paused rest must not
    /// leave the ring draining behind the pause button.
    func testPausingARestFreezesTheRingFraction() async throws {
        let session = session(hold: 1, rest: 20, leadIn: 0)
        session.begin()
        defer { session.end() }
        session.startIfReady(cause: .initial)

        // **Poll for the phase; do not assume a sleep is long enough.** Asserting nothing
        // here would let a slow ticker leave the session still WORKING, and the test would
        // then pass on the working fraction while paused-rest handling was broken — it
        // would be checking a state it never reached.
        try await waitForResting(session)

        session.send(.pause)
        let frozen = try XCTUnwrap(session.phaseRemainingFraction)
        try await Task.sleep(for: .milliseconds(900))

        XCTAssertEqual(try XCTUnwrap(session.phaseRemainingFraction), frozen,
                       accuracy: 0.001)
    }

    func testFinishTimestampFreezesBeforeSummarySave() async throws {
        let session = session(hold: 10, rest: 20, leadIn: 0)
        session.begin()
        defer { session.end() }
        session.send(.abort)
        let finished = try XCTUnwrap(session.finishedAt)
        XCTAssertGreaterThanOrEqual(finished, session.startedAt)
        try await Task.sleep(for: .milliseconds(100))
        session.send(.tick)
        XCTAssertEqual(session.finishedAt, finished)
        XCTAssertLessThan(finished, Date.now)
    }
}
