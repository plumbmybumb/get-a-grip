// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// A published protocol offered as a STARTING POINT from "New routine" (Nuri, 2026-09-25).
///
/// This reverses the 2026-08-10/-19 "nothing is offered" rule on purpose, and in the
/// narrowest form that works: the chooser opens with "Build from scratch", a protocol is
/// previewed in full before it is yours, and once added it is an ordinary routine you edit
/// like any other. It is NOT a library — nothing here is referenced after the add, so
/// changing a protocol below never rewrites a routine somebody already owns.
///
/// Each one transcribes the source's numbers as Nuri captured them from Frez. The C4
/// sources also name a contraction (active curl / passive pull); a grip cannot carry that
/// and the app does not show it (Nuri's call), so it is not recorded anywhere.
enum RoutineProtocol: String, CaseIterable, Identifiable, Hashable, Sendable {
    case dailyNoHangs
    case c4WarmUp
    case c4Max
    case fingerRehab

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dailyNoHangs: String(localized: "Daily no-hangs")
        case .c4WarmUp:     String(localized: "C4 warm-up")
        case .c4Max:        String(localized: "C4 max")
        case .fingerRehab:  String(localized: "Finger rehab")
        }
    }

    /// Attribution, never endorsement: the authors published these; they did not approve
    /// this app.
    var source: String {
        switch self {
        case .dailyNoHangs:     String(localized: "After Emil Abrahamsson")
        case .c4WarmUp, .c4Max: String(localized: "After Camp4 Human Performance")
        case .fingerRehab:      String(localized: "After Hooper's Beta")
        }
    }

    var blurb: String {
        switch self {
        case .dailyNoHangs:
            String(localized: "Light no-hangs across six grips, twice a day, every day.")
        case .c4WarmUp:
            String(localized: "Two pulls a hand per rung, ramping from 45 to 95 % of max.")
        case .c4Max:
            String(localized: "Three sets of three at 80–100 % of max, when you're fresh.")
        case .fingerRehab:
            String(localized: "Light 10-second pulls at 15–25 % of max to reload an injured finger.")
        }
    }

    /// Shown under the preview only where it is true.
    var caution: String? {
        switch self {
        case .fingerRehab:
            String(localized: "A rehab plan is not a diagnosis. Load an injured finger only as far as your physio or doctor advises.")
        case .dailyNoHangs, .c4WarmUp, .c4Max:
            nil
        }
    }

    /// Computed, never stored: every call mints fresh `SetPlan` ids, so adding the same
    /// protocol twice gives two routines that share no row identity (see `.starter`).
    var draft: RoutineDraft {
        switch self {
        case .dailyNoHangs: Self.dailyNoHangsDraft
        case .c4WarmUp:     Self.c4WarmUpDraft
        case .c4Max:        Self.c4MaxDraft
        case .fingerRehab:  Self.fingerRehabDraft
        }
    }

    // MARK: - The four

    /// A 20 mm ladder of six grips, alternating every pull. Same shape as `.starter`, with
    /// the positions and load the written protocol states.
    private static var dailyNoHangsDraft: RoutineDraft {
        func set(_ fingers: FingerSet, _ position: GripPosition, _ reps: Int) -> SetPlan {
            SetPlan(grip: GripSpec(edgeMM: 20, fingers: fingers, position: position),
                    repsPerSide: reps, targetLoPercent: 0.35, targetHiPercent: 0.45)
        }
        var d = RoutineDraft()
        d.plan.name = String(localized: "Daily no-hangs")
        d.plan.handMode = .alternateEachRep
        d.plan.holdSeconds = 10
        d.plan.restSeconds = 20
        d.plan.setBreakSeconds = 20
        d.plan.sets = [
            set(.four,       .halfCrimp, 6),
            set(.frontThree, .drag,      6),
            set(.frontTwo,   .drag,      2),
            set(.middleTwo,  .drag,      2),
            set(.frontTwo,   .halfCrimp, 2),
            set(.middleTwo,  .halfCrimp, 2),
        ]
        d.sessionsPerDay = 2
        d.reminders = [ReminderTime(hour: 8, minute: 0), ReminderTime(hour: 19, minute: 0)]
        return d
    }

    /// Six rungs of two pulls a hand, one hand at a time as C4 tests.
    private static var c4WarmUpDraft: RoutineDraft {
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        let bands: [(Double, Double)] = [
            (0.45, 0.55), (0.65, 0.75), (0.75, 0.85), (0.85, 0.95), (0.55, 0.65), (0.75, 0.85),
        ]
        var d = RoutineDraft()
        d.plan.name = String(localized: "C4 warm-up")
        d.plan.handMode = .alternateEachSet
        d.plan.holdSeconds = 5
        d.plan.restSeconds = 10
        d.plan.setBreakSeconds = 30
        d.plan.sets = bands.map { lo, hi in
            SetPlan(grip: grip, repsPerSide: 2, targetLoPercent: lo, targetHiPercent: hi)
        }
        d.sessionsPerDay = 1
        d.isOnDemand = true
        return d
    }

    /// Three by three at 80–100 %. The band is a LANE here, not a gate: gating would pause
    /// the clock on a pull that beats the max on file — the one pull this routine is for.
    private static var c4MaxDraft: RoutineDraft {
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        var d = RoutineDraft()
        d.plan.name = String(localized: "C4 max")
        d.plan.handMode = .alternateEachSet
        d.plan.holdSeconds = 4
        d.plan.restSeconds = 10
        d.plan.setBreakSeconds = 60
        d.plan.pausesOutsideTargetBand = false
        d.plan.sets = (0..<3).map { _ in
            SetPlan(grip: grip, repsPerSide: 3, targetLoPercent: 0.80, targetHiPercent: 1.0)
        }
        d.sessionsPerDay = 1
        d.isOnDemand = true
        return d
    }

    /// Five by five a hand, alternating every pull (Nuri, 2026-09-25), 10 s on and a full
    /// minute off, capped at 25 %. The band GATES here — the ceiling is the prescription,
    /// and EASE OFF is the right thing to hear over it.
    private static var fingerRehabDraft: RoutineDraft {
        let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)
        var d = RoutineDraft()
        d.plan.name = String(localized: "Finger rehab")
        d.plan.handMode = .alternateEachRep
        d.plan.holdSeconds = 10
        d.plan.restSeconds = 60
        d.plan.setBreakSeconds = 60
        d.plan.sets = (0..<5).map { _ in
            SetPlan(grip: grip, repsPerSide: 5, targetLoPercent: 0.15, targetHiPercent: 0.25)
        }
        d.sessionsPerDay = 1
        d.reminders = [ReminderTime(hour: 8, minute: 0)]
        return d
    }
}
