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
                // The rendered decision said the reading was live; confirm that against
                // the exact clock before doing anything irreversible. See
                // `TarePolicy.isSafeToTareNow` — this can only downgrade to a wake.
                // Same bound the button's own mode was drawn from
                // (`device.tareReadingMaxAge`), so the tap can never disagree with what
                // it was shown.
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
                // is dead, and the phase's reason while disabled (see
                // `TarePolicy.disabledLabel`). A button reading "Tare" that restarts the
                // stream instead would be lying about itself.
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
        // `device.isLoadedForTare`, not `device.currentKg` — the coarse, change-guarded
        // flag, so this computed property (read three times in `body`) does not register
        // a dependency on a value moving at sample rate. See its doc comment on
        // `DeviceStore` and `TarePolicy.tapDecision`.
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
            // Deliberately silent. A reject means the phase moved into a pull, the link
            // changed, or the gauge went away — and in every one of those cases the
            // screen behind the alert has already changed to say so, including the Tare
            // button's own label. A second alert explaining why the first one did
            // nothing would be noise stacked on noise.
            return
        case .reask:
            // The quoted number is no longer safe to authorize. Re-arm with the fresh
            // SIGNED reading on the next runloop pass: this button is inside the alert
            // that is dismissing right now, and setting `showingConfirmation` back to
            // true synchronously is swallowed by that dismissal.
            //
            // TRACKED, guarded and cancelled on the way out — the same treatment
            // `MaxTareButton` gives its own re-ask. A yield is a suspension, so the
            // phase can move into a pull and the link can go away before it resumes,
            // and the reading is re-read AFTER the wait rather than quoted from before
            // it. Leaving the screen cancels it, so a dismissed runner has nothing
            // waiting to raise an alert.
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
