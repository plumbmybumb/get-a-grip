// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// One max test: **the hardest the gauge saw you pull.**
///
/// `peakKg` is the highest single reading of the attempt, and that is the whole rule.
///
/// **It used to be a sustained hold** — the highest load you never dropped below for two
/// continuous seconds, computed as a sliding-window minimum — on the reasoning that a
/// snatch at the edge spikes the load for a tenth of a second and reads as a number you
/// cannot actually hold. That reasoning was sound in the abstract and wrong in the hand
/// (Nuri, 2026-08-04, having tried it: *"2s of consistent pull is too much, I think we
/// just measure peak"*). A real max effort peaks and begins decaying immediately, so
/// demanding two full seconds AT that peak measured something nobody's fingers actually
/// do: it reliably reported a number well under what had just been pulled, and the
/// person holding the gauge is the one who can tell.
///
/// **What that trade buys and costs:** a dynamic snatch now counts, so the figure is
/// whatever the gauge saw. In exchange the number matches what the person watched
/// happen, which is what makes it trustworthy enough to train against. If spikes ever do
/// become a problem, the answer is a SHORT window — a few hundred milliseconds smooths
/// sensor noise without demanding a plateau — and not a return to two seconds.
///
/// What the type still earns its keep for is knowing when an attempt is OVER, so that
/// pull-hold-let-go needs no tap; see `isComplete`. Pure and value-typed: no clock of
/// its own, no device, no store, which is what makes the rule testable rather than
/// something only verifiable by hanging off a gauge.
struct MaxAttempt: Hashable, Sendable {
    /// Below this you count as off the edge. Same order as the runner's default
    /// engagement threshold: it separates "hanging on" from "hand resting on it". Used
    /// in BOTH directions — crossing above it arms the attempt, staying below it ends
    /// one — so there is one number to reason about rather than two that could disagree.
    static let releaseKg: Double = 2

    /// Off the edge this long, once you have actually pulled, ends the attempt on its
    /// own. The cost of being wrong is asymmetric: finishing early on a re-grip throws
    /// away an effort someone paid for, while finishing late costs a two-second wait to
    /// someone who has already stopped pulling.
    static let releaseSeconds: TimeInterval = 2

    /// THE RESULT — the hardest single reading. Only ever climbs, so the figure on
    /// screen is always exactly what would be recorded right now.
    private(set) var peakKg: Double = 0

    /// You pulled, and then came off the edge for `releaseSeconds`. The caller can also
    /// stop by hand at any point; this exists so the ordinary case needs no tap at all.
    private(set) var isComplete = false

    /// **A result means you PULLED**, not merely that a number arrived. Any load cell
    /// reports a few hundred grams of drift and noise while nothing is on it, so
    /// `peakKg > 0` would offer to record a 0.4 kg max for someone who has not touched
    /// the edge — and would let a screen opened and forgotten end its own attempt.
    /// Reusing `releaseKg` rather than inventing a second constant keeps "on the edge"
    /// a single idea: crossing it is what starts an attempt, and dropping under it for
    /// `releaseSeconds` is what ends one.
    var hasResult: Bool { peakKg >= Self.releaseKg }

    /// When the load most recently fell below `releaseKg`.
    private var releasedAt: TimeInterval?

    /// Feed one sample. `t` is in SECONDS and must increase monotonically; the store's
    /// playback clock is built to guarantee that across tares and device counter resets
    /// (see `DeviceStore.playbackTime`).
    mutating func add(_ kg: Double, at t: TimeInterval) {
        guard !isComplete, kg.isFinite, t.isFinite else { return }

        peakKg = Swift.max(peakKg, kg)

        guard kg < Self.releaseKg else {
            releasedAt = nil
            return
        }
        // `hasResult` IS "has ever been above the threshold" — `peakKg` is a running
        // maximum, so no separate flag can drift out of step with it.
        guard hasResult else { return }
        let since = releasedAt ?? t
        releasedAt = since
        if t - since >= Self.releaseSeconds { isComplete = true }
    }

    /// Take what has been pulled so far — the manual "Done", and the timeout.
    /// Idempotent.
    mutating func finish() {
        isComplete = true
    }
}
