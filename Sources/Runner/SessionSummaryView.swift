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
                    SessionSummaryHeader(caption: template.name,
                                         title: didAnyWork ? String(localized: "Session done")
                                                           : String(localized: "Session ended"))
                    SessionSummaryStats(completed: completed.count, planned: reps.count,
                                        heldSeconds: heldSeconds, peakKg: peakKg)
                    if didAnyWork { newMaxCard }
                    if didAnyWork { gradeCard }
                    SessionSetBreakdown(reps: reps, expanded: $showsSets)
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
                Text("No pulls held. Nothing to log.")
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
            MaterialCard(surface: .flat) {
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
                        Text("Pick the peaks to save as maxes.")
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
                SessionPeakFigure(grip: candidate.grip, side: candidate.side, kg: candidate.kg,
                                  previous: candidate.previous)
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

    private func sideLine(_ candidate: SessionMaxCandidate) -> String {
        let kg = weightUnit.number(candidate.kg)
        return String(localized: "\(SessionPeakFigure.handLabel(candidate.side)) · \(candidate.grip.shortName) · \(kg) \(weightUnit.symbol)")
    }

    private var gradeCard: some View {
        MaterialCard(surface: .flat) {
            EffortPicker(selection: Binding(get: { grade?.rawValue },
                                             set: { grade = $0.flatMap(RPE.init(rawValue:)) }),
                         labels: RPE.allCases.map(\.name),
                         title: String(localized: "How hard did it feel?"), identifier: "effort.overall")
        }
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

// MARK: - Pieces the finish summary and History's session detail share

/// The routine above, the outcome below, at the summary's own scale. History's detail
/// passes the day instead of "Session done"; both read as the same document.
struct SessionSummaryHeader: View {
    var caption: String
    var title: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            CapsLabel(caption)
            Text(title)
                .font(.system(.largeTitle, weight: .semibold))
                .foregroundStyle(Ink.primary)
        }
    }
}

/// Three cards whose VALUES share a line. "Under tension" has no suffix and was the
/// one label that wrapped on a 13 mini, so its value sat ~25 pt below "6 of 36" and
/// "17.0 kg" beside it — the summary's most visibly slapped-in spot (audit
/// 2026-10-31). Every card now reserves the TALLEST label and a suffix line
/// (`StatCard`), so the three values sit on one baseline at any width. At
/// accessibility sizes a third of the row cannot hold a word, so the cards stack,
/// each full width with its unit inline after the value.
///
/// `peakKg` nil is a session nothing measured (History's timer-only sessions): the card
/// says "—", never "0.0 kg", which would state a measurement nobody took.
struct SessionSummaryStats: View {
    var completed: Int
    var planned: Int
    var heldSeconds: Int
    var peakKg: Double?

    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let cards = [
            StatCard.Content(title: String(localized: "Pulls"), value: "\(completed)",
                             suffix: String(localized: "of \(planned)")),
            StatCard.Content(title: String(localized: "Under tension"),
                             value: PlanMath.clockText(heldSeconds), suffix: nil),
            StatCard.Content(title: String(localized: "Peak"),
                             value: peakKg.map { weightUnit.number($0) } ?? String(localized: "—"),
                             suffix: peakKg == nil ? nil : weightUnit.symbol),
        ]
        return Group {
            if typeSize.isAccessibilitySize {
                VStack(spacing: 10) {
                    ForEach(cards.indices, id: \.self) { StatCard(content: cards[$0], stacked: true) }
                }
            } else {
                HStack(spacing: 10) {
                    ForEach(cards.indices, id: \.self) {
                        StatCard(content: cards[$0], stacked: false,
                                 reservedTitles: cards.map(\.title),
                                 reservesSuffix: cards.contains { $0.suffix != nil })
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(spoken)
    }

    private var spoken: String {
        guard let peakKg else {
            return String(localized: "\(completed) of \(planned) pulls completed, \(heldSeconds) seconds under tension")
        }
        return String(localized: """
            \(completed) of \(planned) pulls completed, \
            \(heldSeconds) seconds under tension, \
            peak \(weightUnit.number(peakKg)) \(weightUnit.spokenName)
            """)
    }
}

/// A peak as the summary states it: the weight, the hand beside it (or under it, when
/// the two will not share a line), the grip, and the max it would replace.
struct SessionPeakFigure: View {
    var grip: GripSpec
    var side: Side
    var kg: Double
    var previous: Double?
    /// A timed max's window; 0 is a peak, and says nothing extra.
    var seconds: Int = 0

    @Environment(\.weightUnit) private var weightUnit

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    peakWeight
                    peakHand
                }
                .fixedSize(horizontal: true, vertical: false)
                VStack(alignment: .leading, spacing: 2) {
                    peakWeight
                    peakHand
                }
            }
            .monospacedDigit()
            Text(seconds > 0 ? "\(grip.line) · \(String(localized: "\(seconds) s"))" : grip.line)
                .font(.system(.caption)).foregroundStyle(Ink.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let previous {
                Text(String(localized: "Previous: \(weightUnit.number(previous)) \(weightUnit.symbol)"))
                    .font(.system(.caption2)).foregroundStyle(Ink.tertiary)
            }
        }
    }

