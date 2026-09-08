// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.units

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import run.nuri.getagrip.engine.L10n

/** A presentation preference. Engine, BLE, database and machine exports always use kg. */
enum class WeightUnit(val rawValue: String, val symbol: String) {
    kg("kg", "kg"), lb("lb", "lb");

    fun fromKg(kilograms: Double): Double = if (this == lb) kilograms / KG_PER_LB else kilograms
    fun toKg(displayed: Double): Double = if (this == lb) displayed * KG_PER_LB else displayed
    fun fromKg(range: ClosedRange<Double>): ClosedFloatingPointRange<Double> = fromKg(range.start)..fromKg(range.endInclusive)
    fun sliderRange(rangeKg: ClosedRange<Double>, step: Double): ClosedFloatingPointRange<Double> =
        (kotlin.math.ceil(fromKg(rangeKg.start) / step) * step)..
            (kotlin.math.floor(fromKg(rangeKg.endInclusive) / step) * step)
    val spoken: String get() = L10n.tr(if (this == kg) "kilograms" else "pounds")

    /** No conversion is ever written back merely because a value was formatted. */
    fun number(kilograms: Double, decimals: Int = 1): String =
        WeightNumberFormatter.format(fromKg(if (kilograms.isFinite()) kilograms else 0.0), decimals)
    fun text(kilograms: Double): String = "${number(kilograms)} $symbol"
    fun band(range: ClosedRange<Double>, withUnit: Boolean = true): String =
        "${number(range.start)}–${number(range.endInclusive)}" + if (withUnit) " $symbol" else ""

    /**
     * Localized legacy UI templates carry kg words. Replace only the unit tokens, BEFORE
     * interpolation, so a user's routine/grip name containing "kg" is never changed.
     * Numeric conversion remains explicit at every call site; this never guesses values.
     */
    fun localizedTemplate(template: String): String {
        if (this == kg) return template
        return template
            .replace(FRENCH_KG, L10n.tr("pounds"))
            .replace(ENGLISH_KG, L10n.tr("pounds"))
            .replace(KG_SYMBOL) { if (it.value == "KG") "LB" else symbol }
    }

    companion object {
        private val FRENCH_KG = Regex("\\bkilogrammes\\b", RegexOption.IGNORE_CASE)
        private val ENGLISH_KG = Regex("\\bkilograms\\b", RegexOption.IGNORE_CASE)
        private val KG_SYMBOL = Regex("\\bkg\\b", RegexOption.IGNORE_CASE)
        const val KG_PER_LB = 0.45359237
        fun fromRaw(raw: String?): WeightUnit = entries.firstOrNull { it.rawValue == raw } ?: kg
    }
}

/** Observable, cached once from SettingsStore; no I/O or preference parsing on sensor frames. */
object WeightUnits {
    var current: WeightUnit by mutableStateOf(WeightUnit.kg)
        internal set
    val symbol: String get() = current.symbol
    val spoken: String get() = current.spoken
    fun fromKg(value: Double): Double = current.fromKg(value)
    fun toKg(value: Double): Double = current.toKg(value)
    fun fromKg(range: ClosedRange<Double>): ClosedFloatingPointRange<Double> = current.fromKg(range)
    fun sliderRange(rangeKg: ClosedRange<Double>, step: Double = 0.5): ClosedFloatingPointRange<Double> = current.sliderRange(rangeKg, step)
    fun formatDisplayed(value: Double, decimals: Int = 1): String = WeightNumberFormatter.format(value, decimals)
    fun number(kg: Double, decimals: Int = 1): String = current.number(kg, decimals)
    fun text(kg: Double): String = current.text(kg)
    fun band(range: ClosedRange<Double>, withUnit: Boolean = true): String = current.band(range, withUnit)
    fun tr(key: String, vararg args: Any): String {
        val template = current.localizedTemplate(L10n.tr(key))
        return if (args.isEmpty()) template else runCatching {
            String.format(java.util.Locale.getDefault(), template, *args)
        }.getOrDefault(template)
    }
}


/** Reuse formatters on each rendering thread; locale changes invalidate the tiny cache. */
private object WeightNumberFormatter {
    private data class Cached(val locale: java.util.Locale, val decimals: Int, val formatter: java.text.NumberFormat)
    private val cached = ThreadLocal<Cached>()
    fun format(value: Double, decimals: Int): String {
        val locale = java.util.Locale.getDefault()
        val previous = cached.get()
        val formatter = if (previous?.locale == locale && previous.decimals == decimals) previous.formatter else {
            java.text.NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = decimals
                maximumFractionDigits = decimals
                isGroupingUsed = false
            }.also { cached.set(Cached(locale, decimals, it)) }
        }
        return formatter.format(if (value.isFinite()) value else 0.0)
    }
}
