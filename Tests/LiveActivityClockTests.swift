// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// What the Live Activity is told, and when it may be told anything at all.
@MainActor
final class LiveActivityClockTests: XCTestCase {
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
}
