// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// One max test: **the hardest the gauge saw you pull.**
///
/// `peakKg` is the highest single reading of the attempt, and that is the whole rule.
///
/// **It used to be a two-second sustained hold**, to discount a snatch at the edge. In
/// the hand that was wrong (Nuri, 2026-08-04: *"2s of consistent pull is too much, I
/// think we just measure peak"*): a real max peaks and decays at once, so it reported
/// well under what had just been pulled. A snatch now counts; if spikes ever become a
/// problem, the answer is a SHORT window (a few hundred ms), not a return to two seconds.
///
/// **A TIMED max is the AVERAGE over a window** (2026-10-01): "the most you can hold for
/// 10 s", for a routine prescribing 90 % of it. The window opens when the load first
/// crosses `releaseKg` and the result is the time-weighted mean force across it — the
/// rule Lattice scores its 7 s critical-force reps by, and what climbers' gauge apps
/// report. NOT the lowest point: an all-out pull fades, so the floor sits at the end or
/// at one wobble, and one noisy moment would decide the max. Letting go before the
/// window closes is no result: you did not hold for 10 s, so it is not a 10 s max.
///
/// The type also knows when an attempt is OVER, so pull-hold-let-go needs no tap (see
/// `isComplete`). Pure: no clock, device or store, so the rule is testable.
struct MaxAttempt: Hashable, Sendable {
    /// Below this you are off the edge (the runner's default threshold order). Used in
    /// BOTH directions — crossing it arms the attempt, staying below ends one — so
    /// there is one number rather than two that could disagree.
    static let releaseKg: Double = 2

    /// Off the edge this long, after pulling, ends a single-test attempt. Erring late is cheap:
    /// ending early on a re-grip throws away an effort someone paid for.
    static let releaseSeconds: TimeInterval = 2

    /// This attempt's own release window. A single test keeps the default; a visit of
    /// repeated pulls (`MaxAttemptLog`) ends each one sooner, because there a re-grip that
    /// splits one effort in two still logs its highest reading.
    let endsAfter: TimeInterval

    /// nil: a PEAK max. Otherwise the timed window, in seconds — see the type's note.
    let window: TimeInterval?

    init(endsAfter: TimeInterval = MaxAttempt.releaseSeconds, window: TimeInterval? = nil) {
        self.endsAfter = endsAfter
        self.window = window.flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
    }

    /// THE RESULT — the hardest single reading. Only ever climbs, so the figure on
    /// screen is always exactly what would be recorded right now.
    private(set) var peakKg: Double = 0

    /// You pulled, and then came off the edge for `releaseSeconds`. The caller can also
    /// stop by hand at any point; this exists so the ordinary case needs no tap at all.
    private(set) var isComplete = false

    /// **A result means you PULLED**, not that a number arrived: load-cell drift would
    /// make `peakKg > 0` offer a 0.4 kg max to someone who never touched the edge, and
    /// let a forgotten screen end its own attempt. Reuses `releaseKg`, so "on the edge"
    /// stays one idea. A timed attempt also needs its whole window.
    var hasResult: Bool {
        guard let window else { return hasPulled }
        return hasPulled && !windowBroken && heldSeconds >= window
            && (averageKg ?? 0) >= Self.releaseKg
    }

    /// What this attempt records: the peak, or the window's average when timed. nil until
    /// there is a result, so a short timed pull can never be mistaken for a max.
    var resultKg: Double? {
        guard hasResult else { return nil }
        return window == nil ? peakKg : averageKg
    }

    /// Ever over the threshold — what release detection needs, separate from `hasResult`
    /// because a timed pull is ON the edge long before its result exists.
    private var hasPulled: Bool { peakKg >= Self.releaseKg }

    // MARK: Timed window

    /// When the window opened: the first sample at or over `releaseKg`.
    private var windowStart: TimeInterval?
    /// The previous sample, held until the next one (sample-and-hold integration, so
    /// bursty delivery weighs each reading by how long it actually stood).
    private var lastT: TimeInterval?
    private var lastKg: Double = 0
    /// ∫ kg dt across the window so far, in kg·s.
    private var impulse: Double = 0
    /// Seconds of the window covered so far, capped at the window.
    private(set) var heldSeconds: TimeInterval = 0
    /// Came off the edge (under `releaseKg`) before the window closed. The window stops
    /// there: time spent letting go is not time held, and averaging the zeros in would let
    /// a 9 s hang plus the release wait pass as a 10 s max.
    private(set) var windowBroken = false

    /// The window's time-weighted mean so far — the live figure during a timed pull and
    /// the result once the window closes. nil for a peak attempt or before any time passed.
    var averageKg: Double? {
        guard window != nil, heldSeconds > 0 else { return nil }
        return impulse / heldSeconds
    }

    /// Seconds still to hold, for the countdown. nil for a peak attempt.
    var remainingSeconds: TimeInterval? {
        guard let window else { return nil }
        return Swift.max(0, window - heldSeconds)
    }

    /// When the load most recently fell below `releaseKg`.
    private var releasedAt: TimeInterval?

    /// Feed one sample. `t` is in SECONDS and must increase monotonically; the store's
    /// playback clock is built to guarantee that across tares and device counter resets
    /// (see `DeviceStore.playbackTime`).
    mutating func add(_ kg: Double, at t: TimeInterval) {
        guard !isComplete, kg.isFinite, t.isFinite else { return }

        peakKg = Swift.max(peakKg, kg)
        accrueWindow(kg, at: t)

        guard kg < Self.releaseKg else {
            releasedAt = nil
            return
        }
        // `hasPulled` IS "has ever been above the threshold" — `peakKg` is a running
        // maximum, so no separate flag can drift out of step with it.
        guard hasPulled else { return }
        let since = releasedAt ?? t
        releasedAt = since
        if t - since >= endsAfter { isComplete = true }
    }

    private mutating func accrueWindow(_ kg: Double, at t: TimeInterval) {
        guard let window else { return }
        guard let start = windowStart else {
            guard kg >= Self.releaseKg else { return }
            windowStart = t
            lastT = t
            lastKg = kg
            return
        }
        guard let previous = lastT, heldSeconds < window, !windowBroken else { return }
        let until = Swift.min(t, start + window)
        let dt = until - previous
        guard dt > 0 else { return }
        // The previous reading stood until now, whatever this one is.
        impulse += lastKg * dt
        if kg < Self.releaseKg, t < start + window {
            heldSeconds = until - start
            windowBroken = true
            return
        }
        // Exactly `window` once the window has closed: `(start + window) - start` need
        // not round-trip in floating point, and `hasResult` compares against it.
        heldSeconds = t >= start + window ? window : Swift.min(window, until - start)
        lastT = t
        lastKg = kg
    }

    /// Take what has been pulled so far — the manual "Done", and the timeout.
    /// Idempotent.
    mutating func finish() {
        isComplete = true
    }
}
