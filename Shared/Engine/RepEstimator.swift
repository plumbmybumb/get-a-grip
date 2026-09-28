// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The live gauge's ESTIMATED rep count: a pull is counted once the load has risen
/// clearly off the gauge and then come back down to the baseline (Nuri, 2026-09-28).
///
/// An estimate, and the screen says so — there is no plan to hold it against, only the
/// shape of the signal. Three rules keep it honest:
/// - **A pull must be real to start**: at least `engageKg` (the same "on the edge" line
///   the max test uses) for `minimumPullSeconds`, so a knock on the gauge is not a rep.
/// - **It ends at the BASELINE, not at a dip.** The release line is a fraction of the
///   pull's own peak, floored near zero, so easing from 40 kg to 25 kg mid-pull is still
///   the same pull, and it has to stay down for `releaseSeconds` so a quick re-grip does
///   not split one pull in two.
/// - **It counts on the way DOWN** — "a spike and then a release", which is also the
///   only moment the pull is known to have happened.
///
/// It assumes a tared gauge: an untared one that rests above `engageKg` never releases,
/// so it counts nothing rather than inventing reps. Pure — no clock, device or store;
/// `t` is the store's monotone playback time in seconds.
struct RepEstimator: Equatable, Sendable {
    /// On the edge — `MaxAttempt.releaseKg`, so "pulling" is one idea across the app.
    static let engageKg: Double = MaxAttempt.releaseKg
    /// Above `engageKg` this long before it is a pull.
    static let minimumPullSeconds: TimeInterval = 0.2
    /// The baseline line, as a fraction of the pull's own peak…
    static let releaseFraction: Double = 0.15
    /// …but never under this, or load-cell drift could hold a pull open forever.
    static let releaseFloorKg: Double = 1
    /// Down at the baseline this long ends the pull.
    static let releaseSeconds: TimeInterval = 0.4

    private(set) var count = 0

    /// A pull is under way (confirmed, not yet released).
    private(set) var isPulling = false
    /// The pull in progress's highest reading.
    private var peakKg: Double = 0
    /// When the load crossed `engageKg`, while the pull is still being confirmed.
    private var engagedSince: TimeInterval?
    /// When the load most recently fell to the baseline, mid-pull.
    private var releasedSince: TimeInterval?

    /// Where the load must fall to for the pull under way to end.
    var releaseKg: Double {
        Swift.max(Self.releaseFloorKg, Self.releaseFraction * peakKg)
    }

    /// Feed one sample. Returns true when this sample completed a rep.
    @discardableResult
    mutating func add(_ kg: Double, at t: TimeInterval) -> Bool {
        guard kg.isFinite, t.isFinite else { return false }

        guard isPulling else {
            guard kg >= Self.engageKg else {
                engagedSince = nil
                peakKg = 0
                return false
            }
            let since = engagedSince ?? t
            engagedSince = since
            peakKg = Swift.max(peakKg, kg)
            if t - since >= Self.minimumPullSeconds {
                isPulling = true
                releasedSince = nil
            }
            return false
        }

        peakKg = Swift.max(peakKg, kg)
        guard kg < releaseKg else {
            releasedSince = nil
            return false
        }
        let since = releasedSince ?? t
        releasedSince = since
        guard t - since >= Self.releaseSeconds else { return false }
        count += 1
        clearPull()
        return true
    }

    /// Drop the pull in progress without counting it — the stream stopped, or the gauge
    /// was zeroed under it, so how it would have ended is unknowable.
    mutating func cancelPull() {
        clearPull()
    }

    mutating func reset() {
        self = RepEstimator()
    }

    private mutating func clearPull() {
        isPulling = false
        peakKg = 0
        engagedSince = nil
        releasedSince = nil
    }
}

/// A plain stopwatch: start, pause, reset. Pure — the caller supplies `now` in seconds
/// from a clock that keeps running while the phone sleeps (the gauge screen uses
/// `ContinuousClock`), so a paused screen cannot lose time.
struct Stopwatch: Equatable, Sendable {
    /// Time banked by earlier runs.
    private(set) var accumulated: TimeInterval = 0
    /// When the current run began; nil while stopped.
    private(set) var startedAt: TimeInterval?

    var isRunning: Bool { startedAt != nil }

    func elapsed(at now: TimeInterval) -> TimeInterval {
        guard let startedAt else { return accumulated }
        // A clock that steps backwards must not subtract banked time.
        return accumulated + Swift.max(0, now - startedAt)
    }

    mutating func start(at now: TimeInterval) {
        guard startedAt == nil else { return }
        startedAt = now
    }

    mutating func pause(at now: TimeInterval) {
        guard startedAt != nil else { return }
        accumulated = elapsed(at: now)
        startedAt = nil
    }

    mutating func reset() {
        self = Stopwatch()
    }

    /// "0:42.3", "12:05.0", "1:02:03.4" — tenths always, hours only when there are any.
    static func label(_ seconds: TimeInterval) -> String {
        let tenths = Int((Swift.max(0, seconds) * 10).rounded(.down))
        let hours = tenths / 36_000
        let minutes = tenths / 600 % 60
        let secs = tenths / 10 % 60
        let tenth = tenths % 10
        let ss = secs < 10 ? "0\(secs)" : "\(secs)"
        if hours > 0 {
            let mm = minutes < 10 ? "0\(minutes)" : "\(minutes)"
            return "\(hours):\(mm):\(ss).\(tenth)"
        }
        return "\(minutes):\(ss).\(tenth)"
    }
}
