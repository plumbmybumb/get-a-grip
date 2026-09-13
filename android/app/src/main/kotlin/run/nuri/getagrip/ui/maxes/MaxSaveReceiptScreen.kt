// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.maxes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.components.*
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.units.WeightUnits

/** A receipt for an already committed save. Typed targets only change after a separate tap. */
@Composable
fun MaxSaveReceiptScreen(receipt: TemplateStore.MaxSaveReceipt, onDone: () -> Unit) {
    val templates = LocalTemplateStore.current
    val palette = LocalGripPalette.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val submission = remember(receipt.id) { SubmissionState() }
    var scaled by remember(receipt.id) { mutableStateOf(emptySet<String>()) }
    var failed by remember(receipt.id) { mutableStateOf(emptySet<String>()) }
    MaxesFlowScaffold(tr("Saved"), onClose = onDone, closeEnabled = !submission.isRunning, actions = {
        TextButton(onClick = onDone, enabled = !submission.isRunning,
            modifier = Modifier.testTag("max.receipt.done")) { Text(tr("Done"), fontWeight = FontWeight.SemiBold) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readablePageWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = Metrics.hPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.spacing)) {
            MaxesContentCard {
                receipt.values.forEach { value ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlyphTile(value.grip)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(value.grip.displayName, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
                            Text(tr(when (value.side) { Side.left -> "Left hand"; Side.right -> "Right hand"; Side.both -> "Shared max" }),
                                style = MaterialTheme.typography.labelLarge, color = palette.inkSecondary)
                            Text(WeightUnits.text(value.kg), style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
                        }
                    }
                }
            }
            if (receipt.percentMoves.isNotEmpty()) MaxesContentCard {
                CapsLabel(tr("TARGETS THAT FOLLOWED"))
                receipt.percentMoves.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.move.routineName, fontWeight = FontWeight.SemiBold)
                        Text("${item.grip.displayName} · ${item.move.side.displayName}",
                            style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                        Text(receiptPercentLine(item.move), style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary)
                    }
                }
                Text(tr("Percent targets always follow your newest max — nothing to do."),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
            }
            receipt.rescaleOffers.forEach { offer ->
                MaxesContentCard {
                    Text(tr("Weight targets"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(offer.grip.displayName, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                    offer.routines.forEach { routine ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(routine.routineName, fontWeight = FontWeight.SemiBold)
                            routine.moves.forEach { move ->
                                Text("${WeightUnits.band(move.oldBand)}  →  ${WeightUnits.band(move.newBand)}",
                                    style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
                            }
                        }
                    }
                    if (offer.id in scaled) {
                        Text(tr("Weight targets updated"), color = palette.inkSecondary)
                    } else {
                        Text(tr("These were typed by hand, so they never move on their own. Scale them with the new max, or leave them."),
                            style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                        if (offer.id in failed) Text(tr("Couldn’t update the weight targets. Your maxes are saved. Try again, or leave the targets as they are."),
                            color = palette.alarm, style = MaterialTheme.typography.bodySmall)
                        PrimaryButton(tr("Scale with the new max"), enabled = !submission.isRunning,
                            modifier = Modifier.testTag("max.receipt.scale.${offer.id}")) {
                            submission.launch(scope) {
                                if (templates.applyMaxRescale(offer)) {
                                    scaled = scaled + offer.id
                                    failed = failed - offer.id
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                } else failed = failed + offer.id
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun receiptPercentLine(move: TemplateStore.MaxImpact.PercentMove): String {
    val pct = L10n.tr("%d–%d %%", Math.round(move.loPercent * 100), Math.round(move.hiPercent * 100))
    var result = WeightUnits.tr("%s · now %s kg", pct, WeightUnits.band(move.newBand, withUnit = false))
    move.oldBand?.takeIf { it != move.newBand }?.let {
        result += L10n.tr(" · was %s", WeightUnits.band(it, withUnit = false))
    }
    return result
}
