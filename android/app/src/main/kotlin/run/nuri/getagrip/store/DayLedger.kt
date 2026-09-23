// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.SessionKind

/// The month grid's day-fill rule.
///
/// TRANSLATION NOTE (`HistoryView.DayLedger`, Sources/UI/HistoryView.swift): a nested type
/// on iOS, but PURE, so here it lives in `store/` beside the other folds.
///
/// Folded once instead of filtering every log per cell: O(logs), not O(days × logs) — with
/// the rule itself unchanged, since silently changing what a filled square means would be
/// worse than the slowness.
class DayLedger(logs: List<WorkoutLogEntity>, today: DayStamp, trackingSince: DayStamp? = null) {

    /// What happened on one day, in the four terms the grid asks about.
    private data class Day(
        var hangs: Int = 0,
        /// The target frozen INTO the logs, not today's setting: a day trained under a
        /// once-a-day routine was full.
        var target: Int = 1,
        var settled: Boolean = false,
        var climbed: Boolean = false,
        var benchmarked: Boolean = false,
    )

    private val days = HashMap<Int, Day>()

    /// The first day with any evidence. Earlier days are not missed days: drawing them as
    /// empty boxes would lie about a habit never observed.
    val trackingSince: DayStamp

    init {
        var earliest: Int? = null
        for (log in logs) {
            earliest = minOf(earliest ?: log.dayKey, log.dayKey)
            val day = days.getOrPut(log.dayKey) { Day() }
            day.target = maxOf(day.target, log.sessionsPerDayTarget)
            if (log.kind.countsAsHang) day.hangs += 1
            if (log.kind.settlesDay) day.settled = true
            // The same predicate `Collection.climb(on:)` filters on, minus its
            // hardest-first PRECEDENCE: cell and legend only need yes or no.
            if (log.kind.isClimb) day.climbed = true
            if (log.kind == SessionKind.benchmark) day.benchmarked = true
        }
        this.trackingSince = trackingSince ?: earliest?.let { DayStamp(it) } ?: today
    }

    fun fraction(on: DayStamp): Double {
        val d = days[on.raw] ?: return 0.0
        // A climb or a benchmark FILLS the day; a half bar would contradict Today's
        // sentence. The notch stays climb-only.
        if (d.settled) return 1.0
        // `target` starts at 1 and only grows, and `WorkoutLogEntity.from` clamps the
        // column with `max(1, …)`: no divide-by-zero, and a target-0 log fills its day
        // exactly once.
        return minOf(1.0, d.hangs.toDouble() / d.target.toDouble())
    }

    fun climbed(on: DayStamp): Boolean = days[on.raw]?.climbed ?: false

    fun benchmarked(on: DayStamp): Boolean = days[on.raw]?.benchmarked ?: false
}
