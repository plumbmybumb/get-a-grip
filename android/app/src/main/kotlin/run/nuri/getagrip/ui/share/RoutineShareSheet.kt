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

/// A frozen share request, exactly like `ShareCalendarRequest`. The deck behind the sheet
/// can keep moving — a swipe, an edit landing, a delete — and what is on screen stays the
/// routine whose menu was tapped. The sheet observes NO store for the same reason: the code
/// it draws and the name beside it must describe one thing.
data class RoutineShareRequest(
    val name: String,
    val metaLine: String,
    val signatureFingers: FingerSet?,
    /// Carried so the mark here wears the SAME rung colour as the card it was opened from —
    /// bleu specifically means "nothing resolves", and a max-day routine whose card burns
    /// red must not turn bleu one presentation later.
    val peakIntensity: Double?,
    val url: String,
)

/// The routine as a QR code. The code IS the routine — there is no server, no account and
/// no link that can rot: everything the recipient's app needs rides in the payload, and
/// percentage targets resolve against THEIR maxes, which is the point of prescribing a
/// fraction rather than a kilogram.
///
/// TRANSLATION NOTE: iOS shares a rendered PNG card through `ShareLink`, because the iPhone
/// share sheet is where an image goes and AirDrop is the common hop between two phones in
/// the same room. Android shares the **LINK** instead (`ACTION_SEND`, `text/plain`), which
/// is the honest thing on this platform: a link pasted into any messenger is tappable and
/// opens the app directly through the intent filter, while a PNG of a QR code is something
/// the recipient has to photograph off their own screen with a second phone. The QR above
/// stays, because pointing a camera at a screen IS the in-the-room case. One consequence
/// worth knowing: a routine too large to draw as a code (the format allows 4 KB compressed;
/// a level-M QR holds far less) can still be shared and imported as a link.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineShareSheet(request: RoutineShareRequest, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    var copied by remember { mutableStateOf(false) }
    // The receipt describes ONE link. Nothing can change the payload while this sheet is up
    // (the request is frozen), but keying the reset to it is what stops a second
    // presentation opening on a stale "Link copied".
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

            // The card's own anatomy — mark, name, plan line — so the sheet reads as the
            // card it was opened from rather than as a second description of the same
            // routine. One TalkBack stop, like the import sheet's twin of this header.
            // Read outside the semantics lambda, which is not composable.
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

            // Two doors, side by side and equal: the code is for a phone in the same room,
            // the link is for one that is not. Neither is the primary — which one you want
            // is a fact about where the other person is, not about which action matters
            // more, so neither wears the filled treatment.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    title = if (copied) tr("Link copied") else tr("Copy link"),
                    icon = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (copyLink(context, request.name, request.url)) {
                        copied = true
                        // The haptic names its cause: the copy LANDED. A clipboard write
                        // can fail (a locked device policy, a null service on a stripped
                        // ROM), and a confirm buzz over a clipboard that did not change is
                        // the app telling a small lie about something the user cannot see.
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

/// The link on the clipboard, labelled with the routine's name — the label is what a
/// clipboard manager shows in its history, and "getagrip://routine#H4sIA…" is not something
/// anyone can pick out of a list.
///
/// Android 13 and up shows its own copy confirmation, so the in-app receipt is the second
/// one there. It stays: the system's toast is not guaranteed on every OEM skin, and the
/// button state is also what a screen reader hears.
private fun copyLink(context: Context, name: String, url: String): Boolean = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(name, url))
    true
}.getOrDefault(false)

/// `ACTION_SEND` with `text/plain` — a LINK, not an image. See the header note.
///
/// `EXTRA_SUBJECT` is what an email client puts on the subject line and what some
/// messengers title the share with; without it a routine arrives as a bare scheme URL with
/// nothing saying what it is.
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
