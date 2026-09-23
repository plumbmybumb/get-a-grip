// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import run.nuri.getagrip.ui.units.WeightUnits

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.BandTrimmer
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.components.ChipGrid
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// The target load for ONE set, as a single row on that set's editor.
///
/// A percentage only means kilograms once you know WHICH grip, so the row states the answer —
/// "20–30 % · 6.0–9.0 kg" — for the grip it sits on.
///
/// Stored as a PERCENTAGE by default: your max moves, and a routine prescribing last spring's
/// load is the bookkeeping this app refuses. Kilograms resolve fresh each session.
///
/// **Kilograms are typeable too** (Nuri, 2026-08-09): a percentage of an unmeasured max
/// resolves to no band at run time, found out mid-session. The unit is a choice inside
/// Custom, defaulting to kilograms exactly when a percentage could not work.
///
/// **SHUT BY DEFAULT — and its warning is not.** The editor measured ~293 pt, a third of an
/// expanded set row, on a screen where most sets carry no target.
@Composable
fun TargetBandRow(
    set: SetPlan,
    /// Every max on file, by grip and hand, so the row can show each hand's ACTUAL kilograms.
    maxes: MaxTable,
    /// Whether hands alternate. `bothHands` shares the edge, so there is no per-hand question and
    /// the row must not invent one.
    handMode: HandMode,
    modifier: Modifier = Modifier,
    onChange: (SetPlan) -> Unit,
) {
    val palette = LocalGripPalette.current

    /// CLOSED on every appearance — see the header note.
    var expanded by remember { mutableStateOf(false) }

    /// Sticky once opened: a custom band landing on 20–30 % would otherwise close the fields
    /// under the finger editing them.
    var showsCustomFields by remember { mutableStateOf(false) }

    /// How a custom band is expressed. Not persisted: the STORED band says which (`targetLoKg`
    /// means kilograms); this only holds the choice while both fields are empty.
    var unit by remember { mutableStateOf(if (set.targetBand != null) Unit_.Kilograms else Unit_.Percent) }

    // Otherwise reopening a kilogram band would show percentage fields over it.
    LaunchedEffect(set.id) {
        unit = if (set.targetBand != null) Unit_.Kilograms else Unit_.Percent
    }

    val percentBand = set.targetPercentBand
    /// An explicit kilogram band OUTRANKS any percentage (`PlanMath.targetBand` order). Setting
    /// either clears the other, so the row never shows one and runs the other.
    val kgBand = set.targetBand
    val hasTarget = kgBand != null || percentBand != null

    /// The hands this routine asks about, in the order the runner alternates them.
    val sides = if (handMode.sideCount > 1) listOf(Side.left, Side.right) else listOf(Side.both)

    /// The kilograms this set asks of one hand, resolved locally because this row holds no
    /// `SessionPlan` for `PlanMath.targetBand`.
    fun resolved(side: Side): ClosedFloatingPointRange<Double>? {
        // An explicit band is already the answer, the same for both hands.
        if (kgBand != null) return kgBand
        val band = percentBand ?: return null
        val maxKg = maxes.max(set.grip.key, side) ?: return null
        if (maxKg <= 0) return null
        return PlanMath.roundedToHalfKg(maxKg * band.start)..
            PlanMath.roundedToHalfKg(maxKg * band.endInclusive)
    }

    val differsByHand = sides.size > 1 && resolved(Side.left) != resolved(Side.right)
    /// A percentage is set, and at least one hand has no max to resolve it against.
    val unresolved = percentBand != null && sides.any { resolved(it) == null }
    val hasResolvableMax = sides.any { (maxes.max(set.grip.key, it) ?: 0.0) > 0 }

    fun applyPercent(lo: Double, hi: Double) {
        // `PlanMath.targetBand` ranks kg first, so a percentage beside kilograms would silently
        // lose; picking a percentage clears them.
        onChange(
            set.copy(
                targetLoPercent = lo, targetHiPercent = hi,
                targetLoKg = null, targetHiKg = null,
            ),
        )
    }

    fun applyKg(lo: Double, hi: Double) {
        onChange(
            set.copy(
                targetLoKg = lo, targetHiKg = hi,
                targetLoPercent = null, targetHiPercent = null,
            ),
        )
    }

    fun clear() {
        onChange(
            set.copy(
                targetLoKg = null, targetHiKg = null,
                targetLoPercent = null, targetHiPercent = null,
            ),
        )
        showsCustomFields = false
    }

    fun matches(lo: Double, hi: Double): Boolean {
        // A kilogram band is never a percentage preset: 20 kg is not "20 %".
        if (kgBand != null) return false
        val band = percentBand ?: return false
        return abs(band.start - lo) < 0.001 && abs(band.endInclusive - hi) < 0.001
    }

    /// Custom mode: you asked for it, or no preset expresses the stored band (synced, or typed
    /// earlier). The second half stops a 17–22 % band opening as "None".
    val editingCustom = showsCustomFields || kgBand != null ||
        (percentBand != null && PRESETS.none { matches(it.lo, it.hi) })

    val valueText = when {
        kgBand != null -> WeightUnits.band(kgBand)
        percentBand == null -> tr("None")
        // **When the hands differ there is no single number to lead with**: show the percentage
        // (what you set, true of both hands); the caption carries the two loads.
        differsByHand -> percentText(percentBand)
        else -> resolved(sides[0])?.let { WeightUnits.band(it) } ?: percentText(percentBand)
    }

    val caption: String? = when {
        kgBand != null ->
            // An explicit load is the one kind that goes stale — the price of not needing a max.
            tr("A fixed load, the same on both hands — it stays put when your max moves.")
        percentBand == null -> null
        else -> {
            val resolvedSides = sides.filter { resolved(it) != null }
            when {
                // Named, not hinted: a percentage with no max resolves to no target at run time.
                // ANDROID-ONLY WORDING: iOS says "Settings › Maxes"; Android's Maxes is its own tab.
                resolvedSides.isEmpty() ->
                    tr("No max on file for this grip yet, so this shows no target during a session. Add one in Maxes.")
                // One hand has a max, the other not (a left- or right-only max). Say WHICH, because the row
                // above shows a confident band for the other.
                resolvedSides.size < sides.size -> {
                    val missing = sides.first { resolved(it) == null }
                    tr(
                        "No max for your %s hand, so those pulls show no target. Add one in Maxes.",
                        missing.displayName.lowercase(),
                    )
                }
                !differsByHand -> tr("%s of your max on this grip", percentText(percentBand))
                else -> {
                    val loads = sides.mapNotNull { side ->
                        resolved(side)?.let {
                            L10n.tr("%s %s", side.prompt.take(1), WeightUnits.band(it, withUnit = false))
                        }
                    }
                    WeightUnits.tr(
                        "%s of each hand's max · %s kg",
                        percentText(percentBand),
                        loads.joinToString(" · "),
                    )
                }
            }
        }
    }

    Column(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The row, always. Closed it is 44 dp and STATES THE LOAD; open it is the editor.
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = L10n.tr("Target load")
                    stateDescription = valueText
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) { expanded = !expanded }
                .pressFeedback(interactionSource, scales = false),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("Target load"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // Amber when a hand will go untargeted: the collapsed row's only way to say so.
                color = when {
                    unresolved -> palette.armed
                    hasTarget -> palette.inkPrimary
                    else -> palette.inkTertiary
                },
                maxLines = 1,
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }

        // **Collapsing a control must never collapse the reason it is broken**: an unresolvable
        // percentage stays explained with the editor shut.
        if (!expanded && unresolved && caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.armed,
            )
        }

        // A disclosure uses `Motion.state` (flat under Reduce Motion), not Compose's unguarded
        // 400 ms default.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.state(rememberReduceMotion())) +
                fadeIn(Motion.state(rememberReduceMotion())),
            exit = shrinkVertically(Motion.state(rememberReduceMotion())) +
                fadeOut(Motion.state(rememberReduceMotion())),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipGrid(
                    base = 3,
                    content = buildList {
                        add { cell: Modifier ->
                            Chip(tr("None"), !hasTarget && !editingCustom, cell) { clear() }
                        }
                        PRESETS.forEach { preset ->
                            add { cell: Modifier ->
                                // Selected only while the custom fields are CLOSED: tapping Custom otherwise lit
                                // "20–30 %", the band it seeds from, beside two open fields.
                                Chip(
                                    tr("%s %%", preset.label),
                                    matches(preset.lo, preset.hi) && !editingCustom,
                                    cell,
                                ) {
                                    applyPercent(preset.lo, preset.hi)
                                    showsCustomFields = false
                                }
                            }
                        }
                        add { cell: Modifier ->
                            // For bands no chip holds — 17–22 % was Nuri's own example.
                            Chip(tr("Custom"), editingCustom, cell) {
                                showsCustomFields = true
                                if (hasTarget) {
                                    unit = if (kgBand != null) Unit_.Kilograms else Unit_.Percent
                                } else {
                                    // **Kilograms when a percentage could not work**: "20 % of your max" with no max
                                    // resolves to nothing at run time.
                                    unit = if (hasResolvableMax) Unit_.Percent else Unit_.Kilograms
                                    if (unit == Unit_.Kilograms) {
                                        applyKg(DEFAULT_KG_LO, DEFAULT_KG_HI)
                                    } else {
                                        applyPercent(0.20, 0.30)
                                    }
                                }
                            }
                        }
                    },
                )

                if (editingCustom) {
                    // WHICH UNIT, asked only here: it is how you express the load, not a preset value,
                    // and only matters once the presets do not fit.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Unit_.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = unit == option,
                                onClick = {
                                    if (unit == option) return@SegmentedButton
                                    unit = option
                                    // Seed from what is on screen, so switching units reads as a conversion, not a reset.
                                    when (option) {
                                        Unit_.Kilograms -> {
                                            val seed = resolved(sides[0])
                                            applyKg(
                                                seed?.start ?: DEFAULT_KG_LO,
                                                seed?.endInclusive ?: DEFAULT_KG_HI,
                                            )
                                        }
                                        Unit_.Percent -> applyPercent(
                                            percentBand?.start ?: 0.20,
                                            percentBand?.endInclusive ?: 0.30,
                                        )
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, Unit_.entries.size),
                            ) { Text(option.label) }
                        }
                    }

                    // ONE two-ended trimmer, replacing steppers that made "80 to 90" a dozen taps. Steps snap
                    // to the 5 % / 0.5 kg resolution targets round to.
                    if (unit == Unit_.Kilograms) {
                        val lo = kgBand?.start ?: DEFAULT_KG_LO
                        val hi = kgBand?.endInclusive ?: DEFAULT_KG_HI
                        BandTrimmer(
                            lo = WeightUnits.fromKg(lo),
                            hi = WeightUnits.fromKg(hi),
                            scale = WeightUnits.sliderRange(0.0..kgScaleTop(kgBand?.endInclusive, set, maxes, sides)),
                            step = 0.5,
                            format = { "${WeightUnits.formatDisplayed(it)} ${WeightUnits.symbol}" },
                            spokenUnit = WeightUnits.spoken,
                        ) { newLo, newHi -> applyKg(WeightUnits.toKg(newLo), WeightUnits.toKg(newHi)) }
                    } else {
                        BandTrimmer(
                            lo = percentBand?.start ?: 0.20,
                            hi = percentBand?.endInclusive ?: 0.30,
                            scale = SetPlan.percentRange,
                            step = 0.05,
                            format = { percentUnit(it) },
                            spokenUnit = tr("percent of max"),
                        ) { newLo, newHi -> applyPercent(newLo, newHi) }
                    }
                    val isPercent = unit == Unit_.Percent
                    val currentLo = if (isPercent) percentBand?.start ?: 0.20 else kgBand?.start ?: DEFAULT_KG_LO
                    val currentHi = if (isPercent) percentBand?.endInclusive ?: 0.30 else kgBand?.endInclusive ?: DEFAULT_KG_HI
                    val factor = if (isPercent) 100.0 else WeightUnits.fromKg(1.0)
                    val bounds = if (isPercent) 1.0..100.0 else WeightUnits.fromKg(0.0..kgScaleTop(kgBand?.endInclusive, set, maxes, sides))
                    for (lower in listOf(true, false)) {
                        run.nuri.getagrip.ui.components.ValueRow(
                            title = if (lower) tr("Lower bound") else tr("Upper bound"),
                            value = (if (lower) currentLo else currentHi) * factor,
                            range = bounds,
                            unit = if (isPercent) "%" else WeightUnits.symbol,
                            decimals = if (isPercent) 0 else 1,
                            control = run.nuri.getagrip.ui.components.ValueControl.None,
                        ) { typed ->
                            val value = if (isPercent) typed / 100.0 else WeightUnits.toKg(typed)
                            val lo = if (lower) value else minOf(value, currentLo)
                            val hi = if (lower) maxOf(value, currentHi) else value
                            if (isPercent) applyPercent(lo, hi) else applyKg(lo, hi)
                        }
                    }
                }

                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        // Amber only when a hand goes untargeted; a per-hand breakdown is not a warning.
                        color = if (unresolved) palette.armed else palette.inkTertiary,
                    )
                }
            }
        }
    }
}

