// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Whether — and which way — the watch face turns for the pull in progress.
///
/// Pulling on a block in front of you, palm down with the forearm level, puts the watch
/// hand's wrist right under your eyes with the HAND past the crown edge: the screen's
/// 12 o'clock points across the arm, so the text runs along it (Nuri's photo,
/// 2026-09-19). A quarter turn, not a half: content-up toward the hand, laid out along
/// the screen's long axis. The watch knows which wrist it is on and the runner knows
/// which hand is pulling, so the turn is automatic — on for the watch hand's pulls, off
/// for the other hand's, and off for the rests and the controls, where the wrist is back
/// at your side and a tap is made the normal way up.
///
/// Pure and in `Shared/`, so the rule is tested on the phone; the watch only draws what
/// it says.
enum FaceFlipPolicy {
    /// Degrees to turn the face, clockwise positive as SwiftUI counts them. The hand is
    /// past the 3 o'clock edge on the left wrist and past 9 on the right — the crown
    /// setting only keeps the screen upright, it never moves the hand — so up goes to
    /// 3 o'clock on the left wrist (a clockwise quarter) and to 9 on the right.
    static func rotationDegrees(wrist: Side) -> Double {
        wrist == .left ? 90 : -90
    }

    static func shouldFlip(phase: RunnerPhase, side: Side?, wrist: Side, enabled: Bool) -> Bool {
        guard enabled, let side else { return false }
        switch phase {
        // The hand is on the block: placing it during the count-in, waiting for the
        // load, pulling, and still holding while the release gate waits for LET GO.
        case .leadIn, .armed, .working, .releasing:
            // Both hands on the block puts the watch hand on it too.
            return side == wrist || side == .both
        // A rest describes the NEXT hand while the current one hangs at your side, and
        // a pause is something you tapped — the normal way up.
        case .idle, .resting, .paused, .finished:
            return false
        }
    }
}
