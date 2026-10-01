// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.components.ChipGrid
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// What a measurement will be, chosen BEFORE the gauge screen opens (Nuri, 2026-10-01: "you
/// decide what type of max you're doing before you get to the measurement screen").
///
/// It replaced a three-button dialog once a max grew a LENGTH: the peak, or a timed max —
/// your average over 5, 10 or 15 s, or any length on the dial. A dialog cannot hold a dial.
/// The two hand buttons ARE the start: choosing how you pull commits the rest in one tap.
///
/// TRANSLATION NOTE (iOS `MaxMeasureChooser`): a ModalBottomSheet that wraps its content —
/// the height iOS has to measure for (a full-height sheet was mostly dead space) is the
/// Material sheet's own behaviour with `skipPartiallyExpanded`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaxMeasureChooser(
    grip: GripSpec,
    /// The length to open on — what a routine is waiting for (`TemplateStore.missingTimedLength`).
    initialSeconds: Int,
    onMax: (Side, Int) -> Unit,
    onDismiss: () -> Unit,
    /// null hides critical force: a new-max form is about a max.
    onCriticalForce: (() -> Unit)? = null,
) {
    val palette = LocalGripPalette.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var criticalForce by rememberSaveable { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableIntStateOf(initialSeconds) }
    // "Other" is sticky once chosen, so dragging the dial onto 10 does not snap it shut.
    var other by rememberSaveable { mutableStateOf(initialSeconds > 0 && initialSeconds !in PRESETS) }

    // Cancel slides the sheet away first; a CHOICE acts at once — every one of them opens
    // a full screen that replaces the root, which disposes this sheet in the same frame.
    // Waiting for the hide animation was dead time on every measurement (audit, 2026-10-01).
    fun close(then: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { then() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
        modifier = Modifier.testTag("max.chooser"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding).padding(bottom = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (onCriticalForce != null) tr("What are you measuring?") else tr("Measure a max"),
                        style = MaterialTheme.typography.titleMedium, color = palette.inkPrimary,
                        modifier = Modifier.semantics { heading() })
                    Text(grip.displayName, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                }
                TextButton(onClick = { close(onDismiss) }, modifier = Modifier.testTag("max.chooser.cancel")) {
                    Text(tr("Cancel"), color = palette.inkSecondary)
                }
            }

            if (onCriticalForce != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to tr("Max"), true to tr("Critical force")).forEachIndexed { index, (isCF, label) ->
                        SegmentedButton(
                            selected = criticalForce == isCF,
                            onClick = { criticalForce = isCF },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            modifier = Modifier.testTag(if (isCF) "max.chooser.criticalForce" else "max.chooser.max"),
                        ) { Text(label) }
                    }
                }
            }

            if (!criticalForce) {
                ChipGrid(base = 5, content = buildList {
                    add { m ->
                        Chip(tr("Peak"), isSelected = seconds == 0 && !other, modifier = m.testTag("max.chooser.peak")) {
                            other = false; seconds = 0
                        }
                    }
                    PRESETS.forEach { preset ->
                        add { m ->
                            Chip(tr("%d s", preset), isSelected = seconds == preset && !other,
                                modifier = m.testTag("max.chooser.$preset")) {
                                other = false; seconds = preset
                            }
                        }
                    }
                    add { m ->
                        Chip(tr("Other"), isSelected = other, modifier = m.testTag("max.chooser.other")) {
                            other = true
                            if (seconds == 0) seconds = 20
                        }
                    }
                })
                if (other) {
                    // The builder's dial: one detent per second, so 17 s is a drag, and the
                    // number is tappable to type.
                    IntValueRow(
                        title = tr("Hold for"), value = seconds, range = RANGE, unit = "s", limit = RANGE,
                        control = ValueControl.Dial(RANGE.map { it.toDouble() }),
                        spokenUnit = tr("seconds"),
                        modifier = Modifier.testTag("max.chooser.dial"),
                    ) { seconds = it.coerceIn(RANGE) }
                }
                Text(
                    if (seconds == 0) tr("Your hardest single reading.")
                    else tr("Your average over %d s. Letting go early doesn't count.", seconds),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(tr("One hand at a time"), modifier = Modifier.testTag("max.mode.hands")) {
                        onMax(Side.left, seconds)
                    }
                    SecondaryButton(tr("Both hands together"),
                        modifier = Modifier.fillMaxWidth().testTag("max.mode.both")) {
                        onMax(Side.both, seconds)
                    }
                }
            } else {
                Text(tr("The four-minute endurance test: all-out pulls until your force levels off."),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                PrimaryButton(tr("Start the test"), modifier = Modifier.testTag("max.mode.criticalForce")) {
                    onCriticalForce?.invoke()
                }
            }
        }
    }
}

private val PRESETS = listOf(5, 10, 15)
private val RANGE = 3..60
