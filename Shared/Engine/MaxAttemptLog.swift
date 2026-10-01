// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// A max-test VISIT: **every pull is an attempt**, logged against the hand it was pulled
/// with, for as long as the screen is open.
///
/// It replaced one-attempt-per-Start (Nuri, 2026-09-25, after using Frez's version): a
/// max is found by pulling again and again until the number stops climbing, and a Start
/// button between every pull made each retry a restart. Now the gauge reads from the
/// moment the screen opens, crossing `MaxAttempt.releaseKg` begins an attempt, and coming
/// off the edge for `releaseSeconds` logs it.
///
/// A visit measures ONE kind of max, fixed at creation: the peak, or a timed window
/// (`windowSeconds`), so every attempt in it is comparable with every other — a 10 s
/// average beside a peak would make "best" meaningless.
///
/// Pure — no clock, device or store — so the rules are testable.
struct MaxAttemptLog: Sendable {
    struct Attempt: Identifiable, Equatable, Sendable {
        let id: Int
        var side: Side
        /// What the pull records: its peak, or its window's average on a timed visit.
        let kg: Double
    }

    /// Off the edge this long ends a pull. Shorter than a single test's two seconds:
    /// here ending early costs nothing — a re-grip that splits one effort in two still
    /// logs its highest reading, and the next pull simply starts another attempt.
    static let releaseSeconds: TimeInterval = 1

    private(set) var attempts: [Attempt] = []
    /// The hand the next pull is logged against.
    private(set) var side: Side
    private var current: MaxAttempt?
    private var nextID = 1
    /// 0 is a PEAK visit; otherwise every pull is averaged over this many seconds.
    let windowSeconds: Int
    /// How long the last timed pull held before it was let go SHORT of its window — it
    /// logged nothing, and the screen says so rather than going quiet. Cleared by the
    /// next pull.
    private(set) var lastShortSeconds: TimeInterval?

    init(side: Side, windowSeconds: Int = 0) {
        self.side = side
        self.windowSeconds = Swift.max(0, windowSeconds)
    }

    /// A pull is under way: over the threshold, or under it for less than `releaseSeconds`.
    var isPulling: Bool { current != nil }
    /// The pull in progress's live figure: its highest reading so far, or on a timed visit
    /// its average so far.
    var pullKg: Double? {
        guard let current else { return nil }
        return windowSeconds > 0 ? current.averageKg : current.peakKg
    }
    /// Seconds still to hold on a timed pull in progress.
    var pullRemainingSeconds: TimeInterval? { current?.remainingSeconds }

    /// Feed one sample. Returns the attempt this sample completed, if any.
    @discardableResult
    mutating func add(_ kg: Double, at t: TimeInterval) -> Attempt? {
        guard kg.isFinite, t.isFinite else { return nil }
        if current == nil {
            // Drift below the threshold is not a pull, so it opens nothing.
            guard kg >= MaxAttempt.releaseKg else { return nil }
            current = MaxAttempt(endsAfter: Self.releaseSeconds,
                                 window: windowSeconds > 0 ? TimeInterval(windowSeconds) : nil)
            lastShortSeconds = nil
        }
        current?.add(kg, at: t)
        return current?.isComplete == true ? close() : nil
    }

    /// Log the pull in progress now — before a save, a hand switch or a lost link.
    @discardableResult
    mutating func close() -> Attempt? {
        guard let attempt = current else { return nil }
        current = nil
        guard let kg = attempt.resultKg, kg.isFinite else {
            if windowSeconds > 0, attempt.heldSeconds > 0 { lastShortSeconds = attempt.heldSeconds }
            return nil
        }
        let logged = Attempt(id: nextID, side: side, kg: kg)
        nextID += 1
        attempts.append(logged)
        return logged
    }

    /// Switching hands mid-pull logs that pull against the hand it BEGAN on — the hand
    /// that actually pulled it.
    @discardableResult
    mutating func select(_ newSide: Side) -> Attempt? {
        guard newSide != side else { return nil }
        let closed = close()
        side = newSide
        return closed
    }

    func attempts(for side: Side) -> [Attempt] {
        attempts.filter { $0.side == side }
    }

    /// The strongest pull on a hand. A tie keeps the EARLIER one, so a repeat of the same
    /// number never moves the choice.
    func best(for side: Side) -> Attempt? {
        attempts(for: side).reduce(nil) { best, next in
            guard let best else { return next }
            return next.kg > best.kg ? next : best
        }
    }

    /// Pulled with the wrong hand selected — the commonest slip in a two-hand visit.
    mutating func move(_ id: Int, to side: Side) {
        guard let index = attempts.firstIndex(where: { $0.id == id }) else { return }
        attempts[index].side = side
    }

    mutating func remove(_ id: Int) {
        attempts.removeAll { $0.id == id }
    }
}
