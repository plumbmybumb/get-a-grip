// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.components.ChipGrid
import run.nuri.getagrip.ui.components.HouseSegmentedRow
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// The builder's three pages, in the order creating walks them.
///
/// ONE builder for creating and editing, as ever: creating walks Back / Next with page dots,
/// editing jumps with a switcher, and everything else is shared.
enum class BuilderPage {
    Rhythm, Sets, Schedule;

    val title: String
        get() = when (this) {
            Rhythm -> L10n.tr("Rhythm")
            Sets -> L10n.tr("Sets")
            Schedule -> L10n.tr("Schedule")
        }

    val next: BuilderPage? get() = entries.getOrNull(ordinal + 1)
    val previous: BuilderPage? get() = entries.getOrNull(ordinal - 1)
}

/// Where you are in the three pages while creating. Not a control: Back and Next are.
@Composable
internal fun BuilderPageDots(current: BuilderPage, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val spoken = tr("%s, page %d of 3", current.title, current.ordinal + 1)
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BuilderPage.entries.forEach { page ->
            val active = page == current
            val width by animateDpAsState(if (active) 18.dp else 6.dp, Motion.state(reduceMotion), label = "dot")
            Box(
                Modifier
                    .width(width)
                    .height(6.dp)
                    .background(
                        if (active) palette.graphite else palette.inkTertiary.copy(alpha = 0.45f),
                        CircleShape,
                    ),
            )
        }
    }
}

/// Editing's page switcher: Rhythm · Sets · Schedule, pinned under the top bar.
@Composable
internal fun BuilderPageSwitcher(current: BuilderPage, modifier: Modifier = Modifier, onSelect: (BuilderPage) -> Unit) {
    HouseSegmentedRow(
        labels = BuilderPage.entries.map { it.title },
        selectedIndex = current.ordinal,
        modifier = modifier,
    ) { onSelect(BuilderPage.entries[it]) }
}

/// The load every set follows unless it says otherwise — a percentage of each grip's own max,
/// so one band lands on the right kilograms for every grip at once. Kilograms stay per set: a
/// kilogram figure only means something for one grip.
///
/// Shut by default, like `TargetBandRow`: the header states the load.
@Composable
internal fun RoutineTargetRow(
    /// The routine's band, as its two edges (null = none) — values, so the row skips every
    /// edit that is not its own.
    lo: Double?,
    hi: Double?,
    /// Some set carries a target of its own, so "None" here is not the whole story.
    setsVary: Boolean,
    modifier: Modifier = Modifier,
    /// Writes the routine band and clears every set's own target: one band for all.
    onChange: (ClosedFloatingPointRange<Double>?) -> Unit,
) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val band = if (lo != null && hi != null) minOf(lo, hi)..maxOf(lo, hi) else null
    var expanded by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(band != null && PRESETS.none { it.second == band }) }
    val stacked = LocalDensity.current.fontScale >= 1.5f

    val valueText = when {
        band != null -> "${percent(band.start)}–${percent(band.endInclusive)} %"
        setsVary -> tr("Per set")
        else -> tr("None")
    }
    val interaction = remember { MutableInteractionSource() }
    val label = tr("Target load")
    val disclosure = tr(if (expanded) "Expanded" else "Collapsed")

    Column(modifier.fillMaxWidth().animateContentSize(Motion.state(reduceMotion))) {
        val header: @Composable () -> Unit = {
            Text(
                valueText,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = if (band == null) palette.inkTertiary else palette.inkPrimary,
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.padding(start = 6.dp).size(20.dp),
            )
        }
        val headerModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
            .pressFeedback(interaction, scales = false)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, $valueText"
                stateDescription = disclosure
                role = Role.Button
            }
        if (stacked) {
            Column(headerModifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = palette.inkPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) { header() }
            }
        } else {
            Row(headerModifier, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkPrimary,
                    modifier = Modifier.weight(1f),
                )
                header()
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.state(reduceMotion)) + fadeIn(Motion.state(reduceMotion)),
            exit = shrinkVertically(Motion.state(reduceMotion)) + fadeOut(Motion.state(reduceMotion)),
        ) {
            Column(Modifier.padding(top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val cells = buildList<@Composable (Modifier) -> Unit> {
                    add { m ->
                        Chip(tr("None"), band == null && !custom && !setsVary, m) {
                            custom = false
                            onChange(null)
                        }
                    }
                    PRESETS.forEach { (title, range) ->
                        add { m ->
                            Chip("$title %", band == range && !custom, m) {
                                custom = false
                                onChange(range)
                            }
                        }
                    }
                    add { m ->
                        Chip(tr("Custom"), custom, m) {
                            custom = true
                            if (band == null) onChange(0.20..0.30)
                        }
                    }
                }
                ChipGrid(base = 3, content = cells)
                if (custom) {
                    val current = band ?: 0.20..0.30
                    IntValueRow(
                        title = tr("From"),
                        value = percent(current.start),
                        range = 1..100,
                        unit = "%",
                        control = ValueControl.Stepper,
                    ) { new -> onChange(ordered(new / 100.0, current.endInclusive)) }
                    IntValueRow(
                        title = tr("To"),
                        value = percent(current.endInclusive),
                        range = 1..100,
                        unit = "%",
                        control = ValueControl.Stepper,
                    ) { new -> onChange(ordered(current.start, new / 100.0)) }
                }
            }
        }
    }
}

private fun percent(fraction: Double): Int = (fraction * 100).roundToInt()

private fun ordered(a: Double, b: Double): ClosedFloatingPointRange<Double> {
    val lo = a.coerceIn(0.01, 1.0)
    val hi = b.coerceIn(0.01, 1.0)
    return minOf(lo, hi)..maxOf(lo, hi)
}

/// The routine bands worth one tap. More is a menu again.
private val PRESETS: List<Pair<String, ClosedFloatingPointRange<Double>>> = listOf(
    "15–25" to 0.15..0.25,
    "20–30" to 0.20..0.30,
    "40–60" to 0.40..0.60,
    "80–100" to 0.80..1.00,
)
