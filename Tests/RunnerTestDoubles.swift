// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// What a `RunnerSession` hands its seams, recorded — shared by the finish-lifecycle,
/// draft and Live Activity tests.
@MainActor
final class RunnerCueRecorder: RunnerCuePlaying {
    var begins = 0
    var ends = 0
    var played: [RunnerCue] = []
    func begin() { begins += 1 }
    func end() { ends += 1 }
    func play(_ cue: RunnerCue) { played.append(cue) }
    func gripChanged() {}
}

@MainActor
final class RunnerActivityRecorder: RunnerActivityPublishing {
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

@MainActor
enum RunnerFixtures {
    /// One set, both hands, no lead-in and no release wait: the shortest plan that is
    /// still a real session.
    static func template(holdSeconds: Int = 1, reps: Int = 1) -> SessionTemplate {
        var draft = RoutineDraft.blank(named: "Finish lifecycle")
        draft.plan.sets = [SetPlan(repsPerSide: reps)]
        draft.plan.handMode = .bothHands
        draft.plan.leadInSeconds = 0
        draft.plan.holdSeconds = holdSeconds
        draft.plan.restSeconds = 20
        draft.plan.waitForReleaseBeforeRest = false
        return SessionTemplate(draft: draft, sortIndex: 0)
    }

    /// 80 Hz of `kg`, continuing the device clock from `from`.
    static func pull(_ session: RunnerSession, kg: Double, samples: Int, from: Int) {
        for index in from..<(from + samples) {
            session.send(.sample(ForceSample(kg: kg, deviceMicros: UInt32(index * 12_500))))
        }
    }
}
