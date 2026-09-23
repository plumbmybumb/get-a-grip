// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Whether — and which way — the watch face turns for the pull in progress.
///
/// Pulling on a block, palm down, puts the watch under your eyes with the HAND past the
/// crown edge, so the screen's 12 o'clock points across the arm (Nuri, 2026-09-19). A
/// quarter turn puts content-up toward the hand. Automatic: on for the watch hand's
/// pulls, off for the other hand's and for rests and controls, where the wrist is back
/// at your side. Pure and in `Shared/`, so the rule is tested on the phone.
enum FaceFlipPolicy {
    /// Degrees to turn the face, clockwise positive. The hand is past 3 o'clock on the
    /// left wrist and past 9 on the right (the crown setting never moves it).
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
