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
    @Environment(\.weightUnit) private var weightUnit
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
    /// Optional selections stay local until the workout is saved.
    @State private var chosenMaxIDs: Set<String> = []
    @State private var showsMaxes = false
    @State private var showsSets = false
    @State private var didLoadCandidates = false

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
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Metrics.spacing) {
                    header
                    stats
                    if didAnyWork { newMaxCard }
                    if didAnyWork { gradeCard }
                    setBreakdown
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.vertical, Metrics.spacing)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            footer
        }
        .sensoryFeedback(.selection, trigger: gradeTick)
        .onAppear {
            guard !didLoadCandidates else { return }
            didLoadCandidates = true
            maxCandidates = SessionMaxCandidate.from(reps: reps, maxes: templates.maxTable)
        }
    }

    private var footer: some View {
        VStack(spacing: 8) {
            if let error = templates.saveError {
                Text(error).font(.footnote).foregroundStyle(StatusTint.armed)
            }
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
                HoldToDiscardButton { onDone() }
            }
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.vertical, 10)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
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
            stat(String(localized: "Peak"), weightUnit.number(peakKg), of: weightUnit.symbol)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("""
            \(completed.count) of \(reps.count) pulls completed, \
            \(heldSeconds) seconds under tension, \
            peak \(weightUnit.number(peakKg)) \(weightUnit.spokenName)
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
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 8)
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
    /// Freeze the candidates so selections and row positions survive view updates.
    @State private var maxCandidates: [SessionMaxCandidate] = []

    @ViewBuilder
    private var newMaxCard: some View {
        if !maxCandidates.isEmpty {
            MaterialCard {
                VStack(alignment: .leading, spacing: 12) {
                    Button {
                        withAnimation(Motion.state(reduceMotion)) { showsMaxes.toggle() }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "chart.line.uptrend.xyaxis")
                            Text("New peaks to review")
                                .font(.system(.subheadline, weight: .semibold))
                            Spacer(minLength: 8)
                            Text("\(maxCandidates.count)").monospacedDigit()
                            Image(systemName: showsMaxes ? "chevron.up" : "chevron.down")
                        }
                        .foregroundStyle(StatusTint.armed)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(.rect)
                    }
                    .buttonStyle(PressFeedbackButtonStyle())
                    .accessibilityIdentifier("summary.peaks")
                    .accessibilityValue(showsMaxes ? String(localized: "Expanded") : String(localized: "Collapsed"))
                    if showsMaxes {
                        Text("Select maximum efforts to use for future targets. Saved with this workout.")
                            .font(.system(.caption)).foregroundStyle(Ink.secondary)
                        VStack(spacing: 0) {
                            ForEach(maxCandidates) { candidate in
                                maxRow(candidate, saved: chosenMaxIDs.contains(candidate.id))
                                if candidate.id != maxCandidates.last?.id {
                                    Divider().overlay(Ink.tertiary.opacity(0.08))
                                }
                            }
                        }
                    }
                }
            }
            .overlay(RoundedRectangle(cornerRadius: Metrics.radiusCard).stroke(StatusTint.armed.opacity(0.25), lineWidth: 1))
        }
    }

    private func maxRow(_ candidate: SessionMaxCandidate, saved: Bool) -> some View {
        Button {
            if saved { chosenMaxIDs.remove(candidate.id) }
            else { chosenMaxIDs.insert(candidate.id) }
            gradeTick += 1
        } label: {
            HStack(spacing: 12) {
                FingerGlyph(fingers: candidate.grip.fingers, position: candidate.grip.position,
                            dot: 8, gap: 3, tint: saved ? StatusTint.armed : Ink.secondary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    ViewThatFits(in: .horizontal) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            peakWeight(candidate)
                            peakHand(candidate.side)
                        }
                        .fixedSize(horizontal: true, vertical: false)
                        VStack(alignment: .leading, spacing: 2) {
                            peakWeight(candidate)
                            peakHand(candidate.side)
                        }
                    }
                    .monospacedDigit()
                    Text(candidate.grip.line)
                        .font(.system(.caption)).foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let previous = candidate.previous {
                        Text(String(localized: "Previous: \(weightUnit.number(previous)) \(weightUnit.symbol)"))
                            .font(.system(.caption2)).foregroundStyle(Ink.tertiary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: saved ? "checkmark.circle.fill" : "circle")
                    .font(.system(.title3))
                    .foregroundStyle(saved ? StatusTint.armed : Ink.tertiary.opacity(0.65))
            }
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityIdentifier("summary.peak.\(candidate.id)")
        .accessibilityLabel(sideLine(candidate))
        .accessibilityValue(saved ? String(localized: "Selected") : String(localized: "Use as max"))
        .accessibilityAddTraits(saved ? .isSelected : [])
    }

    private func peakWeight(_ candidate: SessionMaxCandidate) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(weightUnit.number(candidate.kg))
                .font(.system(.title2, weight: .semibold))
                .foregroundStyle(Ink.primary)
            Text(weightUnit.symbol).font(.system(.caption)).foregroundStyle(Ink.secondary)
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    private func peakHand(_ side: Side) -> some View {
        Text(handLabel(side))
            .font(.system(.caption, weight: .semibold))
            .foregroundStyle(Ink.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func handLabel(_ side: Side) -> String {
        switch side {
        case .left: String(localized: "Left hand")
        case .right: String(localized: "Right hand")
        case .both: String(localized: "Both hands")
        }
    }

    private func sideLine(_ candidate: SessionMaxCandidate) -> String {
        let kg = weightUnit.number(candidate.kg)
        return String(localized: "\(handLabel(candidate.side)) · \(candidate.grip.shortName) · \(kg) \(weightUnit.symbol)")
    }

    private var gradeCard: some View {
        MaterialCard {
            EffortPicker(selection: Binding(get: { grade?.rawValue },
                                             set: { grade = $0.flatMap(RPE.init(rawValue:)) }),
                         labels: RPE.allCases.map(\.name),
                         title: String(localized: "How hard did it feel?"), identifier: "effort.overall")
        }
    }

    /// Per set, so a bad set is visible rather than averaged away.
    private var setBreakdown: some View {
        let grouped = groupedReps
        return VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(Motion.state(reduceMotion)) { showsSets.toggle() }
            } label: {
                HStack {
                    Text("Sets").font(.system(.subheadline, weight: .semibold))
                    Spacer(minLength: 8)
                    Text("\(grouped.order.count)").monospacedDigit()
                    Image(systemName: showsSets ? "chevron.up" : "chevron.down")
                }
                .foregroundStyle(Ink.secondary)
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Metrics.radiusInner))
                .contentShape(.rect)
            }
            .buttonStyle(PressFeedbackButtonStyle())
            .accessibilityIdentifier("summary.sets")
            .accessibilityValue(showsSets ? String(localized: "Expanded") : String(localized: "Collapsed"))
            if showsSets {
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
            let selected = maxCandidates.filter { chosenMaxIDs.contains($0.id) }.map {
                MaxRecord(grip: $0.grip, kg: $0.kg, source: .measured, side: $0.side)
            }
            guard templates.recordSession(plan: plan, template: template, reps: reps,
                                          startedAt: startedAt, finishedAt: finishedAt, rpe: grade,
                                          newMaxes: selected) != nil else { return }
            saved = true
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
        ZStack {
            Text("Keep holding…").hidden().accessibilityHidden(true)
            Text("Hold to discard").hidden().accessibilityHidden(true)
            Text(isHolding ? "Keep holding…" : "Hold to discard")
                .foregroundStyle(Accent.alarm)
                .contentTransition(.identity)
                .animation(nil, value: isHolding)
        }
        .font(.system(.subheadline, weight: .semibold))
        .actionLabelLayout(fullWidth: true)
        .background {
            GeometryReader { geo in
                ZStack {
                    Capsule().fill(Accent.alarm.opacity(0.12))
                    Capsule()
                        .fill(Accent.alarm.opacity(0.36))
                        .mask(alignment: .leading) {
                            Rectangle()
                                .frame(width: geo.size.width * progress)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                }
            }
        }
        .contentShape(.capsule)
        // Keep scrolling available if a gesture begins here. Global translation
        // cancels the hold as the page moves beneath the finger.
        .simultaneousGesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged { updateHold(translation: $0.translation) }
                .onEnded { _ in endHold() }
        )
        .onDisappear { endHold() }
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
