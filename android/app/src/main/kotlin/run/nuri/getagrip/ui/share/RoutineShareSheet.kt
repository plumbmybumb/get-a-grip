// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.ui.components.EdgeMark
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.tint
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// A frozen share request, like `ShareCalendarRequest`: the deck can keep moving and the sheet
/// observes NO store, so the code and the name beside it describe one thing.
data class RoutineShareRequest(
    val name: String,
    val metaLine: String,
    val signatureFingers: FingerSet?,
    /// The card's own rung colour: bleu means "nothing resolves", so a red max-day card must not
    /// turn bleu here.
    val peakIntensity: Double?,
    val url: String,
)

/// The routine as a QR code. The code IS the routine — no server, no account, no link to rot —
/// and percentage targets resolve against THEIR maxes.
///
/// TRANSLATION NOTE: iOS shares a rendered PNG via `ShareLink` (AirDrop between phones in a
/// room). Android shares the **LINK** (`ACTION_SEND`, `text/plain`): pasted into any messenger
/// it opens the app via the intent filter, where a PNG of a QR would need a second phone. The
/// QR stays for the in-the-room case. A routine too large for a code (4 KB format vs a level-M
/// QR's capacity) still shares as a link.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineShareSheet(request: RoutineShareRequest, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    var copied by remember { mutableStateOf(false) }
    // Keyed to the link, so a second presentation never opens on a stale "Link copied".
    LaunchedEffect(request.url) { copied = false }

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
                tr("Share routine"),
                style = MaterialTheme.typography.titleLarge,
                color = palette.inkPrimary,
            )

            // The card's own anatomy (mark, name, plan line), so the sheet reads as the card it came
            // from. One TalkBack stop; read outside the non-composable semantics lambda.
            val spoken = tr("%s. %s", request.name, request.metaLine)
            Column(
                Modifier.clearAndSetSemantics {
                    contentDescription = spoken
                },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EdgeMark(
                        fingers = request.signatureFingers ?: FingerSet.four,
                        rungTint = PlanMath.IntensityBand.band(request.peakIntensity).tint(palette),
                    )
                    Text(
                        request.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                        maxLines = 2,
                    )
                }
                Text(
                    request.metaLine,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = palette.inkSecondary,
                )
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCodeView(payload = request.url)
            }

            Text(
                tr("To add %s, scan with Camera on iPhone. On Android, open Get a Grip and choose Scan a routine in the Today menu.", request.name),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
            )

            // Two equal doors: the code for a phone in the room, the link for one that is not. Which you
            // want depends on where the other person is, so neither is filled.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    title = if (copied) tr("Link copied") else tr("Copy link"),
                    icon = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (copyLink(context, request.name, request.url)) {
                        copied = true
                        // The haptic names its cause: the copy LANDED. Clipboard writes can fail (device policy,
                        // stripped ROM), and a buzz over an unchanged clipboard is a small lie.
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                }
                SecondaryButton(
                    title = tr("Share"),
                    icon = Icons.Outlined.Share,
                    modifier = Modifier.weight(1f),
                ) {
                    shareLink(context, request.name, request.url)
                }
            }

            Spacer(Modifier.padding(bottom = 4.dp))
        }
    }
}

/// The link on the clipboard, labelled with the routine's name, since a clipboard manager
/// shows the label and "getagrip://routine#H4sIA…" is unrecognisable.
///
/// Android 13+ confirms copies itself; the in-app receipt stays because OEM skins vary and the
/// button state is what a screen reader hears.
private fun copyLink(context: Context, name: String, url: String): Boolean = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(name, url))
    true
}.getOrDefault(false)

/// `ACTION_SEND` with `text/plain` — a LINK, not an image (see the header). `EXTRA_SUBJECT`
/// titles emails and some messengers, or the routine arrives as a bare scheme URL.
private fun shareLink(context: Context, name: String, url: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, L10n.tr("Get a Grip — %s", name))
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(send, null))
    }
}
