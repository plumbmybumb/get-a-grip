// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

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
///
/// TRANSLATION NOTE (from Shared/Engine/MaxAttemptLog.swift): Swift's value-type struct is a
/// mutable class here; its one owner (`MaxMeasurementDraft`) never shares it.
class MaxAttemptLog(side: Side) {
    data class Attempt(val id: Int, val side: Side, val peakKg: Double)

    /// Every logged pull, oldest first.
    var attempts: List<Attempt> = emptyList()
        private set

    /// The hand the next pull is logged against.
    var side: Side = side
        private set

    private var current: MaxAttempt? = null
    private var nextID = 1

    /// A pull is under way: over the threshold, or under it for less than `releaseSeconds`.
    val isPulling: Boolean get() = current != null

    /// The pull in progress's highest reading so far.
    val pullPeakKg: Double? get() = current?.peakKg

    /// Feed one sample. Returns the attempt this sample completed, if any.
    fun add(kg: Double, at: Double): Attempt? {
        if (!kg.isFinite() || !at.isFinite()) return null
        if (current == null) {
            // Drift below the threshold is not a pull, so it opens nothing.
            if (kg < MaxAttempt.releaseKg) return null
            current = MaxAttempt(endsAfter = releaseSeconds)
        }
        current?.add(kg, at)
        return if (current?.isComplete == true) close() else null
    }

    /// Log the pull in progress now — before a save, a hand switch or a lost link.
    fun close(): Attempt? {
        val attempt = current ?: return null
        current = null
        if (!attempt.hasResult || !attempt.peakKg.isFinite()) return null
        val logged = Attempt(nextID, side, attempt.peakKg)
        nextID += 1
        attempts = attempts + logged
        return logged
    }

    /// Switching hands mid-pull logs that pull against the hand it BEGAN on — the hand
    /// that actually pulled it.
    fun select(newSide: Side): Attempt? {
        if (newSide == side) return null
        val closed = close()
        side = newSide
        return closed
    }

    fun attempts(side: Side): List<Attempt> = attempts.filter { it.side == side }

    /// The hardest pull on a hand. A tie keeps the EARLIER one, so a repeat of the same
    /// number never moves the choice.
    fun best(side: Side): Attempt? =
        attempts(side).fold(null as Attempt?) { best, next ->
            if (best == null || next.peakKg > best.peakKg) next else best
        }

    /// Pulled with the wrong hand selected — the commonest slip in a two-hand visit.
    fun move(id: Int, to: Side) {
        attempts = attempts.map { if (it.id == id) it.copy(side = to) else it }
    }

    fun remove(id: Int) {
        attempts = attempts.filterNot { it.id == id }
    }

    companion object {
        /// Off the edge this long ends a pull. Shorter than a single test's two seconds:
        /// here ending early costs nothing — a re-grip that splits one effort in two still
        /// logs its highest reading, and the next pull simply starts another attempt.
        const val releaseSeconds: Double = 1.0
    }
}
