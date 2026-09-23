// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// What the Live Activity is told, and when it may be told anything at all.
@MainActor
final class LiveActivityClockTests: XCTestCase {
    func testAStoppedHoldClockStopsTheCardsCountdownToo() async throws {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)
        let activity = RunnerActivityRecorder()
        let session = RunnerSession(
            template: RunnerFixtures.template(holdSeconds: 10), device: device,
            liveActivity: activity, cues: RunnerCueRecorder(),
            activityStartDelay: nil)
        session.begin()
        defer { session.end() }

        RunnerFixtures.pull(session, kg: 10, samples: 40, from: 1)
        await Task.yield()
        guard case .working = session.snapshot.phase else { return XCTFail("Expected a running hold") }
        XCTAssertFalse(session.snapshot.holdClockIsStopped)
        XCTAssertEqual(activity.states.last?.phase, .pulling)
        XCTAssertNotNil(activity.states.last?.endsAt)
        XCTAssertNil(activity.states.last?.pendingSeconds)

        // Off the edge: RE-GRIP. The rep is alive, its clock is not.
        RunnerFixtures.pull(session, kg: 0, samples: 8, from: 41)
        await Task.yield()
        XCTAssertTrue(session.snapshot.isDropped)
        XCTAssertTrue(session.snapshot.holdClockIsStopped)
        let stopped = try XCTUnwrap(activity.states.last)
        XCTAssertEqual(stopped.phase, .pulling)
        XCTAssertNil(stopped.endsAt, "A deadline would count on to zero while nothing is held")
        XCTAssertEqual(stopped.pendingSeconds, session.snapshot.secondsShown,
                       "The card freezes on exactly what the runner shows")

        // Back on: a fresh deadline, and the frozen number goes.
        RunnerFixtures.pull(session, kg: 10, samples: 16, from: 49)
        await Task.yield()
        XCTAssertFalse(session.snapshot.holdClockIsStopped)
        XCTAssertNotNil(activity.states.last?.endsAt)
        XCTAssertNil(activity.states.last?.pendingSeconds)
    }

    func testALostLinkMidHoldIsAStoppedClockButARestIsNot() {
        var snapshot = RunnerSnapshot()
        snapshot.phase = .working(slot: 0)
        snapshot.linkIsDown = true
        XCTAssertTrue(snapshot.holdClockIsStopped)
        snapshot.phase = .resting(slot: 0)
        XCTAssertFalse(snapshot.holdClockIsStopped, "A rest runs on the wall clock whatever the gauge does")
        snapshot.phase = .armed(slot: 0)
        snapshot.linkIsDown = false
        snapshot.isOverTarget = true
        XCTAssertFalse(snapshot.holdClockIsStopped, "Armed never had a clock to stop")
        snapshot.phase = .working(slot: 0)
        XCTAssertTrue(snapshot.holdClockIsStopped, "EASE OFF stops it as surely as RE-GRIP")
    }

    func testTheCardStartsOffThePresentingFrameAndNotAtAllForASessionAlreadyGone() async throws {
        let device = DeviceStore(client: RecordingProgressorClient())
        let activity = RunnerActivityRecorder()
        let session = RunnerSession(
            template: RunnerFixtures.template(), device: device, timerOnly: true,
            liveActivity: activity, cues: RunnerCueRecorder(),
            activityStartDelay: .milliseconds(50))
        session.begin()
        defer { session.end() }
        XCTAssertEqual(activity.starts, 0, "No IPC inside the cover's onAppear")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(activity.starts, 1)
        XCTAssertEqual(activity.states.first?.repPosition, session.snapshot.pullPosition)

        let gone = RunnerActivityRecorder()
        let short = RunnerSession(
            template: RunnerFixtures.template(), device: device, timerOnly: true,
            liveActivity: gone, cues: RunnerCueRecorder(),
            activityStartDelay: .milliseconds(50))
        short.begin()
        short.end()
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(gone.starts, 0, "A card for a session already over would only be ended again")
    }

    func testStaleDatesFollowTheClockTheCardIsShowing() {
        let now = Date(timeIntervalSince1970: 10_000)
        var state = SessionActivity.ContentState(
            grip: GripSpec(), side: .both, phase: .resting, setNumber: 1, repPosition: 1,
            endsAt: now.addingTimeInterval(20))
        XCTAssertEqual(state.staleDate(now: now), now.addingTimeInterval(80),
                       "A countdown is stale a minute after its own zero")
        state.endsAt = now.addingTimeInterval(-5)
        XCTAssertEqual(state.staleDate(now: now), now.addingTimeInterval(60),
                       "Never a stale date already in the past")
        state.endsAt = nil
        state.phase = .armed
        XCTAssertEqual(state.staleDate(now: now), now.addingTimeInterval(600),
                       "No clock: ten minutes, re-pushed on the next change")
    }
}
