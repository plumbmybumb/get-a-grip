// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Tare as a dock action, for every measurement screen's glass dock: the live gauge, the
/// max test and the critical force setup. A meaningful load asks first with the actual
/// reading, and the answer is revalidated against the connection epoch and the load
/// before it is written.
///
/// **Wake is a fallback** (`TarePolicy.gaugeMode`): on a screen that keeps its stream
/// alive, silence is re-kicked automatically and the control stays Tare — disabled, with
/// the reason spoken — until the readings return. Wake appears only once the automatic
/// restarts have given up, or on a screen that runs no keeper.
///
/// Its own view, so the reading-liveness it reads (`isReadingLive`, sample age) rebuilds
/// only the button.
struct GaugeTareDockButton: View {
    /// False while a measurement runs: taring under a pull corrupts every reading after it.
    var enabled = true
    var disabledReason: String? = nil
    var fillsRowHeight = false
    /// Runs just before a tare is written — the live gauge drops a pull in progress, which
    /// a new zero would otherwise read as ending.
    var willTare: () -> Void = {}

    @Environment(DeviceStore.self) private var device
    @Environment(\.weightUnit) private var weightUnit
    @State private var showingConfirmation = false
    @State private var promptedKg = 0.0
    @State private var promptedEpoch: UInt64 = 0
    @State private var tareTick = 0

    private var mode: GaugeTareMode {
        TarePolicy.gaugeMode(isStreaming: device.isStreaming,
                             isReadingLive: device.isReadingLive,
                             keepsStreamAlive: device.keepsStreamAlive,
                             rekickExhausted: device.streamRekickExhausted)
    }

    var body: some View {
        let mode = mode
        DockButton(mode.title,
                   systemImage: mode.systemImage,
                   enabled: enabled && device.state.isConnected && mode.isEnabled,
                   disabledReason: enabled ? mode.reason : disabledReason,
                   fillsRowHeight: fillsRowHeight) { requestTare() }
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
        // Re-read at the tap, never the rendered mode: the stream may have gone quiet since.
        let mode = mode
        if mode.isWake {
            device.startStreaming(cause: .manualWake)
            return
        }
        guard mode == .tare,
              TarePolicy.isSafeToTareNow(sampleAge: device.secondsSinceLastSample(),
                                        maxAgeSeconds: device.tareReadingMaxAge) else {
            // Drawn as Tare, stale by the time of the tap: the load is unknown, so never a
            // zero. A keeper is already re-kicking; without one, wake as this always did.
            if !device.keepsStreamAlive { device.startStreaming(cause: .manualWake) }
            return
        }
        if TarePolicy.shouldConfirm(readingKg: device.currentKg) { prompt() }
        else { tare() }
    }

    private func tare() {
        willTare()
        device.tare()
        tareTick += 1
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
        case .tare: tare()
        case .reask:
            Task { @MainActor in
                await Task.yield()
                guard enabled, device.state.isConnected else { return }
                prompt()
            }
        }
    }
}