/// How a custom band is expressed. Trailing underscore because `Unit` is Kotlin's void type.
/// The label is a `get()` — see `Tab`.
private enum class Unit_(private val key: String) {
    Percent("% of max"),
    Kilograms("Kilograms");

    val label: String get() = if (this == Kilograms) L10n.tr(if (WeightUnits.current == run.nuri.getagrip.ui.units.WeightUnit.kg) "Kilograms" else "Pounds") else L10n.tr(key)
}

private data class Preset(val label: String, val lo: Double, val hi: Double)

/// The one-tap bands. Low-intensity volume, the app's centre of gravity, gets two of four;
/// the others reach strength-endurance and max work.
private val PRESETS = listOf(
    Preset("15–25", 0.15, 0.25),
    Preset("20–30", 0.20, 0.30),
    Preset("40–60", 0.40, 0.60),
    Preset("80–100", 0.80, 1.00),
)

/// A kilogram band's start with nothing to seed it: the low-intensity no-hang load the app
/// is built around.
private const val DEFAULT_KG_LO = 10.0
private const val DEFAULT_KG_HI = 15.0

/// The kilogram scale's top: grows with the band and the strongest max on file, so a
/// 10–15 kg band is not a sliver.
private fun kgScaleTop(
    bandTop: Double?,
    set: SetPlan,
    maxes: MaxTable,
    sides: List<Side>,
): Double {
    val top = bandTop ?: DEFAULT_KG_HI
    val maxTop = sides.mapNotNull { maxes.max(set.grip.key, it) }.maxOrNull() ?: 0.0
    return ceil(maxOf(40.0, top * 1.3, maxTop * 1.2) / 5.0) * 5.0
}