    private var peakWeight: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(weightUnit.number(kg))
                .font(.system(.title2, weight: .semibold))
                .foregroundStyle(Ink.primary)
            Text(weightUnit.symbol).font(.system(.caption)).foregroundStyle(Ink.secondary)
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    private var peakHand: some View {
        Text(Self.handLabel(side))
            .font(.system(.caption, weight: .semibold))
            .foregroundStyle(Ink.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    static func handLabel(_ side: Side) -> String {
        switch side {
        case .left: String(localized: "Left hand")
        case .right: String(localized: "Right hand")
        case .both: String(localized: "Both hands")
        }
    }
}

/// Per set, so a bad set is visible rather than averaged away.
///
/// `showsReps` (History's detail) lists every pull under its set — hand, time held, peak,
/// and how it ended — because an old session is opened to see exactly that. The live
/// summary keeps one line per set: it is a moment, not a record to read back.
struct SessionSetBreakdown: View {
    var reps: [RepSummary]
    @Binding var expanded: Bool
    var showsReps = false
    /// false for a timer-only session: no peak column, which would read "0.0 kg".
    var measured = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let grouped = groupedReps
        return VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(Motion.state(reduceMotion)) { expanded.toggle() }
            } label: {
                HStack {
                    Text("Sets").font(.system(.subheadline, weight: .semibold))
                    Spacer(minLength: 8)
                    Text("\(grouped.order.count)").monospacedDigit()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                }
                .foregroundStyle(Ink.secondary)
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .cardSurface(.flat, in: RoundedRectangle(cornerRadius: Metrics.radiusInner))
                .contentShape(.rect)
            }
            .buttonStyle(PressFeedbackButtonStyle())
            .accessibilityIdentifier("summary.sets")
            .accessibilityValue(expanded ? String(localized: "Expanded") : String(localized: "Collapsed"))
            if expanded {
                ForEach(grouped.order, id: \.self) { index in
                    setRow(grouped.byIndex[index] ?? [])
                }
            }
        }
    }

    @ViewBuilder
    private func setRow(_ inSet: [RepSummary]) -> some View {
        let done = inSet.filter { $0.outcome == .completed }.count
        if showsReps {
            VStack(alignment: .leading, spacing: 0) {
                setHeader(inSet, done: done)
                    .padding(.vertical, 10)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("\(inSet.first?.grip.spoken ?? String(localized: "Set")): \(done) of \(inSet.count) completed")
                ForEach(inSet.indices, id: \.self) { i in
                    Divider().overlay(Ink.tertiary.opacity(0.08))
                    repLine(inSet[i])
                }
            }
            .padding(.horizontal, 14)
            .cardSurface(.flat,
                        in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
        } else {
            setHeader(inSet, done: done)
                .padding(.vertical, 10)
                .padding(.horizontal, 14)
                .cardSurface(.flat,
                            in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(inSet.first?.grip.spoken ?? String(localized: "Set")): \(done) of \(inSet.count) completed")
        }
    }

    private func setHeader(_ inSet: [RepSummary], done: Int) -> some View {
        HStack(spacing: 10) {
            if let grip = inSet.first?.grip {
                FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 8, gap: 3)
                Text(grip.line)
                    .font(.system(.subheadline))
                    .foregroundStyle(Ink.secondary)
                    .lineLimit(showsReps ? nil : 1)
            }
            Spacer(minLength: 8)
            Text("\(done)/\(inSet.count)")
                .font(.system(.subheadline, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(done == inSet.count ? Ink.primary : StatusTint.armed)
        }
    }

    /// One pull: the hand, what was held of what was asked, the peak, and — only when it
    /// did not simply complete — how it ended, in amber (a fact, not an alarm).
    private func repLine(_ rep: RepSummary) -> some View {
        let seconds = rep.heldSeconds.formatted(.number.precision(.fractionLength(1)))
        let held = String(localized: "\(seconds) of \(rep.targetSeconds) s")
        let peak = measured && rep.peakKg > 0 ? "\(weightUnit.number(rep.peakKg)) \(weightUnit.symbol)" : nil
        let ending = Self.ending(rep.outcome)
        return Group {
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 2) {
                    Text(Self.hand(rep.side)).foregroundStyle(Ink.primary)
                    Text(held).foregroundStyle(Ink.secondary)
                    if let peak { Text(peak).foregroundStyle(Ink.primary) }
                    if let ending { Text(ending).foregroundStyle(StatusTint.armed) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(Self.hand(rep.side)).foregroundStyle(Ink.primary)
                    if let ending {
                        Text(ending).font(.system(.caption, weight: .semibold))
                            .foregroundStyle(StatusTint.armed)
                    }
                    Spacer(minLength: 8)
                    Text(held).foregroundStyle(Ink.secondary)
                    if let peak {
                        Text(peak).fontWeight(.semibold).foregroundStyle(Ink.primary)
                    }
                }
            }
        }
        .font(.system(.subheadline))
        .monospacedDigit()
        .padding(.vertical, 8)
        .frame(minHeight: 36)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenRep(rep))
    }

    private func spokenRep(_ rep: RepSummary) -> String {
        let seconds = rep.heldSeconds.formatted(.number.precision(.fractionLength(1)))
        var parts = [SessionPeakFigure.handLabel(rep.side),
                     String(localized: "held \(seconds) of \(rep.targetSeconds) seconds")]
        if measured, rep.peakKg > 0 {
            parts.append(String(localized: "peak \(weightUnit.number(rep.peakKg)) \(weightUnit.spokenName)"))
        }
        if let ending = Self.ending(rep.outcome) { parts.append(ending) }
        return parts.joined(separator: ", ")
    }

    private static func hand(_ side: Side) -> String {
        switch side {
        case .left: String(localized: "Left")
        case .right: String(localized: "Right")
        case .both: String(localized: "Both")
        }
    }

    /// nil for a pull that simply completed.
    static func ending(_ outcome: RepOutcome) -> String? {
        switch outcome {
        case .completed: nil
        case .skipped: String(localized: "Skipped")
        case .earlyRelease: String(localized: "Released early")
        case .aborted: String(localized: "Stopped")
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
}

/// One of the summary's three stats.
///
/// **Side by side, the values share a line.** Each card lays out the row's TALLEST
/// title (hidden, every title in the row reserved in one `ZStack`) and, when any card
/// has one, a suffix line — so a title that wraps, or a card with no suffix, cannot
/// move its value off its neighbours' baseline. The visible title sits at the bottom of
/// that reserve, against its value. **Stacked** (accessibility sizes) it reads as a
/// sentence instead: the title, then the value with its unit inline.
///
/// The suffix is SECONDARY ink: "of 36" and "kg" are part of the number, and tertiary
/// measured 4.13:1 in dark mode, under the 4.5:1 floor for small text.
private struct StatCard: View {
    struct Content {
        var title: String
        var value: String
        var suffix: String?
    }

    var content: Content
    var stacked: Bool
    var reservedTitles: [String] = []
    var reservesSuffix = false

    var body: some View {
        Group {
            if stacked { stackedFace } else { columnFace }
        }
        .padding(.horizontal, stacked ? 16 : 8)
        .padding(.vertical, stacked ? 12 : 14)
        .cardSurface(.flat,
                    in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
    }

    private var columnFace: some View {
        VStack(spacing: 4) {
            ZStack(alignment: .bottom) {
                ForEach(reservedTitles, id: \.self) { title($0).hidden() }
                title(content.title)
            }
            value
            if reservesSuffix {
                // A blank line, not a missing one: the reserve is what keeps the values level.
                suffix(content.suffix ?? " ")
                    .opacity(content.suffix == nil ? 0 : 1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var stackedFace: some View {
        VStack(alignment: .leading, spacing: 4) {
            title(content.title)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                value
                if let suffix = content.suffix { self.suffix(suffix) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func title(_ text: String) -> some View {
        CapsLabel(text)
            .multilineTextAlignment(stacked ? .leading : .center)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var value: some View {
        Text(content.value)
            .font(.system(.title2, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(Ink.primary)
    }

    private func suffix(_ text: String) -> some View {
        Text(text).font(.system(.caption)).foregroundStyle(Ink.secondary)
    }
}

/// Discarding a finished session takes a deliberate HOLD, exactly like ending one.
///
/// Shares `HoldToEndButton`'s gesture and its 0.6 s: one vocabulary for "this cannot be
/// undone", learned once. It is quieter than Save — outlined rather than filled — because
/// throwing the session away is the rarer answer and must never be the reflex. It sits in
/// a scrolling summary, so any drift cancels the hold and scrolls instead.
private struct HoldToDiscardButton: View {
    var action: () -> Void

    @State private var firedTick = 0

    var body: some View {
        HoldToConfirm(cancel: .drift(10),
                      accessibilityLabel: "Discard this session",
                      accessibilityHint: "Press and hold. Nothing is saved.",
                      action: { firedTick += 1; action() }) { isHolding, progress in
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
                HoldFill(progress: progress, tint: Accent.alarm, track: 0.12, fill: 0.36)
            }
        }
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.9), trigger: firedTick)
    }
}
