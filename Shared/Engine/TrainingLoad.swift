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
