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
/// wrist. Same 0.9 s, same "cannot be undone" vocabulary, and the same escape: lifting or
/// sliding off early cancels for free. VoiceOver cannot express a hold, so an
/// accessibility activation discards outright; the gesture guards a thumb, it is not the
/// safeguard itself.
private struct WatchHoldToDiscardButton: View {
    var action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isEnabled) private var isEnabled
    @State private var progress: Double = 0
    @State private var holdTask: Task<Void, Never>?
    @State private var slidOff = false

    private static let holdSeconds: Double = 0.9
    /// A holding finger is still; a scrolling one moves. The phone's slop.
    private static let holdDriftSlop: CGFloat = 10

    var body: some View {
        ZStack {
            // Both labels reserve their width, so the button does not resize mid-hold.
            Text("Keep holding…").hidden().accessibilityHidden(true)
            Text("Hold to discard").hidden().accessibilityHidden(true)
            Text(holdTask != nil ? "Keep holding…" : "Hold to discard")
                .foregroundStyle(StatusTint.alarm)
        }
        .font(.body.weight(.semibold))
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .frame(maxWidth: .infinity, minHeight: 44)
        .background {
            GeometryReader { geo in
                ZStack {
                    Capsule().fill(StatusTint.alarm.opacity(0.16))
                    Capsule()
                        .fill(StatusTint.alarm.opacity(0.4))
                        .mask(alignment: .leading) {
                            Rectangle()
                                .frame(width: geo.size.width * progress)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                }
            }
        }
        .opacity(isEnabled ? 1 : 0.4)
        .contentShape(.capsule)
        // Simultaneous, so a drag that starts here still scrolls the summary; global
        // space, so the page moving under a parked finger reads as movement and cancels.
        .simultaneousGesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged { value in
                    guard isEnabled, !slidOff else { return }
                    guard abs(value.translation.width) <= Self.holdDriftSlop,
                          abs(value.translation.height) <= Self.holdDriftSlop else {
                        slidOff = true
                        cancelHold()
                        return
                    }
                    beginHold()
                }
                .onEnded { _ in
                    cancelHold()
                    slidOff = false
                }
        )
        .onDisappear { cancelHold() }
        .accessibilityElement()
        .accessibilityLabel("Discard this session")
        .accessibilityHint("Press and hold. Nothing is saved.")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { if isEnabled { action() } }
    }

    private func beginHold() {
        guard holdTask == nil else { return }
        // UNCONDITIONAL, as on the phone: the fill is functional progress for the hold,
        // not decoration, and it must match the task's real sleep.
        withAnimation(.linear(duration: Self.holdSeconds)) { progress = 1 }
        holdTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.holdSeconds))
            guard !Task.isCancelled else { return }
            WKInterfaceDevice.current().play(.failure)
            action()
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holdTask = nil
        withAnimation(Motion.state(reduceMotion)) { progress = 0 }
    }
}
