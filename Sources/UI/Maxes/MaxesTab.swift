// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Charts
import SwiftData
import SwiftUI

/// How strong you are at your limit, per grip, over time — the fourth tab.
///
/// History charts the WORKING load your sessions averaged; this charts the CEILING the
/// gauge has seen you pull. `MaxRecord` is append-only, so every max is still there and a
/// card is one grip's rows drawn as a curve.
///
/// A grip has two actions: measure again, or edit its hand values (which appends).
///
/// The WORKING max is the NEWEST record, not the highest: a benchmark that tests lower
/// honestly lowers your percentage targets. Best-ever is shown beside it as the PR.
struct MaxesTab: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var glyphTileSize: CGFloat = 44
    @ScaledMetric(relativeTo: .body) private var chartHeight: CGFloat = 130
    /// Oldest first — each grip's slice is then already in chart order.
    @Query(sort: [SortDescriptor(\MaxRecord.recordedAt)])
    private var records: [MaxRecord]
    /// Critical force lives on the SAME card as the max it is a share of: the ratio is the
    /// point of it, and a section of its own at the bottom left it far from that max.
    @Query(sort: [SortDescriptor(\CriticalForceRecord.recordedAt)])
    private var tests: [CriticalForceRecord]

    @Environment(TemplateStore.self) private var templates

    @State private var measuring: MeasureTarget?
    @State private var choosingMode: GripSpec?
    @State private var editing: MeasureTarget?
    @State private var adding = false
    @State private var criticalForceTest: CriticalForceTestRequest?
    @State private var criticalForceHistory: MeasureTarget?
    /// Grip keys whose cards are open. EMPTY by default: a card is a summary until asked
    /// (Nuri, 2026-09-30 — one open card filled the whole screen).
    @State private var expanded: Set<String> = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Size CLASS, never the idiom — see `CardGrid`.
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let gripGroups = groups
        ScreenScaffold(title: String(localized: "Benchmarks"), subtitle: subtitle,
                       gridsOnWideScreens: true) {
            VStack(alignment: .leading, spacing: Metrics.spacing) {
                if gripGroups.isEmpty {
                    emptyCard.staggerIn(0)
                } else if sizeClass == .regular {
                    // Two per row on a wide window: one chart across a 13-inch screen is a banner.
                    CardGrid { cards(gripGroups) }
                } else {
                    cards(gripGroups)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    // The one door to both measurements. Critical force lives here, not on
                    // Today: a test every six to eight weeks is a measurement, not the ritual
                    // (Nuri, 2026-09-25).
                    Menu {
                        Button("Measure a max", systemImage: "scalemass") { adding = true }
                            .accessibilityIdentifier("maxes.add.max")
                        Button("Test critical force", systemImage: "stopwatch") { startCriticalForce() }
                            .accessibilityIdentifier("maxes.add.criticalForce")
                    } label: {
                        Label("Add a benchmark", systemImage: "plus")
                            .labelStyle(.iconOnly)
                    }
                    .tint(Accent.graphite)
                    .accessibilityIdentifier("maxes.add")
                }
            }
        }
        .fullScreenCover(item: $measuring) { target in
            MaxMeasureView(grip: target.grip, initialSide: target.side,
                           initialSeconds: target.seconds) { readings in
                templates.recordMaxesWithReceipt(readings.map {
                    .init(grip: target.grip, side: $0.side, kg: $0.kg, source: $0.source, seconds: $0.seconds)
                })
            }
        }
        .maxMeasureChooser(for: $choosingMode,
                           initialSeconds: { templates.missingTimedLength(for: $0) ?? 0 },
                           onChoose: { grip, side, seconds in
                               measuring = MeasureTarget(
                                   grip: grip,
                                   side: side == .both ? .both : templates.firstHandToMeasure(grip, seconds: seconds),
                                   seconds: seconds)
                           },
                           onCriticalForce: { grip in startCriticalForce(on: grip) })
        .sheet(item: $editing) { target in
            MaxEditSheet(grip: target.grip) { editing = nil }
        }
        .fullScreenCover(item: $criticalForceTest) { request in
            CriticalForceTestView(grip: request.grip, hands: request.hands)
        }
        .sheet(item: $criticalForceHistory) { target in
            CriticalForceHistorySheet(gripKey: target.id, title: target.grip.displayName)
        }
        #if DEBUG
        // Headless: `-tab 2 -previewCriticalForce` opens the test (add `-startCriticalForce`
        // with `-mockDevice` to run it; `-previewCriticalForceResult` lands on a finished
        // test's result screen instead). `simctl` cannot tap the + menu.
        .onAppear {
            let args = ProcessInfo.processInfo.arguments
            if args.contains("-previewCriticalForce") || args.contains("-previewCriticalForceResult"),
               criticalForceTest == nil { startCriticalForce() }
            // `-expandBenchmarks` opens every card, for screenshots of the open state.
            if args.contains("-expandBenchmarks") { expanded = Set(groups.map(\.key)) }
        }
        #endif
        .sheet(isPresented: $adding) {
            NewMaxSheet(seed: templates.recentGrips.first ?? GripSpec()) { adding = false }
        }
    }

    /// A new test opens on the grip and hands of the last one, else the routine's first grip.
    private func startCriticalForce() {
        startCriticalForce(on: tests.last?.grip ?? templates.recentGrips.first ?? GripSpec())
    }

    /// On a grip already tested, the hands of its last visit; otherwise one at a time.
    private func startCriticalForce(on grip: GripSpec) {
        let onGrip = tests.filter { $0.gripKey == grip.key }
        criticalForceTest = CriticalForceTestRequest(grip: grip,
                                                     hands: onGrip.latestHands ?? .oneAtATime(first: .left))
    }

    /// One card per tested grip — shared by the phone stack and the wide grid. Untested
    /// routine grips no longer get cards of their own (Nuri, 2026-10-04: they crowded the
    /// tab); they lead the "+" › Measure a max sheet instead, as FROM YOUR ROUTINES.
    @ViewBuilder
    private func cards(_ gripGroups: [GripGroup]) -> some View {
        ForEach(Array(gripGroups.enumerated()), id: \.element.id) { index, group in
            gripCard(group).staggerIn(index)
        }
    }

    /// The staleness line the soft nudge is the icon-sized version of.
    private var subtitle: String? {
        guard let last = templates.lastMeasuredMaxAt else { return nil }
        return String(localized: "Tested \(last.formatted(.relative(presentation: .named)))")
    }

    // MARK: - Grouping

    private struct MeasureTarget: Identifiable {
        let grip: GripSpec
        var side: Side = .left
        /// 0 is the peak; otherwise the timed length chosen in `MaxMeasureChooser`.
        var seconds: Int = 0
        var id: String { grip.key }
    }

    private struct GripGroup: Identifiable {
        let key: String
        let grip: GripSpec
        /// PEAK maxes, oldest first, every hand mixed — the per-side slices are cut in the
        /// card. The chart and every "best" are about these.
        let records: [MaxRecord]
        /// Critical force tests on this grip, oldest first, every hand mixed.
        let tests: [CriticalForceRecord]
        /// TIMED maxes, oldest first, every length and hand mixed. Shown as their own
        /// readouts; never charted against the peak, which is a different number.
        var timed: [MaxRecord] = []
        var id: String { key }
        var lastActivity: Date {
            max(records.last?.recordedAt ?? .distantPast, tests.last?.recordedAt ?? .distantPast,
                timed.last?.recordedAt ?? .distantPast)
        }
    }

    /// Most recently tested grip first — the one you are mid-progression on leads. A grip
    /// with only a critical force test still gets its card.
    private var groups: [GripGroup] {
        var maxes: [String: [MaxRecord]] = [:]
        var timed: [String: [MaxRecord]] = [:]
        for record in records {
            if record.isPeak { maxes[record.gripKey, default: []].append(record) }
            else { timed[record.gripKey, default: []].append(record) }
        }
        var cf: [String: [CriticalForceRecord]] = [:]
        for test in tests { cf[test.gripKey, default: []].append(test) }
        return Set(maxes.keys).union(cf.keys).union(timed.keys)
            .map { key in
                let grip = maxes[key]?.last?.grip ?? timed[key]?.last?.grip ?? cf[key]!.last!.grip
                return GripGroup(key: key, grip: grip, records: maxes[key] ?? [], tests: cf[key] ?? [],
                                 timed: timed[key] ?? [])
            }
            .sorted { a, b in
                // Date tie (same morning): key order, so grips don't swap between launches.
                a.lastActivity == b.lastActivity ? a.key < b.key : a.lastActivity > b.lastActivity
            }
    }

    // MARK: - Cards

    /// COLLAPSED BY DEFAULT (Nuri, 2026-09-30). Shut, a card is the grip and its current
    /// maxes — what your percentage targets run on, and what you came to read. Open, it
    /// adds critical force, the chart and the actions. One open card used to fill the
    /// screen, so a second grip was a scroll away and the tab read as one grip's report.
    ///
    /// The header row is the button (grip, name, chevron). The readout below is NOT inside
    /// it — a Button merges its children into one accessibility element and would swallow
    /// each hand's labelled value — but a tap there opens the card too.
    private func gripCard(_ group: GripGroup) -> some View {
        let sides = presentSides(in: group)
        let isOpen = expanded.contains(group.key)
        return MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                cardHeader(group, isOpen: isOpen)

                Group {
                    if group.records.isEmpty, !group.timed.isEmpty {
                        timedReadout(group)
                    } else if group.records.isEmpty {
                        if group.tests.isEmpty || isOpen {
                            Text("No max yet. Measure one to compare with critical force.")
                                .font(.system(.footnote))
                                .foregroundStyle(Ink.tertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        } else {
                            // Shut, a critical-force-only grip still states its number.
                            criticalForceReadout(group)
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 12) {
                            VStack(alignment: .leading, spacing: 6) {
                                // Named once a second kind of max shares the card.
                                if !group.timed.isEmpty {
                                    CapsLabel(String(localized: "Peak max"))
                                } else if isOpen && !group.tests.isEmpty {
                                    CapsLabel(String(localized: "Max"))
                                }
                                currentReadout(group, sides: sides, showBest: isOpen)
                            }
                            if !group.timed.isEmpty { timedReadout(group) }
                        }
                    }
                }
                .contentShape(.rect)
                .onTapGesture { toggle(group.key) }

                if isOpen {
                    details(group, sides: sides)
                        .transition(.opacity)
                }
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func toggle(_ key: String) {
        withAnimation(Motion.state(reduceMotion)) {
            if expanded.contains(key) { expanded.remove(key) } else { expanded.insert(key) }
        }
    }

    private func cardHeader(_ group: GripGroup, isOpen: Bool) -> some View {
        Button { toggle(group.key) } label: {
            HStack(spacing: 12) {
                glyphTile(group.grip)
                Text(group.grip.displayName)
                    .font(.system(.title3, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                    .multilineTextAlignment(.leading)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                    .minimumScaleFactor(dynamicTypeSize.isAccessibilitySize ? 1 : 0.8)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Image(systemName: isOpen ? "chevron.up" : "chevron.down")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            // MANDATORY: a full-width label with a Spacer hit-tests only its glyphs.
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(group.grip.spoken)
        .accessibilityValue(isOpen ? String(localized: "Expanded") : String(localized: "Collapsed"))
        .accessibilityHint(isOpen ? String(localized: "Hides the chart and actions")
                                  : String(localized: "Shows critical force, the chart and Measure again"))
        .accessibilityIdentifier("maxes.card.\(group.key)")
    }

    /// Everything an open card adds. The prose that used to sit under the chart — a
    /// sentence of legend, a "Best … · up … since …" line and a "tested … ago" line —
    /// is gone from the screen: the legend is DRAWN, each best sits under its own number,
    /// and the trend and date are what the chart and the page subtitle already show.
    /// VoiceOver still hears the full sentences on the chart.
    @ViewBuilder
    private func details(_ group: GripGroup, sides: [Side]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if !group.tests.isEmpty, !group.records.isEmpty {
                criticalForceReadout(group)
            }

            if group.records.count + group.tests.count >= 2 {
                chart(group, sides: sides)
            }

            HStack(spacing: 12) {
                if !group.records.isEmpty {
                    Button {
                        editing = MeasureTarget(grip: group.grip)
                    } label: {
                        Label("Edit", systemImage: "slider.horizontal.3")
                            .font(.system(.subheadline, weight: .semibold))
                            .foregroundStyle(Accent.graphite)
                            .actionLabelLayout(minHeight: 44)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(PressFeedbackButtonStyle())
                    .accessibilityLabel("Edit maxes for \(group.grip.spoken)")
                    .accessibilityIdentifier("maxes.edit.\(group.grip.key)")
                }
                Spacer(minLength: 0)
                measureButton(group.grip, label: group.records.isEmpty && group.timed.isEmpty ? String(localized: "Measure max")
                                                                       : String(localized: "Measure again"))
            }
            // Only on a grip that has been tested: a CF door on every card was an
            // orphan row, and the test's own setup reaches any grip.
            if !group.tests.isEmpty {
                Button {
                    criticalForceHistory = MeasureTarget(grip: group.grip)
                } label: {
                    // Standing alone now that Measure asks max or critical force.
                    Label("Critical force history", systemImage: "list.bullet")
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Accent.graphite)
                        .actionLabelLayout(minHeight: 44)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressFeedbackButtonStyle())
                .accessibilityLabel("All critical force tests on \(group.grip.spoken)")
                .accessibilityIdentifier("maxes.cf.history.\(group.grip.key)")
            }
        }
    }

    private var emptyCard: some View {
        MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "No maxes yet"))
                Image(systemName: "scalemass")
                    .font(.system(.largeTitle, weight: .light))
                    .foregroundStyle(Ink.tertiary.opacity(0.55))
                    .accessibilityHidden(true)
            }
        }
    }

    // MARK: - Pieces

    private func glyphTile(_ grip: GripSpec) -> some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(Ink.tertiary.opacity(0.16))
            .frame(width: glyphTileSize, height: glyphTileSize)
            .overlay {
                FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 5, gap: 2.5)
            }
            .accessibilityHidden(true)
    }

    /// Critical force per hand, each with its share of that hand's max when it was tested.
    @ViewBuilder
    private func criticalForceReadout(_ group: GripGroup) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 20))
        VStack(alignment: .leading, spacing: 6) {
            CapsLabel(String(localized: "Critical force"))
            layout {
                ForEach(criticalForceSides(group), id: \.self) { side in
                    if let test = group.tests.last(where: { $0.side == side }) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(side == .both ? String(localized: "Both hands") : side.name)
                                .font(.system(.caption, weight: .medium))
                                .foregroundStyle(Ink.secondary)
                            HStack(alignment: .firstTextBaseline, spacing: 6) {
                                weightText(test.criticalForceKg, style: .title2)
                                if let pct = test.percentOfMax {
                                    Text("\(Int(pct.rounded())) %")
                                        .font(.system(.footnote))
                                        .foregroundStyle(Ink.secondary)
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(String(localized: "Critical force, \(side == .both ? String(localized: "both hands") : side.name)"))
                        .accessibilityValue(test.percentOfMax.map { "\(weightUnit.text(test.criticalForceKg)), \(Int($0.rounded())) % of max" }
                                            ?? weightUnit.text(test.criticalForceKg))
                    }
                }
            }
        }
    }

    private func criticalForceSides(_ group: GripGroup) -> [Side] {
        [Side.both, .left, .right].filter { side in group.tests.contains { $0.side == side } }
    }

    /// "Critical force up 1.2 kg since 12 Jul" — the newest test against the one before it,
    /// on the same hand.
    private func criticalForceProgressLine(_ group: GripGroup) -> String? {
        guard let newest = group.tests.last else { return nil }
        let series = group.tests.filter { $0.side == newest.side }
        guard series.count >= 2 else {
            return String(localized: "Critical force tested \(newest.recordedAt.formatted(.relative(presentation: .named)))")
        }
        let previous = series[series.count - 2]
        let delta = newest.criticalForceKg - previous.criticalForceKg
        let when = previous.recordedAt.formatted(.dateTime.day().month(.abbreviated))
        guard abs(delta) >= 0.05 else { return String(localized: "Critical force held since \(when)") }
        let verb = delta > 0 ? String(localized: "up") : String(localized: "down")
        return String(localized: "Critical force \(verb) \(weightUnit.number(abs(delta))) \(weightUnit.symbol) since \(when)")
    }

    private func measureButton(_ grip: GripSpec, label: String) -> some View {
        Button {
            choosingMode = grip
        } label: {
            Text(label)
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                .actionLabelLayout(minHeight: 44)
                .overlay(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel("\(label) for \(grip.spoken)")
        .accessibilityIdentifier("maxes.measure.\(grip.key)")
    }

    /// The current working numbers, trailing the title. One value for a both-hands
    /// grip; a compact L/R pair once the hands have their own records.
    @ViewBuilder
    private func currentReadout(_ group: GripGroup, sides: [Side], showBest: Bool) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 20))
        layout {
            ForEach(sides, id: \.self) { side in
                if let record = newest(in: group, side: side) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(side == .both ? String(localized: "Shared max") : side.name)
                            .font(.system(.caption, weight: .medium))
                            .foregroundStyle(Ink.secondary)
                        weightText(record.kg, style: .title2)
                        // The PR, under the number it is the best OF — only when the
                        // working max is below it, so a card at its best says nothing extra.
                        if let best = bestShown(group, side: side, current: record.kg, showBest: showBest) {
                            Text("best \(weightUnit.number(best))")
                                .font(.system(.caption))
                                .monospacedDigit()
                                .foregroundStyle(Ink.tertiary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(side == .both ? String(localized: "Shared max") : side.name)
                    // The best is spoken too: `.ignore` drops the drawn caption.
                    .accessibilityValue(bestShown(group, side: side, current: record.kg, showBest: showBest)
                        .map { "\(weightUnit.text(record.kg)), \(String(localized: "best \(weightUnit.number($0))"))" }
                        ?? weightUnit.text(record.kg))
                    .accessibilityIdentifier("maxes.current.\(group.grip.key).\(side.rawValue)")
                }
            }
        }
    }

    /// Each timed length on its own line, newest value per hand — what a routine set to
    /// "90 % of your 10 s max" runs on. Shown shut as well as open: like the peak, it is
    /// the number targets are made of.
    @ViewBuilder
    private func timedReadout(_ group: GripGroup) -> some View {
        let lengths = Set(group.timed.map(\.durationSeconds)).sorted()
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 20))
        VStack(alignment: .leading, spacing: 12) {
            ForEach(lengths, id: \.self) { seconds in
                let series = group.timed.filter { $0.durationSeconds == seconds }
                let sides = [Side.both, .left, .right].filter { side in series.contains { $0.side == side } }
                VStack(alignment: .leading, spacing: 6) {
                    CapsLabel(String(localized: "\(seconds) s max"))
                    layout {
                        ForEach(sides, id: \.self) { side in
                            if let record = series.last(where: { $0.side == side }) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(side == .both ? String(localized: "Shared max") : side.name)
                                        .font(.system(.caption, weight: .medium))
                                        .foregroundStyle(Ink.secondary)
                                    weightText(record.kg, style: .title3)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .accessibilityElement(children: .ignore)
                                .accessibilityLabel(String(localized: "\(seconds) second max, \(side == .both ? String(localized: "both hands") : side.name)"))
                                .accessibilityValue(weightUnit.text(record.kg))
                                .accessibilityIdentifier("maxes.timed.\(group.grip.key).\(seconds).\(side.rawValue)")
                            }
                        }
                    }
                }
            }
        }
    }

    private func weightText(_ kg: Double, style: Font.TextStyle) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 2) {
            Text(weightUnit.number(kg))
                .font(.system(style, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
            Text(weightUnit.symbol)
                .font(.system(.caption))
                .foregroundStyle(Ink.tertiary)
        }
    }

    /// "Best 31.5 kg · up 2.4 kg since 12 Jul" — the PR beside how the newest test
    /// moved against the one before it, on the same hand.
    private func progressLine(_ group: GripGroup) -> String {
        let sides = presentSides(in: group)
        let bests = sides.map { side in
            let best = group.records.filter { $0.side == side }.map(\.kg).max() ?? 0
            let weight = weightUnit.number(best)
            return sides.count == 1 ? weight : "\(side.name) \(weight)"
        }.joined(separator: " · ")
        var parts = [String(localized: "Best \(bests) \(weightUnit.symbol)")]
        if let newest = group.records.last {
            let series = group.records.filter { $0.side == newest.side }
            if series.count >= 2 {
                let previous = series[series.count - 2]
                let delta = newest.kg - previous.kg
                let when = previous.recordedAt.formatted(.dateTime.day().month(.abbreviated))
                if abs(delta) >= 0.05 {
                    let verb = delta > 0 ? String(localized: "up") : String(localized: "down")
                    parts.append(String(localized: "\(verb) \(weightUnit.number(abs(delta))) \(weightUnit.symbol) since \(when)"))
                } else {
                    parts.append(String(localized: "held since \(when)"))
                }
            }
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - Chart

    private func presentSides(in group: GripGroup) -> [Side] {
        var sides: [Side] = []
        for side in [Side.both, .left, .right] where group.records.contains(where: { $0.side == side }) {
            sides.append(side)
        }
        return sides
    }

    /// The hand's best, when the card is open and the working max sits below it.
    private func bestShown(_ group: GripGroup, side: Side, current: Double, showBest: Bool) -> Double? {
        guard showBest, let best = group.records.filter({ $0.side == side }).map(\.kg).max(),
              best > current + 0.05 else { return nil }
        return best
    }

    private func newest(in group: GripGroup, side: Side) -> MaxRecord? {
        group.records.last { $0.side == side }
    }

    /// One line per hand, all bleu (measured kilograms get the measurement colour). Hands
    /// differ by DASH, not hue, surviving greyscale; the wash appears only on a single-series
    /// chart, where it cannot smear two hands into one shape.
    private func chart(_ group: GripGroup, sides: [Side]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Chart {
                ForEach(sides, id: \.self) { side in
                    let series = group.records.filter { $0.side == side }
                    // The wash only when the max stands alone: beside critical force it
                    // ended at the last max while the grey lines ran on, and read as cut off.
                    if sides.count == 1, group.tests.isEmpty {
                        ForEach(series) { record in
                            AreaMark(x: .value("Date", record.recordedAt),
                                     y: .value("Max", weightUnit.fromKg(record.kg)))
                                .interpolationMethod(.monotone)
                                .foregroundStyle(LinearGradient(
                                    colors: [Accent.bleu.opacity(0.28), Accent.bleu.opacity(0.02)],
                                    startPoint: .top, endPoint: .bottom))
                        }
                    }
                    ForEach(series) { record in
                        LineMark(x: .value("Date", record.recordedAt),
                                 y: .value("Max", weightUnit.fromKg(record.kg)),
                                 series: .value("Hand", "max·" + side.name))
                            .interpolationMethod(.monotone)
                            .foregroundStyle(Accent.bleu)
                            .lineStyle(dash(for: side))
                        PointMark(x: .value("Date", record.recordedAt),
                                  y: .value("Max", weightUnit.fromKg(record.kg)))
                            .foregroundStyle(Accent.bleu)
                            .symbolSize(24)
                    }
                }
                // Critical force under the max, in steel: the gap between the two lines is
                // the picture, the ceiling against what you can keep using. Hands still
                // differ by dash, so the two vocabularies never cross.
                ForEach(criticalForceSides(group), id: \.self) { side in
                    ForEach(group.tests.filter { $0.side == side }) { test in
                        LineMark(x: .value("Date", test.recordedAt),
                                 y: .value("Critical force", weightUnit.fromKg(test.criticalForceKg)),
                                 series: .value("Hand", "cf·" + side.name))
                            .interpolationMethod(.monotone)
                            .foregroundStyle(StatusTint.calm)
                            .lineStyle(dash(for: side))
                        PointMark(x: .value("Date", test.recordedAt),
                                  y: .value("Critical force", weightUnit.fromKg(test.criticalForceKg)))
                            .foregroundStyle(StatusTint.calm)
                            .symbol(.square)
                            .symbolSize(22)
                    }
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: dynamicTypeSize.isAccessibilitySize ? 2 : 3)) { _ in
                    AxisGridLine().foregroundStyle(Ink.tertiary.opacity(0.2))
                    AxisValueLabel(format: .dateTime.day().month(.abbreviated))
                }
            }
            .chartYAxisLabel(weightUnit.symbol)
        .chartYAxis {
                AxisMarks { _ in
                    AxisGridLine().foregroundStyle(Ink.tertiary.opacity(0.2))
                    AxisValueLabel()
                }
            }
            .frame(height: chartHeight)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(chartSummary(group))

            chartLegend(group, sides: sides)
                .accessibilityHidden(true)
        }
    }

    /// What VoiceOver hears for the chart: the sentences the card no longer prints.
    private func chartSummary(_ group: GripGroup) -> String {
        var parts = [group.grip.spoken]
        if !group.records.isEmpty { parts.append(progressLine(group)) }
        if let line = criticalForceProgressLine(group) { parts.append(line) }
        return parts.joined(separator: ". ")
    }

    /// A DRAWN key — each entry is a sample of the very stroke it names — instead of the
    /// sentence "blue max · grey critical force · dashed left · dotted right", which asked
    /// you to translate words back into lines. Colour entries only when both series are
    /// on the chart; hand entries only when a hand has its own line.
    @ViewBuilder
    private func chartLegend(_ group: GripGroup, sides: [Side]) -> some View {
        let series = !group.tests.isEmpty && !group.records.isEmpty
        let allSides = Set(sides).union(criticalForceSides(group))
        let hands = allSides.contains(.left) || allSides.contains(.right)
        let colours = HStack(spacing: 14) {
            legendItem(String(localized: "Max"), color: Accent.bleu, style: dash(for: .both))
            legendItem(String(localized: "Critical force"), color: StatusTint.calm, style: dash(for: .both))
        }
        let handKey = HStack(spacing: 14) {
            legendItem(Side.left.name, color: Ink.secondary, style: dash(for: .left))
            legendItem(Side.right.name, color: Ink.secondary, style: dash(for: .right))
        }
        if series || hands {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) {
                    if series { colours }
                    if hands { handKey }
                }
                VStack(alignment: .leading, spacing: 6) {
                    if series { colours }
                    if hands { handKey }
                }
            }
        }
    }

    private func legendItem(_ title: String, color: Color, style: StrokeStyle) -> some View {
        HStack(spacing: 6) {
            Path { path in
                path.move(to: CGPoint(x: 1, y: 4))
                path.addLine(to: CGPoint(x: 19, y: 4))
            }
            .stroke(color, style: style)
            .frame(width: 20, height: 8)
            Text(title)
                .font(.system(.caption))
                .foregroundStyle(Ink.secondary)
                .fixedSize()
        }
    }

    private func dash(for side: Side) -> StrokeStyle {
        switch side {
        case .both:  StrokeStyle(lineWidth: 2.5, lineCap: .round)
        case .left:  StrokeStyle(lineWidth: 2, lineCap: .round, dash: [6, 4])
        case .right: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [1.5, 3.5])
        }
    }
}
