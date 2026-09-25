// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

struct MaxMeasurementResult: Equatable, Sendable {
    let side: Side
    let kg: Double
    var source: MaxSource = .measured
}

/// One visit's unsaved state: the pulls, which one each hand keeps, and any correction
/// typed over it. Nothing here changes a working benchmark until the caller saves.
///
/// **Each hand keeps its hardest pull unless you pick another** in the review. A pick is
/// by attempt, so it survives later pulls; a correction belongs to the pull it corrects,
/// so a new pull on that hand, a pick, a move or a delete clears it.
struct MaxMeasurementDraft {
    let bothTogether: Bool
    private(set) var log: MaxAttemptLog
    private var picked: [Side: Int] = [:]
    private var corrections: [Side: Double] = [:]

    init(bothTogether: Bool = false, side: Side = .left) {
        self.bothTogether = bothTogether
        log = MaxAttemptLog(side: bothTogether ? .both : side)
    }

    /// The hands this visit can save — never a combined value from two separate hands.
    var sides: [Side] { bothTogether ? [.both] : [.left, .right] }
    var hasAttempts: Bool { !log.attempts.isEmpty }

    /// The pull a hand saves: the one picked, while it is still on that hand; else its best.
    func kept(for side: Side) -> MaxAttemptLog.Attempt? {
        if let id = picked[side], let attempt = log.attempts(for: side).first(where: { $0.id == id }) {
            return attempt
        }
        return log.best(for: side)
    }

    func peak(for side: Side) -> Double? { corrections[side] ?? kept(for: side)?.peakKg }
    func measuredPeak(for side: Side) -> Double? { kept(for: side)?.peakKg }
    func isCorrected(_ side: Side) -> Bool { corrections[side] != nil }

    var results: [MaxMeasurementResult] {
        sides.compactMap { side in
            kept(for: side).map {
                MaxMeasurementResult(side: side, kg: corrections[side] ?? $0.peakKg,
                                     source: corrections[side] == nil ? .measured : .manual)
            }
        }
    }

    // MARK: - Pulling

    @discardableResult
    mutating func add(_ kg: Double, at t: TimeInterval) -> MaxAttemptLog.Attempt? {
        let logged = log.add(kg, at: t)
        if let logged { corrections[logged.side] = nil }
        return logged
    }

    @discardableResult
    mutating func close() -> MaxAttemptLog.Attempt? {
        let logged = log.close()
        if let logged { corrections[logged.side] = nil }
        return logged
    }

    @discardableResult
    mutating func select(_ side: Side) -> MaxAttemptLog.Attempt? {
        guard !bothTogether, side != .both else { return nil }
        let logged = log.select(side)
        if let logged { corrections[logged.side] = nil }
        return logged
    }

    // MARK: - Review

    mutating func pick(_ id: Int) {
        guard let attempt = log.attempts.first(where: { $0.id == id }) else { return }
        picked[attempt.side] = id
        corrections[attempt.side] = nil
    }

    mutating func move(_ id: Int, to side: Side) {
        guard !bothTogether, side != .both,
              let from = log.attempts.first(where: { $0.id == id })?.side, from != side else { return }
        log.move(id, to: side)
        for hand in [from, side] {
            picked[hand] = nil
            corrections[hand] = nil
        }
    }

    mutating func remove(_ id: Int) {
        guard let side = log.attempts.first(where: { $0.id == id })?.side else { return }
        log.remove(id)
        if picked[side] == id { picked[side] = nil }
        corrections[side] = nil
    }

    /// Returning to the exact measured peak restores measured provenance; a correction
    /// never fabricates a hand that has no pull.
    @discardableResult
    mutating func correct(_ values: [MaxMeasurementResult]) -> Bool {
        guard !log.isPulling, !values.isEmpty,
              Set(values.map(\.side)).count == values.count,
              values.allSatisfy({ kept(for: $0.side) != nil && $0.kg.isFinite && $0.kg > 0 }) else { return false }
        for value in values {
            corrections[value.side] = value.kg == kept(for: value.side)?.peakKg ? nil : value.kg
        }
        return true
    }
}

/// The draft behind the live screen, observed at two speeds.
///
/// Samples arrive ~80×/s and every one mutates the draft, so the draft itself is
/// `@ObservationIgnored`. Views read it through `snapshot`, which tracks `revision` — bumped
/// only when a pull is logged or the review changes something. The one per-sample value,
/// the pull's climbing peak, is published on its own and read only by the hero leaf.
@Observable
@MainActor
final class LiveMaxSession {
    @ObservationIgnored private var draft: MaxMeasurementDraft
    private var revision = 0
    private(set) var isPulling = false
    private(set) var pullPeakKg: Double?
    /// The most recent pull on the selected hand, for the hero between pulls.
    private(set) var lastAttempt: MaxAttemptLog.Attempt?
    /// Bumped when a pull beats its hand's previous best — the success haptic's trigger.
    private(set) var newBestTick = 0

    init(bothTogether: Bool, side: Side) {
        draft = MaxMeasurementDraft(bothTogether: bothTogether, side: side)
    }

    var snapshot: MaxMeasurementDraft {
        _ = revision
        return draft
    }

    var side: Side { snapshot.log.side }

    func receive(_ point: DeviceStore.TracePoint) {
        let previousBest = draft.log.best(for: draft.log.side)?.peakKg
        let logged = draft.add(point.kg, at: point.t)
        publishPull()
        if let logged { didLog(logged, previousBest: previousBest) }
    }

    func close() {
        let previousBest = draft.log.best(for: draft.log.side)?.peakKg
        let logged = draft.close()
        publishPull()
        if let logged { didLog(logged, previousBest: previousBest) }
    }

    func select(_ side: Side) {
        draft.select(side)
        lastAttempt = nil
        publishPull()
        revision += 1
    }

    func pick(_ id: Int) { draft.pick(id); revision += 1 }
    func move(_ id: Int, to side: Side) { draft.move(id, to: side); forgetLastIfGone(); revision += 1 }
    func remove(_ id: Int) { draft.remove(id); forgetLastIfGone(); revision += 1 }

    @discardableResult
    func correct(_ values: [MaxMeasurementResult]) -> Bool {
        let applied = draft.correct(values)
        if applied { revision += 1 }
        return applied
    }

    private func didLog(_ attempt: MaxAttemptLog.Attempt, previousBest: Double?) {
        if attempt.side == draft.log.side { lastAttempt = attempt }
        if attempt.peakKg > (previousBest ?? 0) { newBestTick += 1 }
        revision += 1
    }

    private func forgetLastIfGone() {
        guard let last = lastAttempt else { return }
        if !draft.log.attempts(for: draft.log.side).contains(where: { $0.id == last.id }) {
            lastAttempt = nil
        }
    }

    /// Equality-guarded: Observation fires on every SET, not every change.
    private func publishPull() {
        if isPulling != draft.log.isPulling { isPulling = draft.log.isPulling }
        let peak = draft.log.pullPeakKg
        if pullPeakKg != peak { pullPeakKg = peak }
    }
}
