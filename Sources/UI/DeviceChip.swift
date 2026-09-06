// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The gauge's status, as a single glass pill: dot + name + battery.
///
/// Tapping connects (or reconnects). It is a Button whose whole visual capsule is
/// the hit target — glass INSIDE the label, then `.contentShape(.capsule)`, because
/// glass wrapped around a container swallows the button's touches and padding alone
/// contributes nothing to SwiftUI's default hit area.
struct DeviceChip: View {
    @Environment(DeviceStore.self) private var device

    var body: some View {
        Button {
            if device.state.isConnected { device.readBattery() } else { device.connect() }
        } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(dotColor)
                    .frame(width: 8, height: 8)
                    .opacity(device.state.isBusy ? 0.45 : 1)

                Text(title)
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.primary)

                if let fraction = device.batteryFraction {
                    // No `.accessibilityLabel` here any more: an explicit label on the
                    // enclosing Button REPLACES every synthesized child label rather than
                    // merging with them, so this one never reached VoiceOver at all —
                    // tapping the chip to refresh battery, its documented purpose while
                    // connected, announced no result. The fact now travels in
                    // `batterySuffix`, folded into the Button's own label below.
                    Image(systemName: batterySymbol(fraction))
                        .font(.system(.footnote))
                        .foregroundStyle(fraction < 0.15 ? Accent.alarm : Ink.tertiary)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 40)
            .glassEffect(.regular.interactive(), in: .capsule)
            // Keep the visible capsule at 40 pt while the label's hit area reaches
            // the 44 pt house floor.
            .padding(.vertical, 2)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "Gauge: \(title)\(batterySuffix). \(device.state.isConnected ? String(localized: "Refresh battery") : String(localized: "Connect"))"))
    }

    /// The battery fact, folded into the outer label rather than left on the glyph — see
    /// the comment where the glyph is built.
    private var batterySuffix: String {
        guard let fraction = device.batteryFraction else { return "" }
        return String(localized: ". Battery \(BatteryDisplay.percentage(fraction)) percent")
    }

    private var title: String {
        if device.isMock && device.state.isConnected { return String(localized: "Demo device") }
        // Fall back to the selected KIND's name, not a hardcoded "Progressor" — a
        // WH-C06 that advertises namelessly must not be labelled as a Tindeq.
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
