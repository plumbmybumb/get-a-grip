// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.routine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineProtocol
import run.nuri.getagrip.engine.RoutineSummary
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.EdgeMark
import run.nuri.getagrip.ui.components.tint
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.share.RoutinePreview
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// "New routine": build your own, or start from a known protocol (Nuri, 2026-09-25).
///
/// Build from scratch LEADS — the plan is still yours first. A protocol opens the same full
/// preview a scanned code gets (`RoutinePreview`), so nothing lands without being read, and
/// what lands is an ordinary routine: the card it becomes opens the builder like any other.
///
/// TRANSLATION NOTE (from Sources/UI/Routine/NewRoutineSheet.swift): iOS pushes the preview
/// on the sheet's navigation stack; here the sheet swaps its content, with a back arrow and
/// the system back gesture returning to the list.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRoutineSheet(
    /// The presenter opens the builder; this sheet is gone by then.
    onBuildFromScratch: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var previewingRaw by rememberSaveable { mutableStateOf<String?>(null) }
    val previewing = RoutineProtocol.entries.firstOrNull { it.rawValue == previewingRaw }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
    ) {
        if (previewing != null) {
            BackHandler { previewingRaw = null }
            // One draft per protocol shown: `draft` mints fresh row ids on every read.
            val draft = remember(previewing) { previewing.draft }
            RoutinePreview(
                draft,
                title = previewing.title,
                source = previewing.source,
                caution = previewing.caution,
                onDone = onClose,
                modifier = Modifier.testTag("newRoutine.preview.${previewing.rawValue}"),
                navigation = {
                    IconButton(onClick = { previewingRaw = null }, modifier = Modifier.testTag("newRoutine.back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"), tint = palette.inkPrimary)
                    }
                },
            )
        } else {
            Chooser(onBuildFromScratch, onClose) { previewingRaw = it.rawValue }
        }
    }
}

@Composable
private fun Chooser(onBuildFromScratch: () -> Unit, onClose: () -> Unit, onPreview: (RoutineProtocol) -> Unit) {
    val palette = LocalGripPalette.current
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Metrics.hPadding)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("New routine"), style = MaterialTheme.typography.titleLarge, color = palette.inkPrimary,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, modifier = Modifier.testTag("newRoutine.cancel")) {
                Text(tr("Cancel"), color = palette.graphite, fontWeight = FontWeight.SemiBold)
            }
        }
        ChooserCard(
            spoken = tr("Build from scratch") + ". " + tr("Name it, add a set, make it yours.") + ". " +
                tr("Opens the routine builder."),
            onClick = onBuildFromScratch,
            modifier = Modifier.testTag("newRoutine.scratch"),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = palette.inkSecondary,
                modifier = Modifier.width(28.dp).size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Build from scratch"), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
                Text(tr("Name it, add a set, make it yours."), style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary)
            }
        }
        CapsLabel(tr("KNOWN PROTOCOLS"), Modifier.padding(top = 12.dp, start = 4.dp))
        RoutineProtocol.allCases.forEach { item -> ProtocolRow(item) { onPreview(item) } }
        Text(
            tr("Written up from what each author published. Get a Grip is not affiliated with or endorsed by them."),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkTertiary,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/// One protocol: its mark, name, attribution, what it is, and what it costs.
@Composable
private fun ProtocolRow(item: RoutineProtocol, onClick: () -> Unit) {
    val palette = LocalGripPalette.current
    // Folded once per row, like the preview's: `previewing` walks the rep sequence.
    val summary = remember(item) { RoutineSummary.previewing(item.draft.normalized) }
    ChooserCard(
        spoken = listOf(item.title, item.source, item.blurb, summary.metaLine).joinToString(". ") +
            ". " + tr("Shows the whole protocol before adding it."),
        onClick = onClick,
        modifier = Modifier.testTag("newRoutine.protocol.${item.rawValue}"),
        alignment = Alignment.Top,
    ) {
        // The mark the card will wear once added, intensity on the rung.
        EdgeMark(
            fingers = summary.signatureFingers ?: FingerSet.four,
            rungTint = PlanMath.IntensityBand.band(summary.peakIntensity).tint(palette),
            modifier = Modifier.padding(top = 4.dp).width(28.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary)
            Text(item.source, style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
            Text(item.blurb, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary,
                modifier = Modifier.padding(top = 2.dp))
            Text(summary.metaLine, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Medium, color = palette.inkSecondary)
        }
    }
}

/// A card that is one tap: the whole surface is the target, and TalkBack hears one sentence.
@Composable
private fun ChooserCard(
    spoken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val palette = LocalGripPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier.fillMaxWidth().heightIn(min = Metrics.controlMinHeight)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Row(
            Modifier.padding(16.dp).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = alignment,
        ) {
            content()
            Spacer(Modifier.width(0.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = palette.inkTertiary,
                modifier = Modifier.padding(top = if (alignment == Alignment.Top) 4.dp else 0.dp).size(20.dp))
        }
    }
}
