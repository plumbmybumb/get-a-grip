// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.today

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// The routine card's whole menu — raised by the ⋯ button and mirrored by a long press on
/// the card, which is why the expanded flag lives on the card and arrives here as a
/// parameter rather than being owned inside.
///
/// The ORDER is iOS's, verbatim, and it is not arbitrary: the things you do to a routine
/// come first (edit, duplicate, a new one, make it primary), then the alternative way to
/// run it, then sharing, then — behind a divider and one level deeper — the destructive one.
///
/// **"Scan a routine" is Android-only and it sits with sharing, not with creating.** iOS has
/// no such item because the Camera app opens a `getagrip://` code on its own; Android's stock
/// camera behaviour varies by OEM and a Lens overlay is as likely to offer to SEARCH a custom
/// scheme as to open it, so the app carries its own door. It is placed immediately after
/// "Share routine…" because the two are one idea seen from either end — this phone showing a
/// code, this phone reading one — and reading somebody else's plan is not "New routine…".
///
/// **Delete is a SUBMENU, not a flat destructive row.** A live simulator audit landed a tap
/// meant for "Start without a gauge" one row low, on Delete, with only the divider's
/// hairline between them — and the only net was the 10 s Undo, which the same tap-miss then
/// also failed to catch. This is NOT the house's banned confirmation DIALOG (no Yes/No
/// prompt, no tax on the 99 % of taps that mean it): it is ordinary menu navigation one
/// level deeper, so a mis-tap that lands on "Delete routine…" opens a second small menu and
/// does nothing — nobody's data is gone until they deliberately tap the destructive row
/// inside it, at the SAME menu-row hit height as every other item here.
///
/// TRANSLATION NOTE: SwiftUI nests a `Menu` inside a `Menu` and the system draws the second
/// level. Compose's `DropdownMenu` has no submenu, so the same menu SWAPS ITS CONTENT to the
/// single destructive item plus a way back — one level deeper, one deliberate extra tap, the
/// same bargain. The swap resets whenever the menu closes, so the next ⋯ tap never reopens
/// on the delete face.
@Composable
fun TodayMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onNew: () -> Unit,
    onMakePrimary: () -> Unit,
    onStartTimerOnly: () -> Unit,
    onShare: () -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalGripPalette.current
    var showingDelete by remember { mutableStateOf(false) }

    // A menu that closed on its delete face would reopen there — which is exactly the
    // accident the second level exists to prevent.
    LaunchedEffect(expanded) { if (!expanded) showingDelete = false }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = palette.card,
    ) {
        if (showingDelete) {
            Item(tr("Back"), Icons.AutoMirrored.Outlined.ArrowBack) { showingDelete = false }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = palette.inkTertiary.copy(alpha = 0.22f))
            Item(tr("Delete routine"), Icons.Outlined.Delete, tint = palette.alarm) {
                onDismiss()
                onDelete()
            }
            return@DropdownMenu
        }

        Item(tr("Edit routine"), Icons.Outlined.Tune) { onDismiss(); onEdit() }
        Item(tr("Duplicate"), Icons.Outlined.ContentCopy) { onDismiss(); onDuplicate() }
        Item(tr("New routine…"), Icons.Outlined.Add) { onDismiss(); onNew() }
        Item(tr("Make this the one Today opens on"), Icons.Outlined.VerticalAlignTop) {
            onDismiss(); onMakePrimary()
        }
        // The always-available door. The card only offers it in front when there is no gauge
        // connected, but choosing to train unmeasured is legitimate at any time — a weight
        // belt, someone else's board, a session you just don't want logged in kg.
        Item(tr("Start without a gauge"), Icons.Outlined.Timer) { onDismiss(); onStartTimerOnly() }
        Item(tr("Share routine…"), Icons.Outlined.QrCode) { onDismiss(); onShare() }
        Item(tr("Scan a routine"), Icons.Outlined.QrCodeScanner) { onDismiss(); onScan() }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = palette.inkTertiary.copy(alpha = 0.22f))
        Item(tr("Delete routine…"), Icons.Outlined.Delete) { showingDelete = true }
    }
}

@Composable
private fun Item(
    title: String,
    icon: ImageVector,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val ink = tint ?: palette.inkPrimary
    DropdownMenuItem(
        text = { Text(title) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        enabled = enabled,
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = ink,
            leadingIconColor = if (tint != null) ink else palette.inkSecondary,
            disabledTextColor = palette.inkTertiary,
            disabledLeadingIconColor = palette.inkTertiary,
        ),
    )
}
