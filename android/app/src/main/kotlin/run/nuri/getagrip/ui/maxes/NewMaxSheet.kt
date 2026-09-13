// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.maxes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.*
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth

/** Hosted above the measurement route so cancellation returns to the same chosen grip. */
class NewMaxDraft(seed: GripSpec = GripSpec()) {
    var grip by mutableStateOf(seed)
}

@Composable
fun NewMaxSheet(
    draft: NewMaxDraft,
    onMeasure: (GripSpec, Side) -> Unit,
    onEnter: (GripSpec) -> Unit,
    onShared: (GripSpec) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    MaxesFlowScaffold(title = tr("New max"), onClose = onClose, actions = {
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("newMax.options")) {
                Icon(Icons.Filled.MoreVert, tr("More max options"))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(tr("Measure both hands together")) }, onClick = {
                    menuOpen = false; onMeasure(draft.grip, Side.both)
                })
                DropdownMenuItem(text = { Text(tr("One value for both hands")) }, onClick = {
                    menuOpen = false; onShared(draft.grip)
                })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readablePageWidth()
            .verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = Metrics.hPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FingerGlyph(draft.grip.fingers, position = draft.grip.position, dot = 18.dp, gap = 7.dp)
                Text(draft.grip.displayName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = palette.inkPrimary, textAlign = TextAlign.Center)
            }
            if (templates.recentGrips.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapsLabel(tr("START FROM"))
                    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("newMax.recentGrips"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        templates.recentGrips.forEach { candidate ->
                            FilterChip(selected = candidate.key == draft.grip.key,
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(vertical = 8.dp)) {
                                        FingerGlyph(candidate.fingers, position = candidate.position, dot = 6.dp, gap = 3.dp)
                                        Text(candidate.shortName)
                                    }
                                },
                                modifier = Modifier.testTag("newMax.grip.${candidate.key}").semantics {
                                    contentDescription = candidate.spoken
                                    selected = candidate.key == draft.grip.key
                                }, onClick = {
                                if (candidate.key != draft.grip.key) {
                                    draft.grip = candidate
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            })
                        }
                    }
                    Text(tr("Grips from your routines. Tap one, then change anything you like."),
                        style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
                }
            }
            IntValueRow(title = tr("Edge"), value = draft.grip.edgeMM, range = 4..45,
                unit = tr("mm"), limit = GripSpec.edgeRange, presets = listOf(6, 10, 20, 30)) {
                draft.grip = draft.grip.withEdgeMM(it)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapsLabel(tr("FINGERS"))
                FingerPips(draft.grip.fingers, draft.grip.position) { draft.grip = draft.grip.withFingers(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapsLabel(tr("GRIP"))
                PositionChipRow(draft.grip.position) { draft.grip = draft.grip.withPosition(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(tr("Measure on the gauge"), modifier = Modifier.testTag("newMax.measure")) {
                    onMeasure(draft.grip, Side.left)
                }
                SecondaryButton(tr("Enter by hand"), modifier = Modifier.fillMaxWidth().testTag("newMax.enter")) {
                    onEnter(draft.grip)
                }
                Text(tr("Measure your left and right hands in one visit, or enter the values you already know."),
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}
