// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

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
/// A percentage only means kilograms once you know WHICH grip it applies to, so a
/// routine-wide card had to talk in abstractions and then hope you checked each grip's max
/// separately. Here the row says the actual answer — "20–30 % · 6.0–9.0 kg" — because it
/// knows the grip it is sitting on.
///
/// Stored as a PERCENTAGE by default, not kilograms: your max moves, and a routine that
/// silently keeps prescribing last spring's load is the bookkeeping this app exists to
/// refuse. The kilograms are resolved fresh at the start of every session.
///
/// **But kilograms are typeable too** (Nuri, 2026-08-09: "we also need to be able to set
/// weight ranges even if you don't have your max recorded"). A percentage of a max you have
/// not measured is a target of NOTHING — it resolves to no band at run time, and finding
/// that out mid-session is the wrong moment. So the unit is a choice inside Custom, and it
/// defaults to kilograms exactly when the percentage could not work.
///
/// **SHUT BY DEFAULT — and its warning is not.** The editor measured ~293 pt, a third of
/// the whole expanded set row, on a screen where most sets carry no target at all. Six
/// chips, a unit picker, a trimmer and a two-line caption is the right EDITOR and the wrong
/// thing to look at while scrolling past five sets you are not editing.
@Composable
fun TargetBandRow(
    set: SetPlan,
    /// Every max on file, by grip and hand — so a percentage can be shown as the ACTUAL
    /// kilograms each hand will be asked for, which is the whole point of the row.
    maxes: MaxTable,
    /// Whether this routine alternates hands. `bothHands` puts them on the edge together,
    /// so there is no per-hand question to answer and the row must not invent one.
    handMode: HandMode,
    modifier: Modifier = Modifier,
    onChange: (SetPlan) -> Unit,
) {
    val palette = LocalGripPalette.current

    /// CLOSED on every appearance, deliberately — see the header note.
    var expanded by remember { mutableStateOf(false) }

    /// Sticky once opened: a custom band that happens to LAND on 20–30 % would otherwise
    /// close the fields under the finger that was still editing it.
    var showsCustomFields by remember { mutableStateOf(false) }

    /// How a custom band is expressed. Not persisted — the STORED band says which it is
    /// (a `targetLoKg` means kilograms), and this only carries the choice while the fields
    /// are open and both are momentarily empty.
    var unit by remember { mutableStateOf(if (set.targetBand != null) Unit_.Kilograms else Unit_.Percent) }

    // The STORED band says which unit it is. Without this, reopening a set that already
    // holds a kilogram band would show the percentage fields over it.
    LaunchedEffect(set.id) {
        unit = if (set.targetBand != null) Unit_.Kilograms else Unit_.Percent
    }

    val percentBand = set.targetPercentBand
    /// An explicit kilogram band, which OUTRANKS any percentage — the same order
    /// `PlanMath.targetBand` resolves in. The two are mutually exclusive here: setting
    /// either clears the other, so the row can never show one and run the other.
    val kgBand = set.targetBand
    val hasTarget = kgBand != null || percentBand != null

    /// The hands this routine asks about, in the order the runner alternates them.
    val sides = if (handMode.sideCount > 1) listOf(Side.left, Side.right) else listOf(Side.both)

    /// The kilograms this set will ask of one hand. Resolved locally from the set's own
    /// percentage rather than through `PlanMath.targetBand`, because this row deliberately
    /// does not hold a whole `SessionPlan`.
    fun resolved(side: Side): ClosedFloatingPointRange<Double>? {
        // An explicit band needs no resolving and no max — it is already the answer, and it
        // is the same answer for both hands.
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
        // A percentage and an explicit kilogram band on the same set would leave the kg
        // winning silently — `PlanMath.targetBand` ranks it first — so picking a percentage
        // clears any kilograms that may have been typed here.
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
        // A kilogram band is never a percentage preset, however the numbers happen to line
        // up — 20 kg is not "20 %".
        if (kgBand != null) return false
        val band = percentBand ?: return false
        return abs(band.start - lo) < 0.001 && abs(band.endInclusive - hi) < 0.001
    }

    /// Whether the row is in custom mode: either you asked for it, or the stored band is one
    /// no preset can express (a routine synced from another device, or a value typed here
    /// earlier). The second half is what stops a 17–22 % band opening as "None".
    val editingCustom = showsCustomFields || kgBand != null ||
        (percentBand != null && PRESETS.none { matches(it.lo, it.hi) })

    val valueText = when {
        kgBand != null -> PlanMath.bandText(kgBand)
        percentBand == null -> tr("None")
        // **When the hands differ there is no single number to lead with**, so it shows the
        // percentage — the thing you actually set, and the one figure that IS true of both
        // hands — and the caption underneath carries the two loads.
        differsByHand -> percentText(percentBand)
        else -> resolved(sides[0])?.let { PlanMath.bandText(it) } ?: percentText(percentBand)
    }

    val caption: String? = when {
        kgBand != null ->
            // What it does NOT do is the part worth stating: an explicit load is the one
            // kind that goes stale, and it is the trade you make for not needing a max.
            tr("A fixed load, the same on both hands — it stays put when your max moves.")
        percentBand == null -> null
        else -> {
            val resolvedSides = sides.filter { resolved(it) != null }
            when {
                // Named, not hinted: a percentage with no max resolves to no target at all
                // at run time, and finding that out mid-session is the wrong moment.
                // ANDROID-ONLY WORDING: iOS says "Settings › Maxes", where its max list
                // lives. Android's Maxes is a tab of its own.
                resolvedSides.isEmpty() ->
                    tr("No max on file for this grip yet, so this shows no target during a session. Add one in Maxes.")
                // One hand has a max and the other does not — which is what an explicitly
                // left-only or right-only max leaves behind. Say WHICH hand is unloaded,
                // because the row above will happily show a confident band for the other.
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
                            L10n.tr("%s %s", side.prompt.take(1), PlanMath.bandText(it, withUnit = false))
                        }
                    }
                    tr(
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
                // Amber when a hand will genuinely go untargeted — the collapsed row's only
                // way to say that the number beside it will not survive to the session.
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

        // **Collapsing a control must never collapse the reason it is broken.** A percentage
        // that resolves to NOTHING stays explained with the editor shut — the same class of
        // bug as a per-set override that only appears once you open the row.
        if (!expanded && unresolved && caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.armed,
            )
        }

        // A disclosure is the ladder's DEFAULT motion — critically damped, and flat under
        // Reduce Motion. Compose's own default here is an unguarded 400 ms tween nothing in
        // this app chose.
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
                                // A preset only reads as selected while the custom fields
                                // are CLOSED. Otherwise tapping Custom lit "20–30 %" — the
                                // band it seeds from — and the row said it was on a preset
                                // while offering you two fields.
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
                            // A band nobody thought to make a chip — 17–22 % was Nuri's own
                            // example, and four presets could never have held it.
                            Chip(tr("Custom"), editingCustom, cell) {
                                showsCustomFields = true
                                if (hasTarget) {
                                    unit = if (kgBand != null) Unit_.Kilograms else Unit_.Percent
                                } else {
                                    // **Kilograms when a percentage could not work.**
                                    // Offering "20 % of your max" to someone who has never
                                    // measured this grip is offering a number that resolves
                                    // to nothing at run time.
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
                    // WHICH UNIT, asked only here. It is a question about how you want to
                    // express the load, not another preset value, so it does not belong in
                    // the chip row above — and it only comes up once you have said the
                    // presets do not fit.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Unit_.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = unit == option,
                                onClick = {
                                    if (unit == option) return@SegmentedButton
                                    unit = option
                                    // Seed from what is on screen where that is possible, so
                                    // switching units reads as a conversion rather than a
                                    // reset.
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

                    // ONE two-ended control, the trimmer — it replaced a pair of steppers
                    // that made "80 to 90" a dozen taps. The steps snap to the same 5 % /
                    // 0.5 kg resolution the app rounds targets to, so every value it can
                    // land on is one people quote to each other exactly.
                    if (unit == Unit_.Kilograms) {
                        val lo = kgBand?.start ?: DEFAULT_KG_LO
                        val hi = kgBand?.endInclusive ?: DEFAULT_KG_HI
                        BandTrimmer(
                            lo = lo,
                            hi = hi,
                            scale = 0.0..kgScaleTop(kgBand?.endInclusive, set, maxes, sides),
                            step = 0.5,
                            format = { kgUnit(it) },
                            spokenUnit = tr("kilograms"),
                        ) { newLo, newHi -> applyKg(newLo, newHi) }
                    } else {
                        BandTrimmer(
                            lo = percentBand?.start ?: 0.20,
                            hi = percentBand?.endInclusive ?: 0.30,
                            scale = 0.05..1.0,
                            step = 0.05,
                            format = { percentUnit(it) },
                            spokenUnit = tr("percent of max"),
                        ) { newLo, newHi -> applyPercent(newLo, newHi) }
                    }
                }

                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        // Amber only when a hand will genuinely go untargeted — a per-hand
                        // breakdown is information, not a warning.
                        color = if (unresolved) palette.armed else palette.inkTertiary,
                    )
                }
            }
        }
    }
}

