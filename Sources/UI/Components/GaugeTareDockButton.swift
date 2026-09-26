// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Tare (or Wake) as a dock action, for a measurement screen's glass dock: the max test's
/// dock shape, with its `TarePolicy` rules. A meaningful load asks first with the actual
/// reading, and the answer is revalidated against the connection epoch and the load
/// before it is written. A stale reading wakes the stream instead.
///
/// Its own view, so the reading-liveness it reads (`isReadingLive`, sample age) rebuilds
/// only the button.
struct GaugeTareDockButton: View {
    /// False while a measurement runs: taring under a pull corrupts every reading after it.
    var enabled = true
    var disabledReason: String? = nil

    @Environment(DeviceStore.self) private var device
    @Environment(\.weightUnit) private var weightUnit
    @State private var showingConfirmation = false
    @State private var promptedKg = 0.0
    @State private var promptedEpoch: UInt64 = 0
    @State private var tareTick = 0

    var body: some View {
        DockButton(device.isReadingLive ? String(localized: "Tare") : String(localized: "Wake"),
                   systemImage: device.isReadingLive ? "arrow.counterclockwise" : "arrow.clockwise",
                   enabled: enabled && device.state.isConnected,
                   disabledReason: disabledReason) { requestTare() }
            .sensoryFeedback(.impact(weight: .medium, intensity: 0.7), trigger: tareTick)
            .alert("Tare under load?", isPresented: $showingConfirmation) {
                Button("Tare", role: .destructive) { confirmTare() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(String(localized: "There's \(weightUnit.number(promptedKg)) \(weightUnit.symbol) on the gauge. Taring now counts it as zero."))
            }
    }

    private func requestTare() {
        guard enabled, device.state.isConnected else { return }
        guard device.isReadingLive,
              TarePolicy.isSafeToTareNow(sampleAge: device.secondsSinceLastSample(),
                                        maxAgeSeconds: device.tareReadingMaxAge) else {
            device.startStreaming(cause: .manualWake)
            return
        }
        if TarePolicy.shouldConfirm(readingKg: device.currentKg) { prompt() }
        else { device.tare(); tareTick += 1 }
    }

    private func prompt() {
        promptedKg = device.currentKg
        promptedEpoch = device.connectionEpoch
        showingConfirmation = true
    }

    private func confirmTare() {
        guard enabled else { return }
        switch TarePolicy.confirmationDecision(
            promptedKg: promptedKg, currentKg: device.currentKg,
            promptedEpoch: promptedEpoch, currentEpoch: device.connectionEpoch,
            isConnected: device.state.isConnected, sampleAge: device.secondsSinceLastSample(),
            phase: .idle, maxAgeSeconds: device.tareReadingMaxAge
        ) {
        case .reject: return
        case .tare: device.tare(); tareTick += 1
        case .reask:
            Task { @MainActor in
                await Task.yield()
                guard enabled, device.state.isConnected else { return }
                prompt()
            }
        }
    }
}
