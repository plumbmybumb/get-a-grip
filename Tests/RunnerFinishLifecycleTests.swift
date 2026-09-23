// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// What a session lets go of at the finish, and when.
@MainActor
final class RunnerFinishLifecycleTests: XCTestCase {
    func testTheFinishLetsGoOfTheGaugeTheScreenAndTheCardBeforeTheViewGoes() async throws {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)
        let cues = RunnerCueRecorder()
        let activity = RunnerActivityRecorder()
        let lockDepth = IdleTimerLock.depth
        let session = RunnerSession(
            template: RunnerFixtures.template(), device: device,
            liveActivity: activity, cues: cues,
            draftStore: nil,
            activityStartDelay: nil, finishCueTail: .zero)
        session.begin()
        XCTAssertEqual(IdleTimerLock.depth, lockDepth + 1)
        XCTAssertTrue(device.isStreaming)
        XCTAssertNotNil(device.onSample)

        RunnerFixtures.pull(session, kg: 10, samples: 120, from: 1)
        XCTAssertTrue(session.isFinished, "One pull of one second is the whole plan")

        XCTAssertFalse(device.isStreaming,
                       "A finished session must stop the stream, or the background grace never disconnects")
        XCTAssertEqual(client.commands.last, .stopWeightMeasurement)
        XCTAssertNil(device.onSample)
        XCTAssertEqual(IdleTimerLock.depth, lockDepth, "The summary does not keep the screen awake")
        XCTAssertEqual(cues.ends, 0, "The finish chord was just queued; the engines stay up for it")
        XCTAssertTrue(cues.played.contains(.sessionCompleted))
        await session.lastActivityPush?.value
        XCTAssertEqual(activity.ends, 1)

        let tail = try XCTUnwrap(session.cueShutdown, "The cue shutdown waits out the tail")
        await tail.value
        XCTAssertEqual(cues.ends, 1, "…and are let go once it has sounded")

        // The view going away later undoes nothing twice.
        let commands = client.commands.count
        session.end()
        await session.lastActivityPush?.value
        XCTAssertEqual(IdleTimerLock.depth, lockDepth, "An unbalanced release would be a bug")
        XCTAssertEqual(cues.ends, 1)
        XCTAssertEqual(activity.ends, 1)
        XCTAssertEqual(client.commands.count, commands, "Nothing left to stop")
    }

    func testLeavingDuringTheFinishChordEndsTheCuesAtOnceAndOnlyOnce() async throws {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)
        let cues = RunnerCueRecorder()
        let session = RunnerSession(
            template: RunnerFixtures.template(), device: device,
            liveActivity: RunnerActivityRecorder(), cues: cues,
            draftStore: nil,
            activityStartDelay: nil, finishCueTail: .zero)
        session.begin()
        RunnerFixtures.pull(session, kg: 10, samples: 120, from: 1)
        XCTAssertTrue(session.isFinished)
        let tail = try XCTUnwrap(session.cueShutdown)
        session.end()
        XCTAssertEqual(cues.ends, 1)
        await tail.value
        XCTAssertEqual(cues.ends, 1, "The cancelled tail must not end them a second time")
    }

    func testAnUnfinishedSessionLeftByTheViewStillGetsTheWholeTeardown() async {
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)
        let cues = RunnerCueRecorder()
        let lockDepth = IdleTimerLock.depth
        let session = RunnerSession(
            template: RunnerFixtures.template(holdSeconds: 10), device: device,
            liveActivity: RunnerActivityRecorder(), cues: cues,
            draftStore: nil,
            activityStartDelay: nil)
        session.begin()
        RunnerFixtures.pull(session, kg: 10, samples: 20, from: 1)
        XCTAssertFalse(session.isFinished)
        session.end()
        XCTAssertFalse(device.isStreaming)
        XCTAssertNil(device.onSample)
        XCTAssertEqual(IdleTimerLock.depth, lockDepth)
        XCTAssertEqual(cues.ends, 1)
    }
}
