// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

enum RunnerScreenCue: Equatable {
    case pulling, releasing, warning

    /// Active pulling stays calmer than an instruction that needs attention.
    var lineWidth: CGFloat { self == .pulling ? 4 : 6 }
}

extension RunnerSnapshot {
    /// Use the engine's phase and warning latches, not displayed kilograms. A blue
    /// edge must never imply that a stale or rejected measured stream is banking time.
    /// Timer-only work has its own clock and ignores unrelated gauge readiness.
    func screenBorderCue(timerOnly: Bool, measuredSignalIsLive: Bool) -> RunnerScreenCue? {
        if showsReleaseBorder { return .releasing }
        guard case .working = phase else { return nil }
        if isDropped || isOverTarget { return .warning }
        if !timerOnly && (!hasSignal || !measuredSignalIsLive || linkIsDown || isRejectingStaleBatches) {
            return .warning
        }
        return .pulling
    }

    /// The release gate owns this cue. A paused release is deliberately excluded:
    /// paused samples cannot clear that gate, so the border would keep asking for an
    /// action the app was no longer observing. Resume restores the live instruction.
    var showsReleaseBorder: Bool {
        if case .releasing = phase { return true }
        return false
    }

    /// `side` already comes from the engine's displaySlot, including skips, set
    /// boundaries, grouped hands and paused rests. Never predict it from pull parity.
    var nextRestHand: Side? {
        switch phase {
        case .resting: side
        case .paused(let inner):
            if case .resting = inner { side } else { nil }
        default: nil
        }
    }

    var nextRestHandPrompt: String? {
        switch nextRestHand {
        case .left: String(localized: "LEFT HAND NEXT")
        case .right: String(localized: "RIGHT HAND NEXT")
        case .both: String(localized: "BOTH HANDS NEXT")
        case nil: nil
        }
    }
}
