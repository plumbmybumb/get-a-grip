// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.MaxAttemptLog
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.units.WeightUnits

/// The pulls of one visit, per hand, and the one each hand will save.
///
/// Each hand keeps its hardest pull; tap another to keep that one instead. A pull made with
/// the wrong hand selected moves across from its menu — the commonest slip in a two-hand
/// visit — and a bad one can be deleted. Nothing is written until Save.
///
/// TRANSLATION NOTE (from Sources/UI/Maxes/MaxAttemptReviewSheet.swift): iOS hides Move and
/// Delete behind a swipe and a context menu. Android has neither on a plain row, so each row
/// carries a visible ⋮ with the same two actions. A full-screen layer over the visit, not a
/// sheet, as the correction screen already is.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaxAttemptReviewScreen(session: LiveMaxSession, onSave: () -> Unit, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current
    val draft = session.snapshot
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(tr("Review pulls"), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    TextButton(onClick = onClose, enabled = !session.isSaving,
                        modifier = Modifier.testTag("max.review.back")) {
                        Text(tr("Keep pulling"), color = palette.graphite, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = palette.card,
                    titleContentColor = palette.inkPrimary,
                ),
            )
        },
        bottomBar = {
            Box(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .readablePageWidth()
                    .padding(horizontal = Metrics.hPadding).padding(top = 10.dp, bottom = 6.dp),
            ) { Footer(session, onSave) }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).readablePageWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding).padding(top = 8.dp, bottom = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(Metrics.spacing),
        ) {
            draft.sides.forEach { side ->
                HandSection(side, session) { action ->
                    when (action) {
                        is RowAction.Pick -> {
                            session.pick(action.id)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        is RowAction.Move -> session.move(action.id, action.to)
                        is RowAction.Delete -> session.remove(action.id)
                    }
                }
            }
        }
    }
}

private sealed interface RowAction {
    data class Pick(val id: Int) : RowAction
    data class Move(val id: Int, val to: Side) : RowAction
    data class Delete(val id: Int) : RowAction
}

@Composable
private fun HandSection(side: Side, session: LiveMaxSession, onAction: (RowAction) -> Unit) {
    val palette = LocalGripPalette.current
    // Read HERE, not handed down: the draft is one mutable object, so as a parameter it
    // compares equal to itself and the section would skip every change.
    val draft = session.snapshot
    val attempts = draft.log.attempts(side)
    val kept = draft.kept(side)?.id
    val best = draft.log.best(side)?.id
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsLabel(handTitle(side))
        InstrumentSurface(Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (attempts.isEmpty()) {
                    Text(tr("No pulls with this hand."), style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkTertiary, modifier = Modifier.padding(16.dp))
                }
                attempts.forEachIndexed { index, attempt ->
                    PullRow(attempt, number = index + 1, kept = attempt.id == kept, best = attempt.id == best,
                        bothTogether = draft.bothTogether, onAction = onAction)
                }
            }
        }
        draft.peak(side)?.let { peak ->
            Text(
                if (draft.isCorrected(side)) tr("Saves %s, adjusted by hand.", WeightUnits.text(peak))
                else tr("Saves %s.", WeightUnits.text(peak)),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
                modifier = Modifier.testTag("max.review.saving.${side.rawValue}"),
            )
        }
    }
}

@Composable
private fun PullRow(
    attempt: MaxAttemptLog.Attempt,
    number: Int,
    kept: Boolean,
    best: Boolean,
    bothTogether: Boolean,
    onAction: (RowAction) -> Unit,
) {
    val palette = LocalGripPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { if (!kept) onAction(RowAction.Pick(attempt.id)) },
            color = Color.Transparent,
            shape = RoundedCornerShape(Metrics.radiusInner),
            modifier = Modifier.weight(1f).heightIn(min = Metrics.controlMinHeight)
                .testTag("max.review.pull.${attempt.id}")
                .semantics(mergeDescendants = true) { selected = kept },
        ) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (kept) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null, tint = if (kept) palette.bleu else palette.inkTertiary,
                    modifier = Modifier.size(22.dp))
                Text(tr("Pull %d", number), style = MaterialTheme.typography.bodyLarge, color = palette.inkPrimary)
                if (best) CapsLabel(tr("Best"))
                Spacer(Modifier.weight(1f).width(8.dp))
                Text(WeightUnits.text(attempt.peakKg),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = if (kept) FontWeight.SemiBold else FontWeight.Normal, color = palette.inkPrimary)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("max.review.menu.${attempt.id}")) {
                Icon(Icons.Filled.MoreVert, contentDescription = tr("Pull options"), tint = palette.inkSecondary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!bothTogether) {
                    DropdownMenuItem(
                        text = { Text(moveTitle(attempt.side)) },
                        leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                        onClick = { menu = false; onAction(RowAction.Move(attempt.id, attempt.side.other)) },
                        modifier = Modifier.testTag("max.review.move.${attempt.id}"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(tr("Delete"), color = palette.alarm) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = palette.alarm) },
                    onClick = { menu = false; onAction(RowAction.Delete(attempt.id)) },
                    modifier = Modifier.testTag("max.review.delete.${attempt.id}"),
                )
            }
        }
    }
}

@Composable
private fun Footer(session: LiveMaxSession, onSave: () -> Unit) {
    val palette = LocalGripPalette.current
    val results = session.snapshot.results
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (session.saveFailed) {
            Text(tr("Couldn’t save. Your pulls are still here — try again."),
                style = MaterialTheme.typography.bodySmall, color = palette.alarm, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("max.review.saveFailed"))
        }
        PrimaryButton(
            title = if (results.size == 1) tr("Save max") else tr("Save maxes"),
            icon = Icons.Filled.Check,
            enabled = results.isNotEmpty() && !session.isSaving && !session.committed,
            modifier = Modifier.fillMaxWidth().testTag("max.review.save"),
            onClick = onSave,
        )
        SecondaryButton(
            title = tr("Adjust values"),
            icon = Icons.Outlined.Edit,
            enabled = results.isNotEmpty() && !session.isSaving && !session.committed,
            modifier = Modifier.fillMaxWidth().testTag("max.measure.adjust"),
        ) { session.adjusting = true }
    }
}

@Composable
private fun handTitle(side: Side): String = when (side) {
    Side.left -> tr("Left hand")
    Side.right -> tr("Right hand")
    Side.both -> tr("Both hands together")
}

@Composable
private fun moveTitle(from: Side): String =
    if (from == Side.left) tr("Move to right hand") else tr("Move to left hand")
