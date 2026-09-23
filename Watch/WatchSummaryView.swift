// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import WatchKit

/// What the session came to, and the one decision that matters on a wrist: keep it or
/// not. Save writes through `SessionLedger` — the phone's own write path — so the log
/// lands in History on every device the moment it syncs, indistinguishable from one
/// the phone recorded. Grading (RPE, finger strain) stays on the phone, where History
/// already offers it after the fact.
struct WatchSummaryView: View {
    let session: RunnerSession
    let template: SessionTemplate
    var onDone: () -> Void

    @Environment(SessionLedger.self) private var ledger
    @State private var saveFailed = false
    /// Set for the length of a save, so a second tap — easy with a wet fingertip on a
    /// small screen — cannot write the session twice. The phone's summary has the same
    /// guard (`saved`).
    @State private var saving = false

    private var reps: [RepSummary] { session.runner.results }
    private var completed: Int { reps.filter { $0.outcome == .completed }.count }
    private var planned: Int { session.runner.plannedRepCount }
    private var heldSeconds: Double { reps.reduce(0) { $0 + $1.heldSeconds } }
    private var peakKg: Double { reps.map(\.peakKg).max() ?? 0 }

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                Text(session.runner.didAnyWork ? "Session done" : "Nothing recorded")
                    .font(.headline)
                stat(String(localized: "\(completed) of \(planned)"), String(localized: "pulls"))
                stat(heldText, String(localized: "on the edge"))
                if !session.timerOnly, peakKg > 0 {
                    stat(WeightUnit.kg.text(peakKg), String(localized: "peak"))
                }
                if session.runner.didAnyWork {
                    Button {
                        save()
                    } label: {
                        Text("Save").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(StatusTint.engaged)
                    .disabled(saving)
                    .accessibilityIdentifier("watch.save")
                    // A HOLD, like the phone's: a single tap sat one row under Save, and
                    // a bump there threw away a whole session with no way back.
                    WatchHoldToDiscardButton { onDone() }
                        .disabled(saving)
                } else {
                    Button {
                        onDone()
                    } label: {
                        Text("Done").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                if saveFailed {
                    Text(ledger.saveError ?? String(localized: "Couldn't save this workout."))
                        .font(.caption2)
                        .foregroundStyle(StatusTint.alarm)
                        .multilineTextAlignment(.center)
                }
            }
        }
    }

    private func stat(_ value: String, _ caption: String) -> some View {
        VStack(spacing: 0) {
            Text(value)
                .font(.title3.weight(.semibold))
                .monospacedDigit()
            Text(caption)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var heldText: String {
        let whole = Int(heldSeconds.rounded())
        return whole >= 60 ? String(localized: "\(whole / 60) min \(whole % 60) s")
                           : String(localized: "\(whole) s")
    }

    private func save() {
        guard !saving else { return }
        saving = true
        let saved = ledger.recordSession(plan: session.plan, template: template, reps: reps,
                                         startedAt: session.startedAt,
                                         finishedAt: session.finishedAt ?? .now,
                                         rpe: nil)
        if saved != nil {
            // `saving` stays set: the screen is leaving, and a tap during the pop must
            // not find an enabled Save.
            onDone()
        } else {
            saving = false
            saveFailed = true
        }
    }
}

/// Discarding takes a deliberate HOLD — the phone's `HoldToDiscardButton`, sized for a
/// wrist, on the same `HoldToConfirm`: same 0.9 s, same "cannot be undone" vocabulary, and
/// the same escape — lifting or sliding off early cancels for free, and any drift lets the
/// summary scroll instead.
private struct WatchHoldToDiscardButton: View {
    var action: () -> Void

    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        HoldToConfirm(cancel: .drift(10),
                      accessibilityLabel: "Discard this session",
                      accessibilityHint: "Press and hold. Nothing is saved.",
                      action: {
                          WKInterfaceDevice.current().play(.failure)
                          action()
                      }) { isHolding, progress in
            ZStack {
                // Both labels reserve their width, so the button does not resize mid-hold.
                Text("Keep holding…").hidden().accessibilityHidden(true)
                Text("Hold to discard").hidden().accessibilityHidden(true)
                Text(isHolding ? "Keep holding…" : "Hold to discard")
                    .foregroundStyle(StatusTint.alarm)
            }
            .font(.body.weight(.semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background {
                HoldFill(progress: progress, tint: StatusTint.alarm, track: 0.16, fill: 0.4)
            }
            .opacity(isEnabled ? 1 : 0.4)
        }
    }
}
