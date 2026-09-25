// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// **The one question before a measurement on a grip**: a max (one hand at a time, or both
/// together), or, where offered, a critical force test (Nuri, 2026-09-25: "when you hit
/// measure… shouldn't it ask if you are measuring CF or max?"). Asked up front because it
/// decides what the visit can save — two per-hand values, or one combined value that is
/// never derived from two separate hands.
///
/// `onCriticalForce` null hides the third choice: a new-max form is about a max.
///
/// TRANSLATION NOTE (iOS `maxMeasureModeDialog`, a confirmation dialog): a Material dialog
/// with the choices as rows. Both max choices open the max VISIT (`MaxMeasureScreen`): one
/// hand at a time starts on the left hand and switches with a tap.
@Composable
internal fun MaxMeasureModeDialog(
    onMax: (Side) -> Unit,
    onDismiss: () -> Unit,
    onCriticalForce: (() -> Unit)? = null,
) {
    val palette = LocalGripPalette.current
    val offersCriticalForce = onCriticalForce != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (offersCriticalForce) tr("What are you measuring?") else tr("How are you measuring?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (offersCriticalForce) tr("A max is your hardest pull. Critical force is the four-minute endurance test.")
                    else tr("Pull as many times as you like. Each hand keeps its hardest pull."),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val choices = buildList {
                    add(Triple(if (offersCriticalForce) tr("Max, one hand at a time") else tr("One hand at a time"),
                        "max.mode.hands") { onMax(Side.left) })
                    add(Triple(if (offersCriticalForce) tr("Max, both hands together") else tr("Both hands together"),
                        "max.mode.both") { onMax(Side.both) })
                    if (onCriticalForce != null) add(Triple(tr("Critical force test"), "max.mode.criticalForce", onCriticalForce))
                }
                choices.forEach { (label, tag, action) ->
                    TextButton(onClick = action, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                        Text(label, color = palette.graphite, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = palette.inkSecondary) } },
        containerColor = palette.card,
        titleContentColor = palette.inkPrimary,
        textContentColor = palette.inkSecondary,
    )
}
