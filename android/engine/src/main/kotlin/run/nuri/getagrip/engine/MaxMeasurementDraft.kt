// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

data class MaxMeasurementResult(
    val side: Side,
    val kg: Double,
    val source: MaxSource = MaxSource.measured,
)

/// One visit's unsaved state: the pulls, which one each hand keeps, and any correction
/// typed over it. Nothing here changes a working benchmark until the caller saves.
///
/// **Each hand keeps its hardest pull unless you pick another** in the review. A pick is
/// by attempt, so it survives later pulls; a correction belongs to the pull it corrects,
/// so a new pull on that hand, a pick, a move or a delete clears it.
///
/// TRANSLATION NOTE (from Sources/UI/Maxes/MaxMeasurementDraft.swift): pure, so it lives in
/// `:engine` beside `MaxAttemptLog` rather than in the UI. Swift's mutating struct is a
/// mutable class with one owner (`LiveMaxSession`).
class MaxMeasurementDraft(val bothTogether: Boolean = false, side: Side = Side.left) {
    val log = MaxAttemptLog(if (bothTogether) Side.both else side)
    private val picked = mutableMapOf<Side, Int>()
    private val corrections = mutableMapOf<Side, Double>()

    /// The hands this visit can save — never a combined value from two separate hands.
    val sides: List<Side> get() = if (bothTogether) listOf(Side.both) else listOf(Side.left, Side.right)
    val hasAttempts: Boolean get() = log.attempts.isNotEmpty()

    /// The pull a hand saves: the one picked, while it is still on that hand; else its best.
    fun kept(side: Side): MaxAttemptLog.Attempt? {
        picked[side]?.let { id -> log.attempts(side).firstOrNull { it.id == id }?.let { return it } }
        return log.best(side)
    }

    fun peak(side: Side): Double? = corrections[side] ?: kept(side)?.peakKg
    fun measuredPeak(side: Side): Double? = kept(side)?.peakKg
    fun isCorrected(side: Side): Boolean = side in corrections

    val results: List<MaxMeasurementResult>
        get() = sides.mapNotNull { side ->
            kept(side)?.let {
                val correction = corrections[side]
                MaxMeasurementResult(side, correction ?: it.peakKg,
                    if (correction == null) MaxSource.measured else MaxSource.manual)
            }
        }

    // MARK: - Pulling

    fun add(kg: Double, at: Double): MaxAttemptLog.Attempt? =
        log.add(kg, at)?.also { corrections.remove(it.side) }

    fun close(): MaxAttemptLog.Attempt? =
        log.close()?.also { corrections.remove(it.side) }

    fun select(side: Side): MaxAttemptLog.Attempt? {
        if (bothTogether || side == Side.both) return null
        return log.select(side)?.also { corrections.remove(it.side) }
    }

    // MARK: - Review

    fun pick(id: Int) {
        val attempt = log.attempts.firstOrNull { it.id == id } ?: return
        picked[attempt.side] = id
        corrections.remove(attempt.side)
    }

    fun move(id: Int, to: Side) {
        if (bothTogether || to == Side.both) return
        val from = log.attempts.firstOrNull { it.id == id }?.side ?: return
        if (from == to) return
        log.move(id, to)
        for (hand in listOf(from, to)) {
            picked.remove(hand)
            corrections.remove(hand)
        }
    }

    fun remove(id: Int) {
        val side = log.attempts.firstOrNull { it.id == id }?.side ?: return
        log.remove(id)
        if (picked[side] == id) picked.remove(side)
        corrections.remove(side)
    }

    /// Returning to the exact measured peak restores measured provenance; a correction
    /// never fabricates a hand that has no pull.
    fun correct(values: List<MaxMeasurementResult>): Boolean {
        if (log.isPulling || values.isEmpty() || values.map { it.side }.toSet().size != values.size ||
            !values.all { kept(it.side) != null && it.kg.isFinite() && it.kg > 0 }) return false
        values.forEach { value ->
            if (value.kg == kept(value.side)?.peakKg) corrections.remove(value.side)
            else corrections[value.side] = value.kg
        }
        return true
    }
}
