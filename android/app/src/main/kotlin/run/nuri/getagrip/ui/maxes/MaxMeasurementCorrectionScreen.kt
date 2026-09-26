// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxMeasurementResult
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.ValueRow
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.units.WeightUnits

/** Correct captured values locally. Applying neither writes a max nor changes its raw trace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaxMeasurementCorrectionScreen(
    results: List<MaxMeasurementResult>,
    measuredPeaks: Map<Side, Double>,
    onApply: (List<MaxMeasurementResult>) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf(results.toList()) }
    var applying by remember { mutableStateOf(false) }
    BackHandler { if (!applying) onCancel() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(tr("Adjust values"), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !applying,
                               modifier = Modifier.testTag("max.adjust.cancel")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Cancel"))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (!applying) {
                                applying = true
                                // ValueRow commits on focus loss. Wait one frame before
                                // reading values so the last typed digits join this Apply.
                                focus.clearFocus(force = true)
                                scope.launch {
                                    withFrameNanos { }
                                    if (values.isNotEmpty() && values.all { it.kg.isFinite() && it.kg > 0 }) {
                                        onApply(values)
                                    }
                                    applying = false
                                }
                            }
                        }, enabled = !applying, modifier = Modifier.testTag("max.adjust.apply"),
                    ) { Text(tr("Apply")) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent, scrolledContainerColor = palette.card,
                    titleContentColor = palette.inkPrimary, navigationIconContentColor = palette.inkPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().readablePageWidth()
                .verticalScroll(rememberScrollState()).padding(Metrics.hPadding),
            verticalArrangement = Arrangement.spacedBy(Metrics.spacing),
        ) {
            values.forEach { value ->
                val measured = measuredPeaks[value.side] ?: value.kg
                ValueRow(
                    title = when (value.side) {
                        Side.left -> tr("Left hand")
                        Side.right -> tr("Right hand")
                        Side.both -> tr("Both hands")
                    },
                    value = WeightUnits.fromKg(value.kg),
                    range = WeightUnits.sliderRange(0.0..100.0),
                    limit = WeightUnits.fromKg(0.0..maxOf(250.0, measured)),
                    unit = WeightUnits.symbol, step = 0.5, decimals = 1,
                    caption = L10n.tr("Measured: %s", WeightUnits.text(measured)),
                    modifier = Modifier.testTag("max.adjust.${value.side.rawValue}"),
                ) { displayed ->
                    values = values.map {
                        if (it.side == value.side) it.copy(kg = WeightUnits.toKg(displayed)) else it
                    }
                }
            }
            if (values.any { !it.kg.isFinite() || it.kg <= 0 }) {
                Text(tr("Enter a max above zero to save it."),
                     style = MaterialTheme.typography.bodySmall, color = palette.alarm)
            }
            Text(tr("Adjusted values save as manual entries. The trace is unchanged."),
                 style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
        }
    }
}
