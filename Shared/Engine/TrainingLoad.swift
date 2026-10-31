// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The small value that crosses from the SwiftData model into the pure load engine.
/// Keeping the model out of `Shared/` means the later widget can use these calculations
/// without importing the app's persistence layer.
struct LoggedLoad: Hashable, Sendable {
    let day: DayStamp
    let minutes: Int?
    let rpe: RPE?
    let finger: FingerStrain?
}

/// Staged, tested calculations for a future trend surface. Current screens store and
/// display the two effort inputs but do not present these derived load estimates — except
/// `topLoadKg`, which History's "Load per grip" card draws beside the average.
enum TrainingLoad {
    /// Borg CR-10, which is the scale session-RPE is defined on.
    static func cr10(_ rpe: RPE) -> Double { Double(rpe.rawValue * 2) }

    /// "Nothing" genuinely is zero finger load. "Light" does not mean zero session
    /// load — you were still there for the session — so the local axis has its own
    /// asymmetric mapping rather than borrowing the systemic one.
    static func cr10(_ strain: FingerStrain) -> Double {
        Double(strain.rawValue - 1) * 2.5
    }

    /// Foster's session-RPE: intensity × minutes, in arbitrary units.
    static func sessionLoad(cr10: Double, minutes: Int) -> Double {
        cr10 * Double(minutes)
    }

    struct DayLoad: Hashable, Sendable {
        let day: DayStamp
        let systemic: Double
        let finger: Double
    }

    /// Daily totals, ascending, with gap days present at zero so a window sum is a
    /// plain slice rather than a lookup per day.
    static func daily(_ entries: [LoggedLoad], from: DayStamp, to: DayStamp) -> [DayLoad] {
        guard from <= to else { return [] }

        var totals: [DayStamp: (systemic: Double, finger: Double)] = [:]
        for entry in entries where entry.day >= from && entry.day <= to {
            guard let minutes = entry.minutes, minutes > 0 else { continue }
            let systemic = entry.rpe.map { sessionLoad(cr10: cr10($0), minutes: minutes) } ?? 0
            let finger = entry.finger.map { sessionLoad(cr10: cr10($0), minutes: minutes) } ?? 0
            let old = totals[entry.day] ?? (0, 0)
            totals[entry.day] = (old.systemic + systemic, old.finger + finger)
        }

        return (from...to).map { day in
            let total = totals[day] ?? (0, 0)
            return DayLoad(day: day, systemic: total.systemic, finger: total.finger)
        }
    }

    /// The share of held time `topLoadKg` reads by default: the hardest 5 %.
    static let topLoadFraction = 0.05

    /// **What you held for your hardest `fraction` of time**, in kilograms — History's
    /// "Hardest 5 % of time" chart, beside the "Average load" one (2026-10-31). The session
    /// mean is dragged down by warm-up pulls at 60–70 % before the hard sets, so a session
    /// that ended heavier can read as a weaker one; this line ignores them unasked.
    ///
    /// Reps that count: COMPLETED, measured (`avgKg > 0` — a gauge-free rep logs 0 kg
    /// because nothing was measured, not because nothing was pulled) and with held time.
    /// Sorted heaviest first, they are walked until `fraction` of the total held seconds is
    /// covered; the rep that crosses the boundary contributes only the slice of its seconds
    /// still needed. The answer is the time-weighted mean load over that slice.
    ///
    /// Nil when nothing qualifies — no held time is a blank, never a zero. Derived on read
    /// and stored nowhere: changing the fraction is a code change, not a migration.
    /// `Fixtures/trend/top-load.json` pins it on both engines.
    static func topLoadKg(of reps: [RepSummary], fraction: Double = topLoadFraction) -> Double? {
        guard fraction > 0, fraction.isFinite else { return nil }
        let held = reps
            .filter { $0.outcome == .completed && $0.avgKg > 0 && $0.avgKg.isFinite
                && $0.heldSeconds > 0 && $0.heldSeconds.isFinite }
            // Stable on ties: equal loads give the same mean in any order, but the walk
            // stays reproducible.
            .enumerated()
            .sorted { $0.element.avgKg == $1.element.avgKg
                ? $0.offset < $1.offset : $0.element.avgKg > $1.element.avgKg }
            .map(\.element)
        let total = held.reduce(0) { $0 + $1.heldSeconds }
        guard total > 0 else { return nil }

        let budget = total * Swift.min(fraction, 1)
        var covered = 0.0
        var weighted = 0.0
        for rep in held {
            let slice = Swift.min(rep.heldSeconds, budget - covered)
            guard slice > 0 else { break }
            covered += slice
            weighted += rep.avgKg * slice
        }
        return covered > 0 ? weighted / covered : nil
    }

    /// RAW rolling sums. No ratio, no verdict, no "sweet spot" — the schema stores
    /// inputs and a later read can change the model without a CloudKit migration.
    static func rolling(_ series: [DayLoad], days: Int,
                        endingAt: DayStamp) -> (systemic: Double, finger: Double) {
        guard days > 0 else { return (0, 0) }
        let firstDay = endingAt - (days - 1)
        return series
            .filter { $0.day >= firstDay && $0.day <= endingAt }
            .reduce(into: (systemic: 0.0, finger: 0.0)) { result, day in
                result.systemic += day.systemic
                result.finger += day.finger
            }
    }
}
