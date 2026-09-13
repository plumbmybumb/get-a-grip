// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

/** Unsaved hand values. Existing shared fallbacks never become invented hand records. */
class MaxEditDraft(leftKg: Double? = null, rightKg: Double? = null) {
    data class Change(val side: Side, val kg: Double)

    private var originals: Map<Side, Double> = buildMap {
        leftKg?.takeIf { it.isFinite() && it > 0 }?.let { put(Side.left, it) }
        rightKg?.takeIf { it.isFinite() && it > 0 }?.let { put(Side.right, it) }
    }
    var repeatedTests: Set<Side> = emptySet()
        private set
    var leftKg: Double = originals[Side.left] ?: 0.0
    var rightKg: Double = originals[Side.right] ?: 0.0

    fun originalKg(side: Side): Double? = originals[side]
    fun recordsAnotherTest(side: Side): Boolean = side in repeatedTests

    fun setRecordsAnotherTest(enabled: Boolean, side: Side) {
        if (side == Side.both) return
        if (enabled && originals[side] != null) repeatedTests = repeatedTests + side
        else if (!enabled) repeatedTests = repeatedTests - side
    }

    /** Follow history changes for untouched fields, preserving edited or explicitly retested values. */
    fun rebase(leftKg: Double?, rightKg: Double?) {
        val leftWasEdited = this.leftKg != (originals[Side.left] ?: 0.0) || Side.left in repeatedTests
        val rightWasEdited = this.rightKg != (originals[Side.right] ?: 0.0) || Side.right in repeatedTests
        val current = MaxEditDraft(leftKg, rightKg)
        originals = current.originals
        if (!leftWasEdited) this.leftKg = current.leftKg
        if (!rightWasEdited) this.rightKg = current.rightKg
    }

    private fun value(side: Side): Double = if (side == Side.left) leftKg else rightKg

    val hasInvalidChanges: Boolean get() = listOf(Side.left, Side.right).any { side ->
        val kg = value(side)
        (kg != (originals[side] ?: 0.0) || side in repeatedTests) && (!kg.isFinite() || kg <= 0)
    }

    val changes: List<Change> get() = listOf(Side.left, Side.right).mapNotNull { side ->
        val kg = value(side)
        if (kg.isFinite() && kg > 0 && (kg != (originals[side] ?: 0.0) || side in repeatedTests))
            Change(side, kg) else null
    }

    val canSave: Boolean get() = !hasInvalidChanges && changes.isNotEmpty()

    /** Value-style use with Compose mutableStateOf; the engine stays platform independent. */
    fun copy(): MaxEditDraft = MaxEditDraft(originals[Side.left], originals[Side.right]).also {
        it.leftKg = leftKg
        it.rightKg = rightKg
        it.repeatedTests = repeatedTests.toSet()
    }
}
