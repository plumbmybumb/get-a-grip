// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.data.LifetimeStats
import run.nuri.getagrip.engine.DisplayText
import java.util.Locale
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/// **All time, in History between the calendar and the sessions** (Nuri, 2026-09-20:
/// "lifetime stats — number of routines done, total load lifetime, anything else?").
/// Sessions, pulls, time under tension, volume, days trained, climbing days, the heaviest
/// pull, and the date they count from. Nothing here is a chart or a trend — the calendar
/// above it owns those — this is the odometer, and it sits right over the sessions it adds
/// up.
///
/// A LEDGER, not tiles: label left, value right, the rows the Settings device card is drawn
/// as. The iOS first cut was a three-column grid and read as ragged — a "49" left a hole two
/// numbers wide (Nuri: "spacing on this is kind of ugly"). Folded from denormalized columns
/// (`Collection<WorkoutLogEntity>.lifetime`), so it costs a row per session, never a decode.
@Composable
fun LifetimeCard(stats: LifetimeStats, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val title = stats.since?.let { since ->
        tr("All time · since %s", since.localDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    } ?: tr("All time")
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsLabel(title)
            if (stats.isEmpty) {
                Text(
                    tr("Your all-time totals appear after your first session."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary,
                )
            } else {
                val integers = NumberFormat.getIntegerInstance()
                LedgerRow(tr("Sessions"), integers.format(stats.sessions))
                LedgerRow(tr("Pulls"), integers.format(stats.pulls))
                LedgerRow(tr("Under tension"), heldText(stats.heldSeconds))
                LedgerRow(tr("Volume"), volumeText(stats.volumeKg))
                LedgerRow(tr("Days trained"), integers.format(stats.daysTrained))
                if (stats.climbDays > 0) LedgerRow(tr("Climbing days"), integers.format(stats.climbDays))
                LedgerRow(tr("Heaviest pull"), WeightUnits.current.text(stats.heaviestPullKg))
                Text(
                    tr("Volume is load × pulls. Under tension is total time on the edge."),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/// The Settings device card's row, with the value in primary ink and a little weight: these
/// are facts about the person, not the link's status.
///
/// **Side by side only when both fit on one line with a real gap; otherwise STACKED** —
/// label above, value under it on the same right-hand axis. A plain SpaceBetween row let
/// "Under tension" and its value touch from 1.5 and wrapped the value over three lines
/// at 2.0. Measured, not thresholded on the font scale, so a short value like "91" stays
/// beside its label at every size.
@Composable
private fun LedgerRow(label: String, value: String) {
    val palette = LocalGripPalette.current
    Layout(
        content = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
                textAlign = TextAlign.End,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = LEDGER_GAP.roundToPx()
        val labelWidth = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val valueWidth = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        val loose = Constraints(maxWidth = width)
        if (labelWidth + gap + valueWidth <= width) {
            val l = measurables[0].measure(loose)
            val v = measurables[1].measure(loose)
            layout(width, maxOf(l.height, v.height)) {
                l.placeRelative(0, 0)
                v.placeRelative(width - v.width, 0)
            }
        } else {
            val l = measurables[0].measure(loose)
            val v = measurables[1].measure(loose)
            layout(width, l.height + v.height) {
                l.placeRelative(0, 0)
                v.placeRelative(width - v.width, l.height)
            }
        }
    }
}

private val LEDGER_GAP = 12.dp

/// Two units, never three — the iOS card's `Duration.formatted(.units(width:
/// .abbreviated, maximumUnitCount: 2))`, through the same CLDR data: "8 hr, 38 min",
/// "8 h et 38 min". The long "8 hours and 38 minutes" was the one value on the card too
/// wide to sit beside its label at large text.
private fun heldText(seconds: Double): String {
    val total = seconds.toLong()
    val hours = (total / 3600).toInt()
    val minutes = ((total % 3600) / 60).toInt()
    val format = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.SHORT)
    val text = if (hours > 0) {
        format.formatMeasures(Measure(hours, MeasureUnit.HOUR), Measure(minutes, MeasureUnit.MINUTE))
    } else {
        format.formatMeasures(Measure(minutes, MeasureUnit.MINUTE))
    }
    // A number never parts from its unit; the list separator may still break.
    return text.replace(Regex("(\\d)[ \\u202F]")) { "${it.groupValues[1]}${DisplayText.NBSP}" }
}

/// Tonnes once kilograms pass a thousand — "20.3 t" is the odometer reading, "20 300 kg" is
/// a spreadsheet. Pounds stay pounds: a ton is two different weights in English.
@Composable
private fun volumeText(volumeKg: Double): String {
    val unit = WeightUnits.current
    val oneDecimal = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
    val whole = NumberFormat.getIntegerInstance()
    return when (unit) {
        WeightUnit.kg ->
            if (volumeKg >= 1000) DisplayText.token(tr("%s t", oneDecimal.format(volumeKg / 1000)))
            else "${whole.format(volumeKg)}${DisplayText.NBSP}${unit.symbol}"
        WeightUnit.lb -> "${whole.format(unit.fromKg(volumeKg))}${DisplayText.NBSP}${unit.symbol}"
    }
}
