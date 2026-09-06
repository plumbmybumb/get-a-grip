// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// The visible capsule stays 40 dp so the chip reads as a status pill rather than a button;
/// the CLICKABLE box around it is 44 dp, the house hit-target floor. That split is the
/// point: shrinking the target to the drawing is the failure the rule exists to prevent,
/// and growing the drawing to the target would make a status line look like a control.
private val CHIP_HEIGHT = 40.dp
private val CHIP_HIT_HEIGHT = 44.dp
private val DOT = 8.dp

/// The gauge's status, as a single tonal pill: dot + name + battery.
///
/// Tapping connects, or — once connected — refreshes the battery, which is the only fact on
/// it that goes stale while nothing else changes.
///
/// TRANSLATION NOTE: iOS builds this as a Button whose glass capsule lives INSIDE the label,
/// because glass wrapped around a container swallows the button's touches. Compose has no
/// such trap, so the outer box carries the click and the semantics while an inert `Surface`
/// inside it carries the look.
@Composable
fun DeviceChip(modifier: Modifier = Modifier) {
    val device = LocalDeviceStore.current
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }

    val connected = device.state.isConnected
    val title = when {
        device.isMock && connected -> tr("Demo device")
        // Fall back to the selected KIND's name, not a hardcoded "Progressor" — a WH-C06
        // that advertises namelessly must not be labelled as a Tindeq.
        connected -> device.deviceName ?: device.gaugeKind.displayName
        else -> device.state.label
    }
    val fraction = device.batteryFraction
    val batterySuffix = fraction?.let { L10n.tr(". Battery %d percent", BatteryDisplay.percentage(it)) } ?: ""
    val action = if (connected) tr("Refresh battery") else tr("Connect")

    Box(
        modifier
            .height(CHIP_HIT_HEIGHT)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button) {
                if (connected) device.readBattery() else device.connect()
            }
            // ONE spoken node. The battery fact travels in the label rather than on the
            // glyph: an explicit description on the outer node replaces every synthesized
            // child label rather than merging with them, so a description left on the icon
            // would never reach TalkBack at all.
            .semantics(mergeDescendants = true) {
                contentDescription = L10n.tr("Gauge: %s%s. %s", title, batterySuffix, action)
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = palette.card,
            contentColor = palette.inkPrimary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .height(CHIP_HEIGHT)
                .pressFeedback(interactionSource),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dot = dotColour(
                    state = device.state,
                    isMock = device.isMock,
                    bleu = palette.bleu,
                    armed = palette.armed,
                    alarm = palette.alarm,
                    idle = palette.inkTertiary,
                )
                Box(
                    Modifier
                        .size(DOT)
                        .clip(CircleShape)
                        // Dimmed while busy: scanning and connecting are states in motion,
                        // and the dot says so without adding an animation to a screen that
                        // already has a live graph on it.
                        .background(dot.copy(alpha = if (device.state.isBusy) 0.45f else 1f)),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
                if (fraction != null) {
                    Icon(
                        batteryIcon(fraction),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        // Alarm red is RESERVED for attention, and a gauge about to die
                        // mid-session is exactly that.
                        tint = if (fraction < 0.15) palette.alarm else palette.inkTertiary,
                    )
                }
            }
        }
    }
}

/// Bleu is the LIVE signal, amber means waiting on something, alarm red is a link the app
/// cannot open without the person doing something. Demo mode is amber, never bleu: a
/// synthetic number that looks like a measurement is worse than no number.
private fun dotColour(
    state: ProgressorConnectionState,
    isMock: Boolean,
    bleu: Color,
    armed: Color,
    alarm: Color,
    idle: Color,
): Color = when (state) {
    is ProgressorConnectionState.Connected -> if (isMock) armed else bleu
    is ProgressorConnectionState.Scanning, is ProgressorConnectionState.Connecting -> armed
    is ProgressorConnectionState.Unauthorized,
    is ProgressorConnectionState.Unsupported,
    is ProgressorConnectionState.BluetoothOff,
    -> alarm
    else -> idle
}

private fun batteryIcon(fraction: Double): ImageVector = when {
    fraction < 0.15 -> Icons.Filled.Battery0Bar
    fraction < 0.4 -> Icons.Filled.Battery2Bar
    fraction < 0.65 -> Icons.Filled.Battery4Bar
    fraction < 0.9 -> Icons.Filled.Battery6Bar
    else -> Icons.Filled.BatteryFull
}