private fun percentText(band: ClosedFloatingPointRange<Double>): String =
    L10n.tr("%d–%d %%", (band.start * 100).roundToInt(), (band.endInclusive * 100).roundToInt())

/// Named, because a lambda passed to a composable is not composable, and `L10n.tr` works
/// from either.
private fun kgUnit(value: Double): String = WeightUnits.tr("%s kg", kgText(value))

private fun percentUnit(fraction: Double): String =
    L10n.tr("%d %%", (fraction * 100).roundToInt())

/// One kilogram figure, to one decimal. LOCALE-SENSITIVE like every number a person reads;
/// the locale-free `Fmt.fixed` is for keys and exports.
internal fun kgText(kg: Double): String = WeightUnits.number(kg)

@Preview(name = "TargetBandRow", showBackground = true, widthDp = 360)
@Composable
private fun TargetBandRowPreview() {
    GetAGripTheme {
        val maxes = remember {
            MaxTable().apply {
                record(30.0, GripSpec(20, FingerSet.four, GripPosition.halfCrimp).key, Side.left)
                record(27.0, GripSpec(20, FingerSet.four, GripPosition.halfCrimp).key, Side.right)
            }
        }
        var set by remember {
            mutableStateOf(
                SetPlan(
                    grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp),
                    targetLoPercent = 0.20,
                    targetHiPercent = 0.30,
                ),
            )
        }
        Column(Modifier.padding(16.dp)) {
            TargetBandRow(set, maxes, HandMode.alternateEachRep) { set = it }
        }
    }
}
