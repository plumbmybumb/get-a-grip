// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

data class MaxMeasurementResult(
    val side: Side,
    val kg: Double,
    val source: MaxSource = MaxSource.measured,
)

/** A hand is fixed before the first sample; corrections remain unsaved draft values. */
class MaxMeasurementDraft(val bothTogether: Boolean = false) {
    var activeSide: Side? = null
        private set
    private val peaks = mutableMapOf<Side, Double>()
    private val corrections = mutableMapOf<Side, Double>()

    val results: List<MaxMeasurementResult> get() {
        if (activeSide != null) return emptyList()
        return listOf(Side.left, Side.right, Side.both).mapNotNull { side ->
            peaks[side]?.let { measured ->
                MaxMeasurementResult(side, corrections[side] ?: measured,
                    if (side in corrections) MaxSource.manual else MaxSource.measured)
            }
        }
    }

    fun peak(side: Side): Double? = corrections[side] ?: peaks[side]
    fun measuredPeak(side: Side): Double? = peaks[side]

    fun correct(values: List<MaxMeasurementResult>): Boolean {
        if (activeSide != null || values.isEmpty() || values.map { it.side }.toSet().size != values.size ||
            values.any { it.side !in peaks || !it.kg.isFinite() || it.kg <= 0 }) return false
        values.forEach { value ->
            if (value.kg == peaks[value.side]) corrections.remove(value.side)
            else corrections[value.side] = value.kg
        }
        return true
    }

    fun begin(side: Side): Boolean {
        if (activeSide != null || (side == Side.both) != bothTogether) return false
        activeSide = side
        return true
    }

    fun finish(peakKg: Double): Side? {
        val side = activeSide ?: return null
        activeSide = null
        if (peakKg.isFinite() && peakKg >= MaxAttempt.releaseKg) {
            peaks[side] = peakKg
            corrections.remove(side)
        }
        return side
    }

    fun copy(): MaxMeasurementDraft = MaxMeasurementDraft(bothTogether).also {
        it.activeSide = activeSide
        it.peaks.putAll(peaks)
        it.corrections.putAll(corrections)
    }
}
