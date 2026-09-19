// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// **The one decision-critical word on the runner**, for every screen that draws one.
///
/// PULL · RE-GRIP · EASE OFF · LET GO · REST · SET BREAK · PAUSED — this is the word you
/// act on with chalk on your hands, and the phone, the watch and the runner's own
/// gauge-free dial all say it. They said it from two hand-written copies of the same
/// switch, which is how the two DRIFTED: a gauge-free session on the phone opened with
/// CONNECTING, describing a device nobody had asked for, where the watch already said
/// GET READY. The watch was right, so that is what this ladder does — the drift is
/// resolved here rather than in whichever screen is edited next.
///
/// Pure, and beside `WatchFaceMood` on purpose: the face's COLOUR ladder and the
/// prompt's WORD ladder are the same state read two ways, so they take the same facts
/// and live in the same place. Neither may reach for a view, a store or a gauge.
///
/// The clock stopping without saying so looks like a bug, and the instinct it provokes —
/// pull harder — is the wrong one. So `.working` says what to DO instead, and with a
/// target range there are TWO ways to stall it: the reflex that fixes one makes the
/// other worse, which is why the word names which. `.releasing` is the same rule at the
/// other end of the rep — the hold is banked and the rest has not started, so silence
/// there would read as a frozen clock.
enum RunnerPromptWords {

    /// - Parameters:
    ///   - side: the hand this rep is on — its own prompt is the word while the clock
    ///     runs, since "LEFT" is the instruction once nothing is wrong.
    ///   - isConnected: a MEASURED session's gauge. Ignored when `timerOnly`.
    ///   - timerOnly: a session running on the clock alone. There is nothing to connect
    ///     to, so it is never CONNECTING.
    ///   - isSetBreak: describes the REST currently running, not the rep ahead of it.
    static func word(phase: RunnerPhase, side: Side?,
                     isConnected: Bool, timerOnly: Bool,
                     isDropped: Bool, isOverTarget: Bool, isSetBreak: Bool) -> String {
        switch phase {
        case .idle:
            return isConnected || timerOnly
                ? String(localized: "GET READY") : String(localized: "CONNECTING")
        case .leadIn:
            return String(localized: "GET READY")
        case .armed:
            return String(localized: "\(side?.prompt ?? "") — PULL")
        case .working:
            if isDropped { return String(localized: "RE-GRIP") }
            if isOverTarget { return String(localized: "EASE OFF") }
            return side?.prompt ?? ""
        case .releasing:
            return String(localized: "LET GO")
        case .resting:
            return isSetBreak ? String(localized: "SET BREAK") : String(localized: "REST")
        case .paused:
            return String(localized: "PAUSED")
        case .finished:
            return String(localized: "DONE")
        }
    }
}
