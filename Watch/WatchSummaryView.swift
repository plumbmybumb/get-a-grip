// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

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
                    .accessibilityIdentifier("watch.save")
                    Button(role: .destructive) {
                        onDone()
                    } label: {
                        Text("Discard").frame(maxWidth: .infinity)
                    }
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
        let saved = ledger.recordSession(plan: session.plan, template: template, reps: reps,
                                         startedAt: session.startedAt,
                                         finishedAt: session.finishedAt ?? .now,
                                         rpe: nil)
        if saved != nil {
            onDone()
        } else {
            saveFailed = true
        }
    }
}
