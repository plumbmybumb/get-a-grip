// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.criticalforce

import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import run.nuri.getagrip.engine.Side
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.engine.CriticalForceResult
import run.nuri.getagrip.engine.CriticalForceRules
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.units.WeightUnits
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/// What a critical force test says, as a value: shared by the result screen and a saved
/// test's detail, so the two can never describe one test differently.
data class CriticalForceSummary(
    val criticalForceKg: Double,
    val wPrimeKgS: Double,
    val peakKg: Double,
    val repMeans: List<Double?>,
    val criticalForceReps: IntRange,
    val restsKept: Int,
    val restsTotal: Int,
    val percentOfMax: Double?,
    val percentOfBodyMass: Double?,
) {
    companion object {
        fun of(result: CriticalForceResult, maxKg: Double?, bodyMassKg: Double?) = CriticalForceSummary(
            criticalForceKg = result.criticalForceKg,
            wPrimeKgS = result.wPrimeKgS,
            peakKg = result.peakKg,
            repMeans = result.reps.map { it.meanKg },
            criticalForceReps = result.criticalForceReps,
            restsKept = result.restsKept,
            restsTotal = result.restsTotal,
            percentOfMax = result.percentOf(maxKg),
            percentOfBodyMass = result.percentOf(bodyMassKg),
        )

        fun of(record: CriticalForceRecordEntity): CriticalForceSummary {
            val last = max(1, record.repsRun)
            return CriticalForceSummary(
                criticalForceKg = record.criticalForceKg,
                wPrimeKgS = record.wPrimeKgS,
                peakKg = record.peakKg,
                repMeans = record.reps.map { it.meanKg },
                criticalForceReps = max(1, last - CriticalForceRules.criticalForceReps + 1)..last,
                restsKept = record.restsKept,
                restsTotal = record.restsTotal,
                percentOfMax = record.percentOfMax,
                percentOfBodyMass = record.percentOfBodyMass,
            )
        }
    }
}

/// "46 % of your max · 26 % of body weight", or null when neither is known.
internal fun criticalForceRatioLine(percentOfMax: Double?, percentOfBodyMass: Double?, ofYour: Boolean): String? {
    val parts = buildList {
        percentOfMax?.let {
            add(if (ofYour) L10n.tr("%d %% of your max", roundedPercent(it)) else L10n.tr("%d %% of max", roundedPercent(it)))
        }
        percentOfBodyMass?.let { add(L10n.tr("%d %% of body weight", roundedPercent(it))) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/// Swift's `Int(x.rounded())`: ties away from zero.
internal fun roundedPercent(value: Double): Int =
    (if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)).toInt()

/// A saved test, stacked: the headline, then the pulls in a well. The history detail; the
/// live result places the same two pieces on the test's own screen instead.
@Composable
fun CriticalForceResultView(summary: CriticalForceSummary, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CriticalForceHeadline(summary)
        CriticalForcePullChart(
            summary,
            Modifier
                .background(palette.inkPrimary.copy(alpha = 0.05f), RoundedCornerShape(Metrics.radiusInner))
                .padding(14.dp),
        )
    }
}

private val HERO_SIZE = 76.sp
private val UNIT_SIZE = 21.sp

/// One number, then what it means. The hero is CF; everything else is in secondary ink,
/// because the other apps' failure was to give every figure the same voice.
@Composable
fun CriticalForceHeadline(summary: CriticalForceSummary, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val ratios = criticalForceRatioLine(summary.percentOfMax, summary.percentOfBodyMass, ofYour = true)
    val spoken = listOfNotNull(
        WeightUnits.tr("Critical force %s kilograms", WeightUnits.number(summary.criticalForceKg)),
        ratios,
    ).joinToString(". ")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CapsLabel(tr("CRITICAL FORCE"), Modifier.clearAndSetSemantics {})
            Row(
                Modifier.clearAndSetSemantics {},
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    WeightUnits.number(summary.criticalForceKg),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = HERO_SIZE, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em,
                    ),
                    fontWeight = FontWeight.Thin,
                    color = palette.inkPrimary,
                    maxLines = 1,
                )
                Text(WeightUnits.symbol, style = TextStyle(fontSize = UNIT_SIZE), color = palette.inkTertiary,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            if (ratios != null) {
                Text(ratios, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.clearAndSetSemantics {})
            }
        }
        CriticalForceStatsRow(summary)
    }
}

/// W′ and the hardest pull, in secondary voice under the headline.
@Composable
fun CriticalForceStatsRow(summary: CriticalForceSummary, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.inkPrimary.copy(alpha = 0.05f), RoundedCornerShape(Metrics.radiusInner))
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stat(tr("RESERVE (W′)"), WeightUnits.number(summary.wPrimeKgS, 0), "${WeightUnits.symbol}·s",
            Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(36.dp).background(palette.inkTertiary.copy(alpha = 0.25f)))
        Stat(tr("HARDEST PULL"), WeightUnits.number(summary.peakKg), WeightUnits.symbol, Modifier.weight(1f))
    }
}

