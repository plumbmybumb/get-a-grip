// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The gauge's status, as a single glass pill: dot + name + battery.
///
/// Tapping connects (or reconnects), or cancels an active broadcast search. Once connected
/// it opens a small menu: refresh the battery, or disconnect (Nuri, 2026-10-04: the chip
/// is where you look for the gauge, so it is where you let it go). The whole capsule is
/// the hit target: glass INSIDE the label, then `.contentShape(.capsule)`.
struct DeviceChip: View {
    @Environment(DeviceStore.self) private var device

    var body: some View {
        if device.state.isConnected && !device.canCancelBroadcastSearch {
            Menu {
                Button(String(localized: "Refresh battery"), systemImage: "arrow.clockwise") {
                    device.readBattery()
                }
                if device.isMock {
                    Button(String(localized: "Leave demo mode"), systemImage: "xmark.circle") {
                        device.useMockDevice(false)
                    }
                } else {
                    Button(String(localized: "Disconnect"), systemImage: "xmark.circle") {
                        device.disconnect()
                    }
                }
            } label: {
                chip
            }
            .buttonStyle(PressFeedbackButtonStyle())
            .accessibilityLabel(String(localized: "Gauge: \(title)\(batterySuffix)"))
        } else {
            Button {
                if device.canCancelBroadcastSearch {
                    device.disconnect()
                } else {
                    device.connect()
                }
            } label: {
                chip
            }
            .buttonStyle(PressFeedbackButtonStyle())
            .accessibilityLabel(String(localized: "Gauge: \(title)\(batterySuffix). \(actionTitle)"))
        }
    }

    private var chip: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(dotColor)
                .frame(width: 8, height: 8)
                .opacity(device.state.isBusy ? 0.45 : 1)

            Text(title)
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Ink.primary)

            if device.canCancelBroadcastSearch {
                Image(systemName: "xmark")
                    .font(.system(.caption, weight: .semibold))
                    .foregroundStyle(Ink.secondary)
                    .accessibilityHidden(true)
            }

            if let fraction = device.batteryFraction {
                // No `.accessibilityLabel` here: an explicit label on the enclosing Button
                // REPLACES synthesized child labels, so this one never reached VoiceOver.
                // The fact travels in `batterySuffix`, folded into the Button's label.
                Image(systemName: batterySymbol(fraction))
                    .font(.system(.footnote))
                    .foregroundStyle(fraction < 0.15 ? Accent.alarm : Ink.tertiary)
            }
        }
        .actionLabelLayout(minHeight: 44)
        .glassEffect(.regular.interactive(), in: .capsule)
        .contentShape(.capsule)
    }

    private var actionTitle: String {
        device.canCancelBroadcastSearch ? String(localized: "Cancel") : String(localized: "Connect")
    }

    /// The battery fact, folded into the outer label — see where the glyph is built.
    private var batterySuffix: String {
        guard let fraction = device.batteryFraction else { return "" }
        return String(localized: ". Battery \(BatteryDisplay.percentage(fraction)) percent")
    }

    private var title: String {
        if device.isMock && device.state.isConnected { return String(localized: "Demo device") }
        // The selected KIND's name, not a hardcoded "Progressor": a nameless WH-C06
        // must not be labelled as a Tindeq.
        if device.state.isConnected { return device.deviceName ?? device.gaugeKind.displayName }
        return device.state.label
    }

    private var dotColor: Color {
        switch device.state {
        case .connected: device.isMock ? StatusTint.armed : StatusTint.engaged
        case .scanning, .connecting: StatusTint.armed
        case .unauthorized, .unsupported, .bluetoothOff: Accent.alarm
        default: Ink.tertiary
        }
    }

    private func batterySymbol(_ fraction: Double) -> String {
        switch fraction {
        case ..<0.15: "battery.0percent"
        case ..<0.4: "battery.25percent"
        case ..<0.65: "battery.50percent"
        case ..<0.9: "battery.75percent"
        default: "battery.100percent"
        }
    }
}
