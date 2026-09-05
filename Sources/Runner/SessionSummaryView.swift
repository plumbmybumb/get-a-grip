// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// What just happened, and the one question worth asking about it.
///
/// The grade is the point: peak and average kilograms are what the gauge measured,
/// but how hard it *felt* is the thing no sensor knows and the only input a future
/// progression suggestion can use. It is one tap and always skippable — a summary that
/// blocks on a question gets dismissed reflexively, and then the answer is noise.
struct SessionSummaryView: View {
    let template: SessionTemplate
    let plan: SessionPlan
    let reps: [RepSummary]
    let startedAt: Date
    let finishedAt: Date
    let didAnyWork: Bool
    var onDone: () -> Void

    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var grade: RPE?
    @State private var saved = false
    @State private var gradeTick = 0
    /// Candidates already written this summary — their rows flip to a checkmark so a
    /// second tap cannot double-record.
    @State private var recordedMaxIDs: Set<String> = []

    private var completed: [RepSummary] { reps.filter { $0.outcome == .completed } }
    private var heldSeconds: Int { Int(reps.reduce(0.0) { $0 + $1.heldSeconds }.rounded()) }
    private var peakKg: Double { reps.map(\.peakKg).max() ?? 0 }
    /// Time-weighted, like the log's own: a rep that dropped off after a second must
    /// not weigh as much as a full hang.
    private var avgKg: Double {
        let held = reps.reduce(0.0) { $0 + $1.heldSeconds }
        guard held > 0 else { return 0 }
        return reps.reduce(0.0) { $0 + $1.avgKg * $1.heldSeconds } / held
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.spacing) {
                header
                stats
                if didAnyWork { newMaxCard }
                if didAnyWork { gradeCard }
                setBreakdown
                PrimaryGlassButton(title: didAnyWork ? String(localized: "Save and finish") : String(localized: "Finish"),
                                   tint: Accent.graphite) {
                    finish()
                }
                if !didAnyWork {
                    Text("Nothing was held, so there's nothing to log.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                        .frame(maxWidth: .infinity, alignment: .center)
                } else {
                    // **THROW IT AWAY.** A session you were pulled out of halfway is not
                    // training, and logging it drags a bad number through every average
                    // and marks the day done when it was not (Nuri, 2026-08-09: "just in
                    // case you get interrupted").
                    //
                    // A HOLD, and the same 0.9 s hold as ending a session, because it is
                    // the same kind of decision: irreversible, taken with chalk on your
                    // hands, and never something a mis-tap should do. There is no
                    // confirmation dialog for the same reason there is none on End.
                    HoldToDiscardButton { onDone() }
                    Text("Nothing is saved. The session is gone.")
                        .font(.system(.caption))
                        .foregroundStyle(Ink.tertiary)
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            }
            .padding(.horizontal, Metrics.hPadding)
            .padding(.vertical, Metrics.spacing)
            .frame(maxWidth: Metrics.maxContentWidth)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .sensoryFeedback(.selection, trigger: gradeTick)
        .onAppear { maxCandidates = computeMaxCandidates() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            CapsLabel(template.name)
            Text(didAnyWork ? "Session done" : "Session ended")
                .font(.system(.largeTitle, weight: .semibold))
                .foregroundStyle(Ink.primary)
        }
    }

    private var stats: some View {
        HStack(spacing: 10) {
            stat(String(localized: "Pulls"), "\(completed.count)", of: String(localized: "of \(reps.count)"))
            stat(String(localized: "Under tension"), PlanMath.clockText(heldSeconds), of: nil)
            stat(String(localized: "Peak"), peakKg.formatted(.number.precision(.fractionLength(1))), of: String(localized: "kg"))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("""
            \(completed.count) of \(reps.count) pulls completed, \
            \(heldSeconds) seconds under tension, \
            peak \(peakKg.formatted(.number.precision(.fractionLength(1)))) kilograms
            """)
    }

    private func stat(_ title: String, _ value: String, of suffix: String?) -> some View {
        VStack(spacing: 4) {
            CapsLabel(title)
            Text(value)
                .font(.system(.title2, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
            if let suffix {
                Text(suffix).font(.system(.caption)).foregroundStyle(Ink.tertiary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(.regularMaterial,
                    in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
    }

    // MARK: - New maxes

    /// A pull inside a session that beat a grip's working max, offered as the new max
    /// per HAND (Nuri, 2026-08-10: "it should offer to update your max in that grip
    /// type, depending on the side you're pulling on"). One tap; `measured` provenance
    /// — the gauge genuinely saw it. Deliberately does NOT mark a benchmark day: this
    /// session already logged, and settling the day would cancel the evening ritual.
    private struct MaxCandidate: Identifiable {
        let grip: GripSpec
        let side: Side
        let kg: Double
        /// nil = the grip had no max at all — a first number, not a beat.
        let previous: Double?
        var id: String { grip.key + "·" + side.rawValue }
    }

    /// FROZEN at first appearance — recording a candidate updates the max table, and a
    /// live computation would then drop the row it should be flipping to a checkmark.
    @State private var maxCandidates: [MaxCandidate] = []

    /// COMPLETED reps only, and only real pulls: a timer-only session records 0 kg
    /// peaks, and offering "0.0 kg — new max!" would be the app talking nonsense.
    private func computeMaxCandidates() -> [MaxCandidate] {
        var best: [String: MaxCandidate] = [:]
        for rep in completed where rep.peakKg > 1 {
            let key = rep.grip.key + "·" + rep.side.rawValue
            if let held = best[key], held.kg >= rep.peakKg { continue }
            best[key] = MaxCandidate(grip: rep.grip, side: rep.side, kg: rep.peakKg,
                                     previous: templates.maxTable.max(grip: rep.grip.key,
                                                                      side: rep.side))
        }
        return best.values
            .filter { candidate in candidate.previous.map { candidate.kg > $0 } ?? true }
            .sorted { $0.kg > $1.kg }
    }

    @ViewBuilder
    private var newMaxCard: some View {
        let candidates = maxCandidates.filter { !recordedMaxIDs.contains($0.id) }
        let recorded = maxCandidates.filter { recordedMaxIDs.contains($0.id) }
        if !candidates.isEmpty || !recorded.isEmpty {
            MaterialCard {
                VStack(alignment: .leading, spacing: 12) {
                    CapsLabel(recorded.isEmpty ? String(localized: "Harder than your max") : String(localized: "New maxes"))
                    ForEach(candidates) { candidate in maxRow(candidate, saved: false) }
                    ForEach(recorded) { candidate in maxRow(candidate, saved: true) }
                    Text("Your percent targets follow whatever you save here.")
                        .font(.system(.caption))
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func maxRow(_ candidate: MaxCandidate, saved: Bool) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(sideLine(candidate))
                    .font(.system(.subheadline, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                Text(candidate.previous.map {
                    String(localized: "beats your \($0.formatted(.number.precision(.fractionLength(1)))) kg")
                } ?? String(localized: "first max on this grip"))
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
            }
            Spacer(minLength: 8)
            if saved {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(.title3))
                    .foregroundStyle(Accent.graphite)
                    .accessibilityLabel("Saved")
            } else {
                Button {
                    // OPTIMISTIC. `recordMax` saves and re-derives synchronously on the
                    // main actor, so doing it first meant the checkmark — the tap's only
                    // acknowledgement — could not draw until the write had finished.
                    // Flipping the order costs nothing: a rolled-back save puts the row
                    // straight back (audit, 2026-08-11).
                    withAnimation(Motion.state(reduceMotion)) {
                        _ = recordedMaxIDs.insert(candidate.id)
                    }
                    gradeTick += 1
                    guard templates.recordMax(candidate.kg, for: candidate.grip,
                                              source: .measured, side: candidate.side,
                                              marksBenchmarkDay: false) else {
                        withAnimation(Motion.state(reduceMotion)) {
                            recordedMaxIDs.remove(candidate.id)
                        }
                        return
                    }
                } label: {
                    Text("Save as max")
                        .font(.system(.footnote, weight: .semibold))
                        .foregroundStyle(Accent.graphite)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 44)
                        .overlay(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
                        .contentShape(.capsule)
                }
                .buttonStyle(PressFeedbackButtonStyle())
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func sideLine(_ candidate: MaxCandidate) -> String {
        let kg = candidate.kg.formatted(.number.precision(.fractionLength(1)))
        if candidate.side == .both {
            return String(localized: "\(candidate.grip.shortName) · \(kg) kg")
        }
        return String(localized: "\(candidate.side.name) · \(candidate.grip.shortName) · \(kg) kg")
    }

    private var gradeCard: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 10) {
                CapsLabel(String(localized: "How hard was that?"))
                // A wrapping grid rather than a row: "Comfortable" and "All I had" do
                // not fit five-across at any accessibility size.
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 8,
                                             alignment: .leading)],
                          alignment: .leading, spacing: 8) {
                    ForEach(RPE.allCases, id: \.self) { level in
                        Chip(title: level.name, isSelected: grade == level) {
                            // Tapping the same grade clears it — the answer stays
                            // genuinely optional after you've given one.
                            grade = (grade == level) ? nil : level
                            gradeTick += 1
                        }
                    }
                }
                Text("Optional. It's what tells you later whether to add load.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
            }
        }
    }

    /// Per set, so a bad set is visible rather than averaged away.
    private var setBreakdown: some View {
        let grouped = groupedReps
        return VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "Sets"))
            ForEach(grouped.order, id: \.self) { index in
                let inSet = grouped.byIndex[index] ?? []
                let done = inSet.filter { $0.outcome == .completed }.count
                HStack(spacing: 10) {
                    if let grip = inSet.first?.grip {
                        FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 8, gap: 3)
                        Text(grip.line)
                            .font(.system(.subheadline))
                            .foregroundStyle(Ink.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Text("\(done)/\(inSet.count)")
                        .font(.system(.subheadline, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(done == inSet.count ? Ink.primary : StatusTint.armed)
                }
                .padding(.vertical, 10)
                .padding(.horizontal, 14)
                .background(.regularMaterial,
                            in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(inSet.first?.grip.spoken ?? String(localized: "Set")): \(done) of \(inSet.count) completed")
            }
        }
    }

    private var groupedReps: (order: [Int], byIndex: [Int: [RepSummary]]) {
        var order: [Int] = []
        var byIndex: [Int: [RepSummary]] = [:]
        for rep in reps {
            if byIndex[rep.setIndex] == nil { order.append(rep.setIndex) }
            byIndex[rep.setIndex, default: []].append(rep)
        }
        return (order, byIndex)
    }

    private func finish() {
        // Guard against a double tap writing two logs — the button is on screen while
        // the save round-trips.
        if didAnyWork, !saved {
            saved = true
            templates.recordSession(plan: plan, template: template, reps: reps,
                                    startedAt: startedAt, finishedAt: finishedAt, rpe: grade)
        }
        onDone()
    }
}


/// Discarding a finished session takes a deliberate HOLD, exactly like ending one.
///
/// Shares `HoldToEndButton`'s shape and its 0.9 s: one gesture vocabulary for "this cannot
/// be undone", learned once. It is quieter than Save — outlined rather than filled — because
/// throwing the session away is the rarer answer and must never be the reflex.
private struct HoldToDiscardButton: View {
    var action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress: Double = 0
    @State private var isHolding = false
    @State private var holdTask: Task<Void, Never>?
    @State private var firedTick = 0
    @State private var slidOff = false

    private static let holdSeconds: Double = 0.9
    private static let slideSlop: CGFloat = 24

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Capsule().fill(Accent.alarm.opacity(0.12))
                // Masked to the button's own capsule, not a second shape at partial
                // width — see `HoldToEndButton`, where a width-constrained `Capsule()`
                // drew its own fully rounded (and oversized) outline instead of the
                // button's.
                Capsule()
                    .fill(Accent.alarm.opacity(0.36))
                    .mask(alignment: .leading) {
                        Rectangle()
                            .frame(width: geo.size.width * progress)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                Text(isHolding ? "Keep holding…" : "Hold to discard")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Accent.alarm)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    // The label REPLACES rather than dissolving through the outgoing one —
                    // see `HoldToEndButton`, where a cross-fade left two strings overlapping
                    // for the whole hold.
                    .contentTransition(.identity)
                    .animation(nil, value: isHolding)
            }
            .contentShape(.capsule)
            // `.simultaneousGesture`, not `.gesture` — this button sits inside the
            // summary's `ScrollView`, and a `DragGesture(minimumDistance: 0)` attached
            // exclusively claims every touch that starts on it, including a scroll that
            // happens to start here (the button legitimately sits below the fold on a
            // session with several new maxes or many sets). `HoldToEndButton` uses the
            // same gesture but lives on a screen with no `ScrollView`, so it never hit
            // this. The house fix for exactly this conflict is already in the codebase —
            // `RepeatingStep` (`ValueRow.swift`) attaches its own hold-and-repeat drag the
            // same way, cancelled once the touch moves past a small slop, "so a scroll
            // starting on the glyph still scrolls" (CLAUDE.md). Letting the ScrollView's
            // own pan recognize ALONGSIDE this one — rather than claiming the touch
            // outright — restores scrolling; the GLOBAL-space drift cancel below is what
            // stops the hold from completing once the scroll takes over. Global, not
            // local: local coordinates scroll WITH the content, so during a page scroll
            // the finger never moves relative to the button and a bounds test cannot
            // see the scroll at all — which would have let a scroll fire an
            // irreversible discard with no undo behind it.
            .simultaneousGesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .onChanged { updateHold(translation: $0.translation) }
                    .onEnded { _ in endHold() }
            )
            // A hold in flight when the view leaves (the summary can be dismissed by
            // its own buttons) must not complete off-screen — `RepeatingStep`'s rule.
            .onDisappear { endHold() }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 48)
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.9), trigger: firedTick)
        .accessibilityElement()
        .accessibilityLabel("Discard this session")
        .accessibilityHint("Press and hold. Nothing is saved.")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { action() }
    }

    /// A holding finger is stationary; a scrolling one moves. 10 pt is the same slop
    /// `RepeatingStep` cancels at, and it is measured in GLOBAL space so a page scroll
    /// — which moves the finger through the window while leaving it parked on the
    /// button's own coordinates — reads as movement here.
    private static let holdDriftSlop: CGFloat = 10

    private func updateHold(translation: CGSize) {
        guard !slidOff else { return }
        guard abs(translation.width) <= Self.holdDriftSlop,
              abs(translation.height) <= Self.holdDriftSlop else {
            slidOff = true
            cancelHold()
            return
        }
        beginHold()
    }

    private func endHold() {
        cancelHold()
        slidOff = false
    }

    private func beginHold() {
        guard holdTask == nil else { return }
        isHolding = true
        // UNCONDITIONAL, deliberately — see `HoldToEndButton.beginHold()`, which makes
        // and documents the same call: this fill is functional progress feedback for
        // the hold, not decorative motion, and it must match `holdTask`'s real sleep.
        withAnimation(.linear(duration: Self.holdSeconds)) { progress = 1 }
        holdTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.holdSeconds))
            guard !Task.isCancelled else { return }
            firedTick += 1
            action()
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holdTask = nil
        isHolding = false
        // `Motion.state(reduceMotion)` already resolves to `Motion.reduced` when the
        // flag is set — see `HoldToEndButton.cancelHold()`, where the identical
        // redundant ternary produced a second, divergent, untokenised reduced-motion
        // curve.
        withAnimation(Motion.state(reduceMotion)) { progress = 0 }
    }
}
