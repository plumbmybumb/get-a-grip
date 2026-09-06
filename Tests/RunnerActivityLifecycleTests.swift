// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

@MainActor
final class RunnerActivityLifecycleTests: XCTestCase {
    private final class Recorder: RunnerActivityPublishing {
        var isRunning = false
        var starts = 0
        var ends = 0
        var states: [SessionActivity.ContentState] = []
        func start(routineName: String, plannedReps: Int, setCount: Int,
                   state: SessionActivity.ContentState) {
            starts += 1
            isRunning = true
            states.append(state)
        }
        func update(_ state: SessionActivity.ContentState) async {
            if isRunning { states.append(state) }
        }
        func end() async {
            guard isRunning else { return }
            isRunning = false
            ends += 1
        }
    }

    func testReleaseRestPauseAndFinishPublishTheirActualLifecycle() async throws {
        var draft = RoutineDraft.blank(named: "Activity lifecycle")
        draft.plan.sets = [SetPlan(repsPerSide: 2)]
        draft.plan.handMode = .bothHands
        draft.plan.leadInSeconds = 0
        draft.plan.holdSeconds = 1
        draft.plan.restSeconds = 20
        draft.plan.waitForReleaseBeforeRest = true
        let client = RecordingProgressorClient()
        let device = DeviceStore(client: client)
        client.setState(.connected)
        let activity = Recorder()
        let session = RunnerSession(template: SessionTemplate(draft: draft, sortIndex: 0),
                                    device: device, liveActivity: activity)
        session.begin()
        defer { session.end() }
        for index in 1...120 {
            session.send(.sample(ForceSample(kg: 10, deviceMicros: UInt32(index * 12_500))))
        }
        guard case .releasing = session.snapshot.phase else { return XCTFail("Expected release wait") }
        await Task.yield()
        XCTAssertEqual(activity.states.last?.phase, .releasing)
        XCTAssertNil(activity.states.last?.endsAt, "LET GO waits for force, without a rest deadline")
        session.send(.pause)
        await Task.yield()
        XCTAssertEqual(activity.states.last?.phase, .paused)
        XCTAssertNil(activity.states.last?.endsAt)
        session.send(.resume)
        // The real release debounce is sample-driven too.
        for index in 121...160 {
            session.send(.sample(ForceSample(kg: 0, deviceMicros: UInt32(index * 12_500))))
        }
        await Task.yield()
        XCTAssertEqual(activity.states.last?.phase, .resting)
        let restDeadline = try XCTUnwrap(activity.states.last?.endsAt)
        XCTAssertGreaterThan(restDeadline.timeIntervalSinceNow, 18,
                             "The full rest clock is anchored when the load releases")
        let published = activity.states.count
        session.send(.abort)
        await Task.yield()
        XCTAssertEqual(activity.ends, 1, "End on the summary, before save/discard")
        XCTAssertEqual(activity.states.count, published, "Finish must not publish a phantom rest")

        let commands = client.commands.count
        session.startIfReady(cause: .foreground)
        session.connectionChanged(isConnected: true)
        session.wakeStream()
        session.send(.tick)
        await Task.yield()
        XCTAssertEqual(client.commands.count, commands, "A summary never restarts the gauge")
        XCTAssertEqual(activity.starts, 1)
        XCTAssertEqual(activity.states.count, published)
    }
}