/// How a custom band is expressed. Named with a trailing underscore because `Unit` is
/// Kotlin's own void type and shadowing it inside a file this size is a trap.
/// The label is a `get()` for the reason `Tab`'s is: an enum entry is constructed once per
/// process, and a translated string baked in there survives a language change.
private enum class Unit_(private val key: String) {
    Percent("% of max"),
    Kilograms("Kilograms");

    val label: String get() = L10n.tr(key)
}

private data class Preset(val label: String, val lo: Double, val hi: Double)

/// The bands worth one tap. Low-intensity volume is the app's centre of gravity, so it gets
/// two of the four; the others reach strength-endurance and max work without pretending a
/// slider would be more precise than a person's intent.
private val PRESETS = listOf(
    Preset("15–25", 0.15, 0.25),
    Preset("20–30", 0.20, 0.30),
    Preset("40–60", 0.40, 0.60),
    Preset("80–100", 0.80, 1.00),
)

/// Where a kilogram band starts when there is nothing to seed it from. The low-intensity
/// no-hang load this whole app is built around, in the units someone with no max on file
/// can still reason about.
private const val DEFAULT_KG_LO = 10.0
private const val DEFAULT_KG_HI = 15.0

/// The kilogram scale's top. Wide enough for strong pullers without making a 10–15 kg band
/// a sliver: it grows with the band it has to show and with the strongest max on file.
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

/// The trimmer's own formatters. Named rather than inline because a lambda passed to a
/// composable is not itself composable, and `L10n.tr` is the door that works from either.
private fun kgUnit(value: Double): String = L10n.tr("%s kg", kgText(value))

private fun percentUnit(fraction: Double): String =
    L10n.tr("%d %%", (fraction * 100).roundToInt())

/// One kilogram figure, to one decimal. LOCALE-SENSITIVE, like `PlanMath.bandText` and
/// every other number a person reads — the locale-free `Fmt.fixed` belongs to keys and
/// exports.
internal fun kgText(kg: Double): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(kg)

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
