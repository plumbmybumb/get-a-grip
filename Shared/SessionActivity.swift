// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

#if canImport(ActivityKit)
import ActivityKit
#else
/// The wrist has no ActivityKit — the phone's Live Activity is mirrored to it by the
/// system — but the runner still speaks this contract, so the shape exists everywhere
/// and only the conformance is real on iOS.
protocol ActivityAttributes: Codable, Hashable {
    associatedtype ContentState: Codable & Hashable
}
#endif
import Foundation
import SwiftUI

/// The Live Activity contract — **the one type the app and the widget both compile**,
/// which is why it lives in `Shared/` beside the engine rather than in either target.
///
/// A Live Activity is a separate process rendering from a value: it cannot reach the
/// store, the gauge or the runner, so everything it draws must be IN this value.
///
/// **No force trace.** ActivityKit coalesces and throttles updates; ~80 samples a second
/// would be dropped and spend the session's update budget in the first minute.
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
        /// The load this rep is aiming for, already resolved; nil when there is none.
        /// The TARGET, not the live reading: pushes happen only on state changes, so a live
        /// figure would sit confidently wrong between them. The target stays true all rep.
        var targetLoKg: Double?
        var targetHiKg: Double?

        /// **When the running clock runs out.** A Date, so `Text(timerInterval:)` counts
        /// down on its own with NO further pushes — what makes a twenty-minute session
        /// affordable: the app pushes only real state changes, not the passage of time.
        var endsAt: Date?

        /// The hold ahead, in seconds, while **armed** — no clock runs, since armed waits
        /// on YOU with no timeout, and a countdown there would lie. Drawn dimmed so a static
        /// number cannot read as a stalled one. Also carries a hold whose clock STOPPED
        /// mid-rep (off the edge, over the band, link lost): the seconds still owed.
        var pendingSeconds: Int?

        var displayWeightUnit: WeightUnit? = nil

        var targetBand: ClosedRange<Double>? {
            SetPlan.band(lo: targetLoKg, hi: targetHiKg)
        }

        /// **When this card stops being trustworthy without another push** — e.g. the app
        /// died mid-session and the card kept saying "Pull". Sent with every request and
        /// update so the widget can draw `context.isStale`.
        ///
        /// A countdown gets its deadline plus a minute (every countdown ends in a pushed
        /// phase change). A phase with no clock (armed, let-go, paused) may legitimately
        /// sit still, so it gets ten minutes.
        func staleDate(now: Date = .now) -> Date {
            if let endsAt { return max(endsAt, now).addingTimeInterval(Self.staleAfterCountdown) }
            return now.addingTimeInterval(Self.staleWithoutCountdown)
        }

        static let staleAfterCountdown: TimeInterval = 60
        static let staleWithoutCountdown: TimeInterval = 10 * 60
    }

    /// What the session is doing, reduced to the states worth telling someone who is
    /// looking at a lock screen. The runner's own phase has more cases; none of the others
    /// change what you should do with your hands.
    enum Phase: String, Codable, Hashable {
        case leadIn, armed, pulling, releasing, resting, paused

        var word: String {
            switch self {
            case .leadIn:  String(localized: "Get ready")
            case .armed:   String(localized: "Pull now")
            case .pulling: String(localized: "Holding")
            case .releasing: String(localized: "Let go")
            case .resting: String(localized: "Rest")
            case .paused:  String(localized: "Paused")
            }
        }

        /// Whether the hand should read as "do this NOW" or "this is what's coming".
        var isActive: Bool { self == .pulling || self == .armed }

        /// Let-go waits for the load to drop; its following rest has not begun yet.
        var runsCountdown: Bool { self == .leadIn || self == .pulling || self == .resting }
    }
}

// MARK: - The phase, as a colour

/// **The Live Activity is colour-coded on the same ladder as the runner screen**
/// (`RunnerView.tint` → `StatusTint`), so a glance at the phone on the floor says exactly
/// what the app in your hand would (Nuri, 2026-08-09).
///
/// **It cannot pulse.** A Live Activity is an archived render, not a running view: no
/// `TimelineView`, no `repeatForever`. It does get a cross-fade on every push, so the
/// colour changes ON THE BEAT — armed, holding, rest — as a consequence of the phase.
extension SessionActivity.Phase {
    /// The vivid signal hue — the hand, the keyline, the phase word.
    var tint: Color {
        switch self {
        case .leadIn:  StatusTint.calm      // steel: nothing on you yet
        case .armed:   StatusTint.armed     // amber: waiting on YOU to take the load
        case .pulling: StatusTint.engaged   // bleu: force is on and the clock is running
        case .releasing, .resting: StatusTint.calm
        case .paused:  StatusTint.armed     // also "waiting on you" — the app agrees
        }
    }

    /// The card's own background: the same hue dropped almost to black.
    ///
    /// Fixed hex, not `tint.opacity(...)`: a translucent wash under white text is a
    /// legibility coin-toss against the wallpaper, and this is read across a room
    /// mid-hang. Dark enough for white text, saturated enough to tell the hues apart.
    var cardTint: Color {
        switch self {
        case .leadIn, .releasing, .resting: Color(hex: "161A20")   // near-black slate
        case .armed:            Color(hex: "3A2408")   // deep amber
        case .pulling:          Color(hex: "0E2740")   // deep bleu
        case .paused:           Color(hex: "24282F")
        }
    }
}
