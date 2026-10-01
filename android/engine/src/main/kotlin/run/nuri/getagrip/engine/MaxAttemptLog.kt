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
/// A visit measures ONE kind of max, fixed at creation: the peak, or a timed window
/// (`windowSeconds`), so every attempt in it is comparable with every other.
///
/// Pure — no clock, device or store — so the rules are testable.
///
/// TRANSLATION NOTE (from Shared/Engine/MaxAttemptLog.swift): Swift's value-type struct is a
/// mutable class here; its one owner (`MaxMeasurementDraft`) never shares it.
class MaxAttemptLog(side: Side, windowSeconds: Int = 0) {
    /// `kg` is what the pull records: its peak, or its window's average on a timed visit.
    data class Attempt(val id: Int, val side: Side, val kg: Double)

    /// 0 is a PEAK visit; otherwise every pull is averaged over this many seconds.
    val windowSeconds: Int = maxOf(0, windowSeconds)

    /// How long the last timed pull held before it was let go SHORT of its window — it
    /// logged nothing, and the screen says so rather than going quiet. Cleared by the next pull.
    var lastShortSeconds: Double? = null
        private set

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

    /// The pull in progress's live figure: its highest reading so far, or on a timed visit
    /// its average so far.
    val pullKg: Double?
        get() {
            val current = current ?: return null
            return if (windowSeconds > 0) current.averageKg else current.peakKg
        }

    /// Seconds still to hold on a timed pull in progress.
    val pullRemainingSeconds: Double? get() = current?.remainingSeconds

    /// Feed one sample. Returns the attempt this sample completed, if any.
    fun add(kg: Double, at: Double): Attempt? {
        if (!kg.isFinite() || !at.isFinite()) return null
        if (current == null) {
            // Drift below the threshold is not a pull, so it opens nothing.
            if (kg < MaxAttempt.releaseKg) return null
            current = MaxAttempt(endsAfter = releaseSeconds,
                                 window = if (windowSeconds > 0) windowSeconds.toDouble() else null)
            lastShortSeconds = null
        }
        current?.add(kg, at)
        return if (current?.isComplete == true) close() else null
    }

    /// Log the pull in progress now — before a save, a hand switch or a lost link.
    fun close(): Attempt? {
        val attempt = current ?: return null
        current = null
        val kg = attempt.resultKg
        if (kg == null || !kg.isFinite()) {
            if (windowSeconds > 0 && attempt.heldSeconds > 0) lastShortSeconds = attempt.heldSeconds
            return null
        }
        val logged = Attempt(nextID, side, kg)
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

    /// The strongest pull on a hand. A tie keeps the EARLIER one, so a repeat of the same
    /// number never moves the choice.
    fun best(side: Side): Attempt? =
        attempts(side).fold(null as Attempt?) { best, next ->
            if (best == null || next.kg > best.kg) next else best
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
