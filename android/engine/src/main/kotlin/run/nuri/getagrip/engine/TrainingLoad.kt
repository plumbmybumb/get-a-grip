// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

/// The small value that crosses from the persistence model into the pure load engine.
/// Keeping the model out of the engine means the later widget can use these calculations
/// without importing the app's persistence layer.
data class LoggedLoad(
    val day: DayStamp,
    val minutes: Int?,
    val rpe: RPE?,
    val finger: FingerStrain?,
)

/// Staged, tested calculations for a future trend surface. Current screens store and
/// display the two effort inputs but do not present these derived load estimates.
object TrainingLoad {

    /// Borg CR-10, which is the scale session-RPE is defined on.
    fun cr10(rpe: RPE): Double = (rpe.rawValue * 2).toDouble()

    /// "Nothing" genuinely is zero finger load. "Light" does not mean zero session
    /// load — you were still there for the session — so the local axis has its own
    /// asymmetric mapping rather than borrowing the systemic one.
    fun cr10(strain: FingerStrain): Double = (strain.rawValue - 1) * 2.5

    /// Foster's session-RPE: intensity × minutes, in arbitrary units.
    fun sessionLoad(cr10: Double, minutes: Int): Double = cr10 * minutes

    data class DayLoad(
        val day: DayStamp,
        val systemic: Double,
        val finger: Double,
    )

    /// TRANSLATION NOTE: Swift returns the labelled tuple `(systemic:, finger:)`, which
    /// Kotlin has no equivalent of — a named pair type keeps the call sites reading the
    /// same (`rolling(…).systemic`) instead of degrading to `.first`.
    data class RollingTotals(
        val systemic: Double,
        val finger: Double,
    )

    /// Daily totals, ascending, with gap days present at zero so a window sum is a
    /// plain slice rather than a lookup per day.
    fun daily(entries: List<LoggedLoad>, from: DayStamp, to: DayStamp): List<DayLoad> {
        if (from > to) return emptyList()

        val totals = mutableMapOf<DayStamp, Pair<Double, Double>>()
        for (entry in entries) {
            if (entry.day < from || entry.day > to) continue
            val minutes = entry.minutes ?: continue
            if (minutes <= 0) continue
            val systemic = entry.rpe?.let { sessionLoad(cr10(it), minutes) } ?: 0.0
            val finger = entry.finger?.let { sessionLoad(cr10(it), minutes) } ?: 0.0
            val old = totals[entry.day] ?: (0.0 to 0.0)
            totals[entry.day] = (old.first + systemic) to (old.second + finger)
        }

        return DayStamp.span(from, to).map { day ->
            val total = totals[day] ?: (0.0 to 0.0)
            DayLoad(day = day, systemic = total.first, finger = total.second)
        }
    }

    /// RAW rolling sums. No ratio, no verdict, no "sweet spot" — the schema stores
    /// inputs and a later read can change the model without a CloudKit migration.
    fun rolling(series: List<DayLoad>, days: Int, endingAt: DayStamp): RollingTotals {
        if (days <= 0) return RollingTotals(0.0, 0.0)
        val firstDay = endingAt - (days - 1)
        var systemic = 0.0
        var finger = 0.0
        for (day in series) {
            if (day.day < firstDay || day.day > endingAt) continue
            systemic += day.systemic
            finger += day.finger
        }
        return RollingTotals(systemic = systemic, finger = finger)
    }
}
