// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.AgentRoutine
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// "Create with AI" (Nuri, 2026-10-02): two steps, both on one screen. Copy the instructions
/// into any AI chat, describe the routine there, paste the reply back.
///
/// The reply is read HERE, so a paste that is not a routine says so where the climber can paste
/// again. A readable one leaves through Today's import inbox, like a scanned code, and lands in
/// the same preview — nothing is saved until "Add to my routines".
///
/// `onRoutine` fires only once this sheet has finished sliding away: the preview is another
/// sheet, and two stacked on one frame is the collision the inbox exists to prevent.
///
/// TRANSLATION NOTE: iOS pastes through the system `PasteButton`, because reading the
/// pasteboard from an ordinary button raises an "allow paste" alert. Android has no such alert
/// for a foreground read, so the clipboard is read on the Paste tap and never before.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentRoutineSheet(onRoutine: (AgentRoutine.Reading) -> Unit, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var copied by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AgentRoutine.Failure?>(null) }
    /// A routine read and the sheet on its way out: a second Paste tap during the slide would
    /// queue the same routine twice.
    var leaving by remember { mutableStateOf(false) }

    fun paste() {
        if (leaving) return
        when (val outcome = AgentRoutine.read(clipboardText(context))) {
            is AgentRoutine.Outcome.Success -> {
                failure = null
                leaving = true
                scope.launch { sheetState.hide() }.invokeOnCompletion { onRoutine(outcome.reading) }
            }
            is AgentRoutine.Outcome.Failed -> failure = outcome.failure
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding)
                .padding(bottom = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                tr("Create with AI"),
                style = MaterialTheme.typography.titleLarge,
                color = palette.inkPrimary,
            )
            Text(
                tr("Describe your routine to ChatGPT, Claude or any AI chat in your own words. It writes the routine in a form Get a Grip can read."),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary,
            )

            // 1 · Instructions out
            StepCard(
                title = tr("1 · GIVE THE AI THE INSTRUCTIONS"),
                body = tr("Paste them into a new chat. The AI asks how you want to train, then writes the routine."),
            ) {
                PrimaryButton(
                    title = if (copied) tr("Copied") else tr("Copy instructions"),
                    icon = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (copyInstructions(context)) {
                        copied = true
                        // The haptic names its cause: the copy LANDED (clipboard writes can fail).
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                }
                // Straight into an AI app's share target, for anyone whose assistant takes text
                // that way. The whole row is the target, not just the label.
                val interaction = remember { MutableInteractionSource() }
                TextButton(
                    onClick = { shareInstructions(context) },
                    interactionSource = interaction,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.graphite),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .pressFeedback(interaction, scales = false),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(tr("Share to an app"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }

            // 2 · Routine in
            StepCard(
                title = tr("2 · PASTE THE ROUTINE"),
                body = tr("When the AI has written the code block, copy it and paste it here. You'll see the routine before it's saved."),
            ) {
                PrimaryButton(
                    title = tr("Paste the routine"),
                    icon = Icons.Outlined.ContentPaste,
                    enabled = !leaving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::paste,
                )
                AnimatedVisibility(
                    visible = failure != null,
                    enter = fadeIn(Motion.state(reduceMotion)) + expandVertically(Motion.state(reduceMotion)),
                    exit = fadeOut(Motion.state(reduceMotion)) + shrinkVertically(Motion.state(reduceMotion)),
                ) {
                    // Polite live region: TalkBack reads why the paste failed without moving focus.
                    Text(
                        failure?.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = palette.alarm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, content: @Composable () -> Unit) {
    val palette = LocalGripPalette.current
    InstrumentSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CapsLabel(title)
            Text(body, style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
            content()
        }
    }
}

/// Every item joined, as iOS joins the pasted strings: a clip of several texts is still one
/// reply. Read only on the Paste tap.
private fun clipboardText(context: Context): String = runCatching {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
    (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context).toString() }
}.getOrDefault("")

private fun copyInstructions(context: Context): Boolean = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(run.nuri.getagrip.R.string.app_name), AgentRoutine.instructions))
    true
}.getOrDefault(false)

private fun shareInstructions(context: Context) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, AgentRoutine.instructions)
        }
        context.startActivity(Intent.createChooser(send, null))
    }
}
