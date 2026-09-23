// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The gauge's status, as a single glass pill: dot + name + battery.
///
/// Tapping connects (or reconnects), or cancels an active broadcast search. The whole
/// capsule is the hit target: glass INSIDE the label, then `.contentShape(.capsule)`.
struct DeviceChip: View {
    @Environment(DeviceStore.self) private var device

    var body: some View {
        Button {
            if device.canCancelBroadcastSearch {
                device.disconnect()
            } else if device.state.isConnected {
                device.readBattery()
            } else {
                device.connect()
            }
        } label: {
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
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "Gauge: \(title)\(batterySuffix). \(actionTitle)"))
    }

    private var actionTitle: String {
        if device.canCancelBroadcastSearch { return String(localized: "Cancel") }
        return device.state.isConnected ? String(localized: "Refresh battery") : String(localized: "Connect")
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
