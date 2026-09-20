// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The live gauge on the wrist: connect, pull, read, let go. Reached from the gauge row
/// on the Today list — the phone got the same door on its Today bar the same day
/// (2026-09-20), because some people use the gauge and no routine at all.
///
/// Opening this IS the deliberate act the one-gauge rule asks for: whoever pressed it
/// owns the gauge, exactly as Start does, and a watch that merely lists its routines
/// still cannot take the gauge from a phone mid-session. The number is read off
/// `WatchForceReadout` at five updates a second, never off the sample stream, so the
/// face never lags (the runner's lesson, 2026-09-19).
struct WatchGaugeView: View {
    @Environment(DeviceStore.self) private var device
    @State private var readout = WatchForceReadout()
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 44

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                hero
                HStack(spacing: 16) {
                    stat(WeightUnit.kg.number(readout.peakKg), String(localized: "peak"))
                    // Inline: `BatteryDisplay` is a phone component, not in this target.
                    stat(device.batteryFraction.map { "\(Int(($0 * 100).rounded()))%" } ?? "—",
                         String(localized: "battery"))
                }
                if device.state.isConnected {
                    Button {
                        if device.isStreaming {
                            device.stopStreaming(cause: .userStopped)
                        } else {
                            device.startStreaming(cause: .manualMeasurement)
                        }
                    } label: {
                        Label(device.isStreaming ? String(localized: "Stop") : String(localized: "Start"),
                              systemImage: device.isStreaming ? "stop.fill" : "play.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(device.isStreaming ? Accent.alarm : StatusTint.engaged)
                    .accessibilityIdentifier("watch.gauge.measure")
                    // Disabled under load rather than confirmed, as the runner's is: a
                    // dialog on the wrist mid-pull is the wrong price, and a loaded tare
                    // corrupts every reading after it.
                    Button {
                        device.tare()
                    } label: {
                        Label("Tare", systemImage: "arrow.counterclockwise")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!device.isReadingLive || device.isLoadedForTare)
                    .accessibilityIdentifier("watch.gauge.tare")
                } else {
                    Button {
                        if device.canCancelBroadcastSearch {
                            device.disconnect()
                        } else if !device.state.isBusy {
                            device.connect()
                        }
                    } label: {
                        Label(connectTitle,
                              systemImage: device.canCancelBroadcastSearch ? "xmark" : "dot.radiowaves.left.and.right")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(StatusTint.engaged)
                    .disabled(device.state.isBusy && !device.canCancelBroadcastSearch)
                    .accessibilityIdentifier("watch.gauge.connect")
                    // Always compiled in, like the phone's: a DEBUG-only demo would leave
                    // anyone without hardware on a screen that never connects.
                    if !device.isMock {
                        Button("Try demo mode") { device.useMockDevice(true) }
                            .buttonStyle(.plain)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                Text(device.state.label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Gauge")
        .onAppear {
            readout.begin(reading: device)
            if device.state.isConnected {
                device.startStreaming(cause: .manualMeasurement)
            } else if !device.state.isBusy {
                device.connect()
            }
        }
        .onChange(of: device.state.isConnected) { _, connected in
            if connected { device.startStreaming(cause: .manualMeasurement) }
        }
        .onDisappear {
            readout.end()
            // Never leave the device streaming behind us: it drains its own battery and
            // keeps the radio busy. The link itself follows the battery rule on background.
            if device.isStreaming { device.stopStreaming(cause: .screenClosed) }
        }
    }

    /// MEASUREMENT, the runner's rule: the load snaps, it never rolls.
    private var hero: some View {
        HStack(alignment: .lastTextBaseline, spacing: 2) {
            Text(WeightUnit.kg.number(readout.kg))
                .font(.system(size: heroSize, weight: .medium, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .contentTransition(.identity)
                .foregroundStyle(device.isStreaming ? StatusTint.engaged : .primary)
            Text(WeightUnit.kg.symbol)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(localized: "\(WeightUnit.kg.number(readout.kg)) kilograms"))
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 1) {
            Text(value)
                .font(.footnote.weight(.semibold))
                .monospacedDigit()
                .contentTransition(.identity)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var connectTitle: String {
        if device.canCancelBroadcastSearch { return String(localized: "Cancel") }
        return device.state.isBusy ? device.state.label : String(localized: "Connect")
    }
}
