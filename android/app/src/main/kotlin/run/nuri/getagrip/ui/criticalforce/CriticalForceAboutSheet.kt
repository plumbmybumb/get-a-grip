// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.criticalforce

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// Everything the setup screen no longer says, for whoever wants it: what the test
/// measures, how to keep tests comparable, and what "One at a time" means.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriticalForceAboutSheet(onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val state = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
        modifier = Modifier.testTag("cf.aboutSheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding).padding(bottom = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("About the test"), style = MaterialTheme.typography.titleMedium,
                    color = palette.inkPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }) {
                    Text(tr("Done"), color = palette.graphite, fontWeight = FontWeight.SemiBold)
                }
            }
            Section(tr("What it measures"),
                tr("Critical force is the force your fingers can keep producing once the fast reserve is spent: your endurance ceiling. The test drains that reserve with 24 all-out pulls, and your force levels off at your critical force."))
            Section(tr("The clock never waits"),
                tr("Seven seconds on, three off, every time. The result depends on that rhythm, so the rest does not wait for you to let go. Force pulled after the bell isn’t counted."))
            Section(tr("Keep tests comparable"),
                tr("Warm up first. Use the same grip, hand and arm position each time, and leave a few weeks between tests. Your first test is partly practice."))
            Section(tr("Hands"),
                tr("One at a time runs all 24 pulls on one hand, then all 24 on the other, and each hand gets its own number. Both hands means both pulling together through the gauge. The hands never alternate pull by pull, because that changes the rhythm and the result."))
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    val palette = LocalGripPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
    }
}
