// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Loaded taring is useful for a static sling or mounted block, so the control confirms
/// instead of silently refusing a meaningful reading. The phase guard — not the load —
/// keeps taring out of a live rep; confirmation is the warning that prevents an allowed
/// phase from zeroing a load the climber did not mean to discard.
struct TareButton: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    var session: RunnerSession
    /// Inside the dock the button gives up its own glass — see `runnerActionSurface`.
    var docked = false

    @State private var promptedKg = 0.0
    @State private var promptedConnectionEpoch: UInt64 = 0
    @State private var showingConfirmation = false
    /// The re-ask, held so it can be cancelled — see `confirmTare`'s `.reask`.
    @State private var reaskTask: Task<Void, Never>?

    var body: some View {
        let enabled = canTareNow
        Button {
            // Re-check on touch-up against the live stores. A pull that starts after an
            // unloaded touch-down must not slip through an enabled frame and zero load.
            guard device.state.isConnected else { return }
            switch tapDecision {
            case .blocked:
                return
            case .wakeStream:
                // Not a tare, and `wakeStream()` cannot become one. See
                // `TareTapDecision.wakeStream`: with no live samples the load is unknown,
                // and the frozen reading says 0 kg however loaded the gauge actually is.
                session.wakeStream()
            case .confirm, .tare:
                // Confirm the rendered "live" against the exact clock before anything
                // irreversible — `TarePolicy.isSafeToTareNow` can only downgrade to a
                // wake, and uses the bound the button's mode was drawn from.
                guard TarePolicy.isSafeToTareNow(
                    sampleAge: device.secondsSinceLastSample(),
                    maxAgeSeconds: device.tareReadingMaxAge) else {
                    session.wakeStream()
                    return
                }
                if TarePolicy.shouldConfirm(readingKg: device.currentKg) {
                    promptTare()
                } else {
                    session.tare()
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: tapDecision == .wakeStream
                      ? "arrow.clockwise" : "arrow.counterclockwise")
                // The label says what the tap will actually DO — "Wake" when the stream
                // is dead, the phase's reason while disabled (`TarePolicy.disabledLabel`).
                Text(tapDecision == .wakeStream
                     ? String(localized: "Wake")
                     : (TarePolicy.disabledLabel(for: session.snapshot.phase) ?? String(localized: "Tare")))
            }
            .font(.system(.subheadline, weight: .semibold))
            .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
            .runnerActionSurface(docked: docked)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .disabled(!enabled)
        .onDisappear { reaskTask?.cancel() }
        .foregroundStyle(enabled ? Ink.primary : Ink.tertiary.opacity(0.5))
        .accessibilityHint(tareDisabledReason
                           ?? (tapDecision == .wakeStream
                               ? String(localized: "Restart the reading. The gauge is connected but not sending.")
                               : String(localized: "Zero the gauge.")))
        .alert("Zero the gauge?", isPresented: $showingConfirmation) {
            Button("Zero it", role: .destructive) { confirmTare() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(String(localized: "There's \(weightUnit.number(promptedKg)) \(weightUnit.symbol) on the gauge. Zero it?"))
        }
    }

    private var tapDecision: TareTapDecision {
        // `device.isLoadedForTare`, not `device.currentKg`: no dependency on a value
        // moving at sample rate — see `DeviceStore.currentKg`.
        TarePolicy.tapDecision(phase: session.snapshot.phase,
                               isReadingLive: device.isReadingLive,
                               isLoadedForTare: device.isLoadedForTare)
    }

    /// Enabled for the WAKE even in a phase that forbids taring — waking never zeroes
    /// anything, and a dead stream mid-pull is when you most need it back.
    private var canTareNow: Bool { tapDecision != .blocked }

    private var tareDisabledReason: String? {
        tapDecision == .blocked
            ? TarePolicy.disabledReason(for: session.snapshot.phase)
            : nil
    }

    private func promptTare() {
        promptedKg = device.currentKg
        promptedConnectionEpoch = device.connectionEpoch
        showingConfirmation = true
    }

    private func confirmTare() {
        switch TarePolicy.confirmationDecision(
            promptedKg: promptedKg,
            currentKg: device.currentKg,
            promptedEpoch: promptedConnectionEpoch,
            currentEpoch: device.connectionEpoch,
            isConnected: device.state.isConnected,
            sampleAge: device.secondsSinceLastSample(),
            phase: session.snapshot.phase,
            maxAgeSeconds: device.tareReadingMaxAge
        ) {
        case .reject:
            // Silent: a reject means the phase, link or gauge changed, and the screen
            // behind the alert already says so.
            return
        case .reask:
            // The quoted number is no longer safe to authorize. Re-arm with the fresh
            // SIGNED reading on the next runloop pass: setting `showingConfirmation`
            // synchronously is swallowed by the alert's own dismissal.
            //
            // TRACKED, guarded and cancelled on the way out, as in `MaxTareButton`: the
            // phase and link can move during the yield, so the reading is re-read AFTER
            // it, and leaving the screen cancels it.
            reaskTask?.cancel()
            reaskTask = Task { @MainActor in
                await Task.yield()
                guard !Task.isCancelled,
                      device.state.isConnected,
                      TarePolicy.phaseAllowsTare(session.snapshot.phase) else { return }
                promptTare()
            }
        case .tare:
            session.tare()
        }
    }
}
