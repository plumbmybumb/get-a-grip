// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// **The one decision-critical word on the runner**, for every screen that draws one.
///
/// PULL · RE-GRIP · EASE OFF · LET GO · REST · SET BREAK · PAUSED — the word you act on
/// with chalk on your hands, on the phone, the watch and the gauge-free dial. Two
/// hand-written copies drifted (the phone said CONNECTING in a gauge-free session), so
/// there is now one. It sits beside `WatchFaceMood`: the COLOUR ladder and the WORD
/// ladder are the same state read two ways, and neither may reach for a view or store.
///
/// A clock that stops silently looks like a bug and provokes the wrong reflex (pull
/// harder), so `.working` names what to DO — and with a band there are TWO ways to
/// stall, whose fixes are opposite. `.releasing` applies the same rule at the other end.
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
