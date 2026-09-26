// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.data.LifetimeStats
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
@Composable
private fun LedgerRow(label: String, value: String) {
    val palette = LocalGripPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
        )
    }
}

/// Hours and minutes once there are hours, minutes before — two units, never three.
@Composable
private fun heldText(seconds: Double): String {
    val total = seconds.toLong()
    val hours = (total / 3600).toInt()
    val minutes = ((total % 3600) / 60).toInt()
    return if (hours > 0) tr("%d hours and %d minutes", hours, minutes) else tr("%d min", minutes)
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
            if (volumeKg >= 1000) tr("%s t", oneDecimal.format(volumeKg / 1000))
            else "${whole.format(volumeKg)} ${unit.symbol}"
        WeightUnit.lb -> "${whole.format(unit.fromKg(volumeKg))} ${unit.symbol}"
    }
}