/// One hand's critical force in a two-hand result: the hand, the number, its share of that
/// hand's max. Tapping it shows that hand's pulls. The two columns ARE the switch, so no
/// extra control is needed to choose a hand.
@Composable
fun CriticalForceHandColumn(
    side: Side,
    summary: CriticalForceSummary,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val shape = RoundedCornerShape(Metrics.radiusInner)
    val spoken = WeightUnits.tr("%s, critical force %s kilograms", side.displayName,
        WeightUnits.number(summary.criticalForceKg))
    Column(
        modifier
            .clip(shape)
            .background(palette.inkPrimary.copy(alpha = if (selected) 0.07f else 0f), shape)
            .border(1.5.dp, if (selected) palette.bleu else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onSelect)
            .padding(vertical = 10.dp)
            .testTag("cf.hand.${side.rawValue}")
            .clearAndSetSemantics {
                contentDescription = spoken
                this.selected = selected
                role = Role.Tab
                onClick { onSelect(); true }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(side.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = if (selected) palette.inkPrimary else palette.inkSecondary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                WeightUnits.number(summary.criticalForceKg),
                style = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Thin, fontFeatureSettings = "tnum",
                    letterSpacing = (-0.02).em, color = palette.inkPrimary),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 52.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(WeightUnits.symbol, style = TextStyle(fontSize = 18.sp), color = palette.inkTertiary,
                modifier = Modifier.padding(bottom = 7.dp))
        }
        Text(summary.percentOfMax?.let { L10n.tr("%d %% of max", roundedPercent(it)) } ?: " ",
            style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier) {
    val palette = LocalGripPalette.current
    Column(
        modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CapsLabel(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Medium, color = palette.inkPrimary)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = palette.inkTertiary,
                modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/// Every pull's average as a column, the CF pulls in bleu, CF drawn across them as a dashed
/// rule: the plateau the number came from.
///
/// TRANSLATION NOTE: iOS draws this with Swift Charts (`BarMark` + `RuleMark`). As with
/// `MaxChart`, there is no chart library in this project's dependency set, so it is a
/// Canvas with the same marks: squared-off bars, x labels at pulls 1, 8, 16 and 24, three
/// y gridlines on round values.
@Composable
fun CriticalForcePullChart(summary: CriticalForceSummary, modifier: Modifier = Modifier, showsCaptions: Boolean = true) {
    val palette = LocalGripPalette.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = palette.inkTertiary)
    val chartHeight = with(LocalDensity.current) { (150 * fontScale).dp }
    val means = summary.repMeans
    val spoken = run {
        val first = means.firstOrNull()
        val last = means.lastOrNull()
        if (first != null && last != null) {
            WeightUnits.tr("From %s on the first pull to %s on the last",
                WeightUnits.number(first), WeightUnits.number(last))
        } else ""
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .heightIn(min = chartHeight)
                .height(chartHeight)
                .semantics { contentDescription = L10n.tr("Average force per pull") + ". " + spoken },
        ) {
            val count = max(1, means.size)
            val values = means.map { WeightUnits.fromKg(it ?: 0.0) }
            val cf = WeightUnits.fromKg(summary.criticalForceKg)
            val top = niceCeiling(max(values.maxOrNull() ?: 0.0, cf))
            val step = top / 3
            val labelWidth = (0..3).maxOf {
                measurer.measure(axisText(step * it), labelStyle).size.width
            }.toFloat()
            val left = labelWidth + 6.dp.toPx()
            val bottomLabel = measurer.measure("24", labelStyle).size.height.toFloat()
            val plotTop = 4.dp.toPx()
            val plotBottom = size.height - bottomLabel - 4.dp.toPx()
            val plotHeight = max(1f, plotBottom - plotTop)
            val plotWidth = max(1f, size.width - left)
            fun y(value: Double) = plotBottom - (value / top).toFloat().coerceIn(0f, 1f) * plotHeight

            for (i in 0..3) {
                val value = step * i
                val gy = y(value)
                drawRect(palette.inkTertiary.copy(alpha = 0.2f), Offset(left, gy), Size(plotWidth, 1f))
                val text = measurer.measure(axisText(value), labelStyle)
                drawText(text, topLeft = Offset(0f, gy - text.size.height / 2f))
            }
            val slot = plotWidth / count
            val barWidth = max(1f, slot * 0.62f)
            values.forEachIndexed { index, value ->
                val x = left + slot * index + (slot - barWidth) / 2f
                val by = y(value)
                val colour = if ((index + 1) in summary.criticalForceReps) palette.bleu
                else palette.inkTertiary.copy(alpha = 0.45f)
                drawRoundRect(colour, Offset(x, by), Size(barWidth, plotBottom - by), CornerRadius(2.dp.toPx()))
            }
            val ry = y(cf)
            drawLine(palette.bleu, Offset(left, ry), Offset(left + plotWidth, ry), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            for (pull in listOf(1, 8, 16, 24).filter { it <= count }) {
                val text = measurer.measure("$pull", labelStyle)
                val cx = left + slot * (pull - 1) + slot / 2f
                // The last label hangs INSIDE the plot (iOS: `.topTrailing`): centred on the
                // final bar it ran past the edge and was clipped.
                val x = if (pull == count) cx + slot / 2f - text.size.width else cx - text.size.width / 2f
                drawText(text, topLeft = Offset(x, plotBottom + 3.dp.toPx()))
            }
        }
        if (showsCaptions) {
            Text(
                tr("Each bar is one pull’s average. Critical force is the mean of pulls %d–%d, where your force levels off.",
                    summary.criticalForceReps.first, summary.criticalForceReps.last),
                style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary,
            )
            val missed = summary.restsTotal - summary.restsKept
            if (summary.restsTotal > 0) {
                Text(
                    if (missed == 0) tr("Every rest kept on the beat.")
                    else tr("Still on the edge after the bell in %d of %d rests. Force after the bell isn’t counted, but it eats into your rest.",
                        missed, summary.restsTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (missed == 0) palette.inkTertiary else palette.armed,
                )
            }
        }
    }
}

private fun axisText(value: Double): String =
    if (abs(value - value.roundToInt()) < 1e-6) "${value.roundToInt()}" else WeightUnits.formatDisplayed(value, 1)

/// A round top for three gridlines: 1, 2, 2.5 or 5 × a power of ten, times three.
private fun niceCeiling(value: Double): Double {
    if (!(value > 0)) return 3.0
    val raw = value / 3
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    return ceil(value / step) * step
}
