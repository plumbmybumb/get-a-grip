// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// While the gauge CONNECTS (`.idle`), the screen already names the first pull's grip and
/// hand — the plan knows them, and setting up the right hand is exactly what the wait is
/// for (Nuri's Duo, 2026-10-31: the far view showed no grip until the session went live).
@MainActor
final class RunnerConnectingIdentityTests: XCTestCase {
    func testConnectingNamesTheFirstPullsGripAndHand() {
        let first = GripSpec(edgeMM: 20, fingers: .frontTwo, position: .openHand)
        let second = GripSpec(edgeMM: 15, fingers: .frontTwo, position: .halfCrimp)
        var draft = RoutineDraft.blank(named: "Connecting")
        draft.plan.handMode = .alternateEachRep
        draft.plan.sets = [SetPlan(grip: first, repsPerSide: 2), SetPlan(grip: second, repsPerSide: 2)]
        let session = RunnerSession(template: SessionTemplate(draft: draft, sortIndex: 0),
                                    device: DeviceStore(client: RecordingProgressorClient()),
                                    liveActivity: RunnerActivityRecorder(), cues: RunnerCueRecorder(),
                                    draftStore: nil)
        session.begin()
        defer { session.end() }

        XCTAssertEqual(session.snapshot.phase, .idle, "no gauge has connected")
        XCTAssertEqual(session.snapshot.grip, first, "the first pull's grip, before the session is live")
        let firstSlot = PlanMath.sequence(for: draft.plan).first
        XCTAssertEqual(session.snapshot.side, firstSlot?.side, "and the hand it starts on")
        XCTAssertFalse(session.isFinished)
    }
}

/// The dock's one-line rule for a label that SHRINKS rather than wraps: its text scales,
/// its capsule insets do not.
@MainActor
final class ActionRowOneLineTests: XCTestCase {
    func testAShrinkingLabelsFloorScalesTheTextButNotTheInsets() {
        let insets = Metrics.buttonHorizontalPadding * 2
        XCTAssertEqual(AdaptiveActionRow.shrunkWidth(ideal: insets + 100, minimumScale: 0.8),
                       insets + 80, accuracy: 0.001)
        XCTAssertEqual(AdaptiveActionRow.shrunkWidth(ideal: insets + 100, minimumScale: 1),
                       insets + 100, accuracy: 0.001)
        XCTAssertEqual(AdaptiveActionRow.shrunkWidth(ideal: 4, minimumScale: 0.8), insets,
                       accuracy: 0.001, "a label narrower than its insets never goes negative")
    }

    /// The hold title fits wherever the resting title did: at the resting floor, the
    /// hold floor leaves room for the longer English word (106 : 82 pt at 15 pt).
    func testTheHoldFloorCoversTheLongerHoldTitle() {
        let resting: CGFloat = 82, holding: CGFloat = 106
        XCTAssertLessThanOrEqual(holding * HoldToEndButton.holdingScale,
                                 resting * HoldToEndButton.restingScale)
    }
}
