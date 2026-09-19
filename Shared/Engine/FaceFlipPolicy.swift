// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Whether the watch face should be drawn upside down for the pull in progress.
///
/// Pulling on a block in front of you — palm down, forearm level — puts the watch
/// hand's wrist right under your eyes, and the face reads upside down: the crown points
/// away and 12 o'clock points at your elbow (Nuri, 2026-09-19). The watch knows which
/// wrist it is on and the runner knows which hand is pulling, so the flip is automatic:
/// on for the watch hand's pulls, off for the other hand's, and off for the rests and the
/// controls, where the wrist is back at your side and a tap is made the normal way up.
///
/// Pure and in `Shared/`, so the rule is tested on the phone; the watch only draws what
/// it says.
enum FaceFlipPolicy {
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
