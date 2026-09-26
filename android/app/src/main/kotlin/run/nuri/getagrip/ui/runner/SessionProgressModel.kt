// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.runner

import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSlot
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.RunnerPhase

// Where the session's position lives on the runner (iOS `RunnerProgressBars.swift`): ONE
// time bar under the hero, then the whole routine as quiet pills, then the SET / PULL labels.
// This file is the pure half — the model, the pill layout and what the time bar measures —
// so it is testable without Compose.

/// Where the session is, folded from the runner's resolved slots and results. Built when the
/// coarse snapshot changes: `results` only changes when `snapshot.completedRepCount` does.
data class SessionProgressModel(
    /// Pull counts per set, in order.
    val setSizes: List<Int>,
    /// One per FINISHED pull: true = completed, false = skipped or aborted. A skipped pull is
    /// used up but did not happen, so it draws lighter than a completed one.
    val finished: List<Boolean>,
    /// The pull being pulled, or the next one while resting; null once finished.
    val current: Int?,
) {
    val total: Int get() = setSizes.sum()
    val done: Int get() = finished.size
    val pullsLeft: Int get() = total - done

    companion object {
        fun of(slots: List<RepSlot>, results: List<RepSummary>): SessionProgressModel {
            val sizes = mutableListOf<Int>()
            var lastSet: Int? = null
            for (slot in slots) {
                if (slot.setIndex == lastSet) sizes[sizes.size - 1] += 1 else sizes += 1
                lastSet = slot.setIndex
            }
            val next = results.size
            return SessionProgressModel(
                setSizes = sizes,
                finished = results.map { it.outcome == RepOutcome.completed },
                current = if (next < slots.size) next else null,
            )
        }
    }
}

/// Pull slots along a width: ~2 between pulls, ~6 between sets.
///
/// **A pill must stay at least 1.5× as long as it is tall**: at 60 pulls a ~4 pt slot drew a
/// row of DOTS, and a circle is a session in this app. So the pull gap narrows under an
/// 8-unit slot and goes under 6, leaving set gaps only (which narrow before a set becomes a
/// sliver). Units are whatever `width` is in — the caller passes pixels with density-scaled
/// gaps.
object RoutinePillLayout {
    fun cells(
        sizes: List<Int>,
        width: Float,
        pullGap: Float = 2f,
        setGap: Float = 6f,
        /// One design point in the caller's units (density), for the density thresholds.
        unit: Float = 1f,
    ): List<ClosedFloatingPointRange<Float>> {
        val total = sizes.sum()
        if (total <= 0 || width <= 0f) return emptyList()
        val sets = sizes.size
        var set = if (sets > 1) setGap * unit else 0f
        var pull = pullGap * unit
        fun slot() = (width - set * (sets - 1) - pull * (total - sets)) / total
        if (slot() < 8 * unit && pull > 1 * unit) pull = 1 * unit
        if (slot() < 6 * unit) pull = 0f
        while (slot() < 1 * unit && set > 1 * unit) set -= 1 * unit
        val length = maxOf(0.25f * unit, slot())
        val cells = ArrayList<ClosedFloatingPointRange<Float>>(total)
        var cursor = 0f
        for (size in sizes) {
            for (index in 0 until size) {
                cells += cursor..(cursor + length)
                cursor += length + if (index < size - 1) pull else 0f
            }
            cursor += set
        }
        return cells
    }
}

/// What the runner's one time bar measures in this phase.
enum class TimeBarMode { hold, armed, released, countdown, none }

fun timeBarMode(phase: RunnerPhase): TimeBarMode = when ((phase as? RunnerPhase.Paused)?.before ?: phase) {
    is RunnerPhase.Working -> TimeBarMode.hold
    is RunnerPhase.Releasing -> TimeBarMode.released
    is RunnerPhase.Armed -> TimeBarMode.armed
    is RunnerPhase.Resting, is RunnerPhase.LeadIn -> TimeBarMode.countdown
    else -> TimeBarMode.none
}

/// Working or releasing (and paused inside those): the pull is under your hand.
fun isHoldLive(phase: RunnerPhase): Boolean = when ((phase as? RunnerPhase.Paused)?.before ?: phase) {
    is RunnerPhase.Working, is RunnerPhase.Releasing -> true
    else -> false
}

/// The fraction the time bar draws: the hold growing while pulling, FULL once the hold is
/// recorded under your hand, and the countdown DRAINING while resting.
fun timeBarFraction(mode: TimeBarMode, repProgress: Float, remaining: Double?): Float = when (mode) {
    TimeBarMode.hold -> repProgress
    TimeBarMode.released -> 1f
    TimeBarMode.countdown -> (remaining ?: 0.0).toFloat()
    TimeBarMode.armed, TimeBarMode.none -> 0f
}.coerceIn(0f, 1f)

/// The counters' spoken line gains what the pills show and the words do not: how much is
/// left. Verbatim, as iOS's `routineLeftSpoken`.
fun routineLeftSpoken(planned: Int, completed: Int): String {
    val left = planned - completed
    return if (left == 1) ", 1 pull left" else ", ${maxOf(0, left)} pulls left"
}
