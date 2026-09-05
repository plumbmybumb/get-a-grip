// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import ActivityKit
import Foundation
import SwiftUI

/// The Live Activity contract — **the one type the app and the widget both compile**,
/// which is why it lives in `Shared/` beside the engine rather than in either target.
///
/// A Live Activity is a separate process rendering from a value you hand it. Everything
/// it needs must be IN the value: the widget cannot reach the store, the gauge, or the
/// runner. So this carries the whole picture of a moment in a session, and nothing here
/// is a reference to something the widget would have to go and look up.
///
/// **What it deliberately does NOT carry: a force trace.** ActivityKit coalesces and
/// throttles updates, and Apple's guidance is that Live Activities are for glanceable
/// state rather than streaming. The gauge produces ~80 samples a second; pushing even a
/// fraction of that would be dropped, and would spend the update budget for the whole
/// session in the first minute. The load shows as the CURRENT number, updated when the
/// runner already had reason to publish — which is honest and costs nothing extra.
struct SessionActivity: ActivityAttributes {
    /// Frozen for the life of the activity — the routine you started.
    var routineName: String
    var plannedReps: Int
    var setCount: Int

    struct ContentState: Codable, Hashable {
        /// The grip being pulled, or the one coming up during a rest. The widget draws
        /// the same hand the island does in-app.
        var grip: GripSpec
        var side: Side
        var phase: Phase
        var setNumber: Int
        /// Which pull you are ON, matching the runner's own counter.
        var repPosition: Int
        /// The load this rep is aiming for, already resolved. nil when there is none.
        ///
        /// The TARGET rather than the live reading, deliberately. A live kilogram figure
        /// would be stale the instant after it was pushed — updates only happen on state
        /// changes — so it would sit there confidently wrong between reps. The target is
        /// constant for the whole rep, which makes it the only load number this surface
        /// can tell the truth about.
        var targetLoKg: Double?
        var targetHiKg: Double?

        /// **When the clock currently running runs out.** A Date rather than a count of
        /// seconds, because `Text(timerInterval:)` in a widget then counts down on its
        /// own, every second, with NO further updates from the app. That one choice is
        /// what makes a twenty-minute session affordable: the only pushes are real state
        /// changes — a rep ending, a hand swapping — not the passage of time.
        var endsAt: Date?

        /// The hold ahead, in seconds, while **armed** — when no clock is running at all.
        ///
        /// Armed waits on YOU, with no timeout by design, so there is no deadline to count
        /// down to. A live countdown there would be a lie (it would run while you were
        /// still chalking up) and a dash says nothing. The length of the hold you are about
        /// to do is the honest answer, drawn dimmed so a static number cannot be mistaken
        /// for a stalled one.
        var pendingSeconds: Int?

        var targetBand: ClosedRange<Double>? {
            SetPlan.band(lo: targetLoKg, hi: targetHiKg)
        }
    }

    /// What the session is doing, reduced to the states worth telling someone who is
    /// looking at a lock screen. The runner's own phase has more cases; none of the others
    /// change what you should do with your hands.
    enum Phase: String, Codable, Hashable {
        case leadIn, armed, pulling, resting, paused

        var word: String {
            switch self {
            case .leadIn:  String(localized: "Get ready")
            case .armed:   String(localized: "Pull now")
            case .pulling: String(localized: "Holding")
            case .resting: String(localized: "Rest")
            case .paused:  String(localized: "Paused")
            }
        }

        /// Whether the hand should read as "do this NOW" or "this is what's coming".
        var isActive: Bool { self == .pulling || self == .armed }
    }
}

// MARK: - The phase, as a colour

/// **The Live Activity is colour-coded on the same ladder as the runner screen**
/// (`RunnerView.tint` → `StatusTint`), so a glance at the phone on the floor says exactly
/// what the app in your hand would (Nuri, 2026-08-09).
///
/// **It cannot pulse, and nothing here pretends to.** A Live Activity is an archived
/// render, not a running view: no `TimelineView`, no `repeatForever`, no animation the
/// widget drives itself. What it *does* get is a transition every time the app pushes a
/// new `ContentState` — ActivityKit cross-fades between the two renders — so the colour
/// changes ON THE BEAT, at exactly the moments that matter: armed, holding, rest. That
/// pulse is a consequence of the phase changing, which is the only thing worth pulsing
/// about.
extension SessionActivity.Phase {
    /// The vivid signal hue — the hand, the keyline, the phase word.
    var tint: Color {
        switch self {
        case .leadIn:  StatusTint.calm      // steel: nothing on you yet
        case .armed:   StatusTint.armed     // amber: waiting on YOU to take the load
        case .pulling: StatusTint.engaged   // bleu: force is on and the clock is running
        case .resting: StatusTint.calm
        case .paused:  StatusTint.armed     // also "waiting on you" — the app agrees
        }
    }

    /// The card's own background: the same hue dropped almost to black.
    ///
    /// Deliberately fixed hex rather than `tint.opacity(...)` over the system material.
    /// A translucent amber wash under white text is a legibility coin-toss depending on
    /// the wallpaper behind the lock screen, and this surface has one job — being read
    /// from across a room, upside down, mid-hang. Dark enough for white text at every
    /// hue; saturated enough that blue / amber / steel are unmistakable at a glance.
    var cardTint: Color {
        switch self {
        case .leadIn, .resting: Color(hex: "161A20")   // near-black slate
        case .armed:            Color(hex: "3A2408")   // deep amber
        case .pulling:          Color(hex: "0E2740")   // deep bleu
        case .paused:           Color(hex: "24282F")
        }
    }
}
