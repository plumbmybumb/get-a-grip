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
/// Pure — no clock, device or store — so the rules are testable.
struct MaxAttemptLog: Sendable {
    struct Attempt: Identifiable, Equatable, Sendable {
        let id: Int
        var side: Side
        let peakKg: Double
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

    init(side: Side) {
        self.side = side
    }

    /// A pull is under way: over the threshold, or under it for less than `releaseSeconds`.
    var isPulling: Bool { current != nil }
    /// The pull in progress's highest reading so far.
    var pullPeakKg: Double? { current?.peakKg }

    /// Feed one sample. Returns the attempt this sample completed, if any.
    @discardableResult
    mutating func add(_ kg: Double, at t: TimeInterval) -> Attempt? {
        guard kg.isFinite, t.isFinite else { return nil }
        if current == nil {
            // Drift below the threshold is not a pull, so it opens nothing.
            guard kg >= MaxAttempt.releaseKg else { return nil }
            current = MaxAttempt(endsAfter: Self.releaseSeconds)
        }
        current?.add(kg, at: t)
        return current?.isComplete == true ? close() : nil
    }

    /// Log the pull in progress now — before a save, a hand switch or a lost link.
    @discardableResult
    mutating func close() -> Attempt? {
        guard let attempt = current else { return nil }
        current = nil
        guard attempt.hasResult, attempt.peakKg.isFinite else { return nil }
        let logged = Attempt(id: nextID, side: side, peakKg: attempt.peakKg)
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

    /// The hardest pull on a hand. A tie keeps the EARLIER one, so a repeat of the same
    /// number never moves the choice.
    func best(for side: Side) -> Attempt? {
        attempts(for: side).reduce(nil) { best, next in
            guard let best else { return next }
            return next.peakKg > best.peakKg ? next : best
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
