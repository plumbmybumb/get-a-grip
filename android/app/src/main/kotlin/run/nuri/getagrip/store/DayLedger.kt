// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.SessionKind

/// The month grid's day-fill rule.
///
/// TRANSLATION NOTE (`HistoryView.DayLedger` in Sources/UI/HistoryView.swift): it is a
/// nested type on the iOS view, but it is PURE — logs in, four questions out — so on
/// Android it lives in `store/` beside the other folds and History composes it rather
/// than owning it.
///
/// It used to live in `HistoryView.fraction(on:)` as a filter over every log, run once
/// per cell. Folding it in here made the screen O(logs) instead of O(days × logs) — but
/// it also meant rewriting the rule, and a grid that silently changed what a filled
/// square means would be a worse bug than the slowness it fixed.
class DayLedger(logs: List<WorkoutLogEntity>, today: DayStamp, trackingSince: DayStamp? = null) {

    /// What happened on one day, in the four terms the grid asks about.
    private data class Day(
        var hangs: Int = 0,
        /// The target frozen INTO the logs, not today's setting: a day trained under a
        /// once-a-day routine was a full day, even if the routine now asks for two.
        var target: Int = 1,
        var settled: Boolean = false,
        var climbed: Boolean = false,
        var benchmarked: Boolean = false,
    )

    private val days = HashMap<Int, Day>()

    /// The first day there is any evidence of. Days before it are not missed days —
    /// nobody can fail on a day they did not own the app, and drawing them as empty
    /// boxes indistinguishable from a skipped session is the screen quietly lying about
    /// a habit it never observed.
    val trackingSince: DayStamp

    init {
        var earliest: Int? = null
        for (log in logs) {
            earliest = minOf(earliest ?: log.dayKey, log.dayKey)
            val day = days.getOrPut(log.dayKey) { Day() }
            day.target = maxOf(day.target, log.sessionsPerDayTarget)
            if (log.kind.countsAsHang) day.hangs += 1
            if (log.kind.settlesDay) day.settled = true
            // `isClimb` is the same predicate `Collection.climb(on:)` filters on; only
            // its hardest-first PRECEDENCE is dropped, and no caller here asks which
            // climb it was — the cell and the legend both want a yes or no.
            if (log.kind.isClimb) day.climbed = true
            if (log.kind == SessionKind.benchmark) day.benchmarked = true
        }
        this.trackingSince = trackingSince ?: earliest?.let { DayStamp(it) } ?: today
    }

    fun fraction(on: DayStamp): Double {
        val d = days[on.raw] ?: return 0.0
        // A climb — or a benchmark — FILLS the day: both complete it outright, so a
        // half-height bar would contradict the sentence on Today. The notch drawn over
        // the fill stays climb-only.
        if (d.settled) return 1.0
        // `target` starts at 1 and only ever grows, and `WorkoutLogEntity.from` already
        // clamps the column with `max(1, …)`, so there is no divide-by-zero to guard —
        // that is what makes a log written with a target of 0 fill its day exactly once.
        return minOf(1.0, d.hangs.toDouble() / d.target.toDouble())
    }

    fun climbed(on: DayStamp): Boolean = days[on.raw]?.climbed ?: false

    fun benchmarked(on: DayStamp): Boolean = days[on.raw]?.benchmarked ?: false
}
