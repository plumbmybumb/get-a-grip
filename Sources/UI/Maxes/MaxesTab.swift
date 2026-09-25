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
    /// Only to know whether routines exist: invitations come from real routines, never the
    /// seed palette.
    @Query private var routines: [SessionTemplate]

    @Environment(TemplateStore.self) private var templates

    @State private var measuring: MeasureTarget?
    @State private var choosingMode: GripSpec?
    @State private var editing: MeasureTarget?
    @State private var adding = false
    @State private var criticalForceTest: CriticalForceTestRequest?
    @State private var criticalForceHistory: MeasureTarget?

    /// Size CLASS, never the idiom — see `CardGrid`.
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let gripGroups = groups
        let untestedInvitations = invitations
        ScreenScaffold(title: String(localized: "Benchmarks"), subtitle: subtitle,
                       gridsOnWideScreens: true) {
            VStack(alignment: .leading, spacing: Metrics.spacing) {
                if gripGroups.isEmpty && untestedInvitations.isEmpty {
                    // Anchored even when empty: a first-run tour arrives with no maxes.
                    emptyCard.tourAnchor(.maxesCurves).staggerIn(0)
                } else if sizeClass == .regular {
                    // Two per row on a wide window: one chart across a 13-inch screen is a banner.
                    CardGrid { cards(gripGroups, untestedInvitations) }
                } else {
                    cards(gripGroups, untestedInvitations)
                }
                footnote
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Add a max", systemImage: "plus") { adding = true }
                        .labelStyle(.iconOnly)
                        .tint(Accent.graphite)
                        .accessibilityIdentifier("maxes.add")
                }
            }
        }
        .fullScreenCover(item: $measuring) { target in
            MaxMeasureView(grip: target.grip, initialSide: target.side) { readings in
                templates.recordMaxesWithReceipt(readings.map {
                    .init(grip: target.grip, side: $0.side, kg: $0.kg, source: $0.source)
                })
            }
        }
        .maxMeasureModeDialog(for: $choosingMode) { grip, side in
            measuring = MeasureTarget(grip: grip, side: side)
        }
        .sheet(item: $editing) { target in
            MaxEditSheet(grip: target.grip) { editing = nil }
        }
        .fullScreenCover(item: $criticalForceTest) { request in
            CriticalForceTestView(grip: request.grip, hands: request.hands)
        }
        .sheet(item: $criticalForceHistory) { target in
            CriticalForceHistorySheet(gripKey: target.id, title: target.grip.displayName)
        }
        .sheet(isPresented: $adding) {
            NewMaxSheet(seed: templates.recentGrips.first ?? GripSpec()) { adding = false }
        }
    }

    /// One card per tested grip, then one invitation per untested one — shared by the phone
    /// stack and the wide grid.
    @ViewBuilder
    private func cards(_ gripGroups: [GripGroup], _ untestedInvitations: [GripSpec]) -> some View {
        ForEach(Array(gripGroups.enumerated()), id: \.element.id) { index, group in
            if index == 0 {
                gripCard(group).tourAnchor(.maxesCurves).staggerIn(index)
            } else {
                gripCard(group).staggerIn(index)
            }
        }
        ForEach(Array(untestedInvitations.enumerated()), id: \.element.key) { index, grip in
            // First-run tours land here with routines but no maxes — the leading
            // invitation is the spotlight's home then.
            if gripGroups.isEmpty, index == 0 {
                invitationCard(grip).tourAnchor(.maxesCurves).staggerIn(0)
            } else {
                invitationCard(grip).staggerIn(gripGroups.count)
            }
        }
    }

    /// The staleness line the soft nudge is the icon-sized version of.
    private var subtitle: String {
        guard let last = templates.lastMeasuredMaxAt else { return String(localized: "Your ceiling and endurance, per grip") }
        return String(localized: "Tested \(last.formatted(.relative(presentation: .named)))")
    }

    // MARK: - Grouping

    private struct MeasureTarget: Identifiable {
        let grip: GripSpec
        var side: Side = .left
        var id: String { grip.key }
    }

    private struct GripGroup: Identifiable {
        let key: String
        let grip: GripSpec
        /// Oldest first, every hand mixed — the per-side slices are cut in the card.
        let records: [MaxRecord]
        /// Critical force tests on this grip, oldest first, every hand mixed.
        let tests: [CriticalForceRecord]
        var id: String { key }
        var lastActivity: Date {
            max(records.last?.recordedAt ?? .distantPast, tests.last?.recordedAt ?? .distantPast)
        }
    }

    /// Most recently tested grip first — the one you are mid-progression on leads. A grip
    /// with only a critical force test still gets its card.
    private var groups: [GripGroup] {
        var maxes: [String: [MaxRecord]] = [:]
        for record in records { maxes[record.gripKey, default: []].append(record) }
        var cf: [String: [CriticalForceRecord]] = [:]
        for test in tests { cf[test.gripKey, default: []].append(test) }
        return Set(maxes.keys).union(cf.keys)
            .map { key in
                let grip = maxes[key]?.last?.grip ?? cf[key]!.last!.grip
                return GripGroup(key: key, grip: grip, records: maxes[key] ?? [], tests: cf[key] ?? [])
            }
            .sorted { a, b in
                // Date tie (same morning): key order, so grips don't swap between launches.
                a.lastActivity == b.lastActivity ? a.key < b.key : a.lastActivity > b.lastActivity
            }
    }

    /// Grips your routines train that have never seen a number — an invitation, not a
    /// reproach, and only once routines exist.
    private var invitations: [GripSpec] {
        guard !routines.isEmpty else { return [] }
        let tested = Set(records.map(\.gripKey)).union(tests.map(\.gripKey))
        return templates.recentGrips.filter { !tested.contains($0.key) }
    }

    // MARK: - Cards

    private func gripCard(_ group: GripGroup) -> some View {
        let sides = presentSides(in: group)
        return MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    glyphTile(group.grip)
                    Text(group.grip.displayName)
                        .font(.system(.title3, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                        .minimumScaleFactor(dynamicTypeSize.isAccessibilitySize ? 1 : 0.8)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }

                VStack(alignment: .leading, spacing: 6) {
                    if !group.tests.isEmpty { CapsLabel(String(localized: "Max")) }
                    if group.records.isEmpty {
                        Text("No max yet. Measure one to see critical force as a share of it.")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.tertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        currentReadout(group, sides: sides)
                    }
                }

                if !group.tests.isEmpty {
                    criticalForceReadout(group)
                }

                if group.records.count + group.tests.count >= 2 {
                    chart(group, sides: sides)
                }

                if !group.records.isEmpty {
                    Text(progressLine(group))
                        .font(.system(.footnote))
                        .monospacedDigit()
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let line = criticalForceProgressLine(group) {
                    Text(line)
                        .font(.system(.footnote))
                        .monospacedDigit()
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
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
                    measureButton(group.grip, label: group.records.isEmpty ? String(localized: "Measure max")
                                                                           : String(localized: "Measure again"))
                }
                // Only on a grip that has been tested: a CF door on every card was an
                // orphan row, and the test's own setup reaches any grip.
                if !group.tests.isEmpty {
                    HStack(spacing: 12) {
                        Button {
                            criticalForceHistory = MeasureTarget(grip: group.grip)
                        } label: {
                            Label("All tests", systemImage: "list.bullet")
                                .font(.system(.subheadline, weight: .semibold))
                                .foregroundStyle(Accent.graphite)
                                .actionLabelLayout(minHeight: 44)
                                .contentShape(.capsule)
                        }
                        .buttonStyle(PressFeedbackButtonStyle())
                        .accessibilityLabel("All critical force tests on \(group.grip.spoken)")
                        .accessibilityIdentifier("maxes.cf.history.\(group.grip.key)")
                        Spacer(minLength: 0)
                        capsuleButton(String(localized: "Test critical force"),
                                      identifier: "maxes.cf.test.\(group.grip.key)") {
                            criticalForceTest = CriticalForceTestRequest(
                                grip: group.grip, hands: group.tests.latestHands ?? .oneAtATime(first: .left))
                        }
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func invitationCard(_ grip: GripSpec) -> some View {
        MaterialCard(surface: .flat) {
            HStack(spacing: 12) {
                glyphTile(grip)
                VStack(alignment: .leading, spacing: 2) {
                    Text(grip.displayName)
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                    // In a routine, never tested: its percentage targets are waiting
                    // on this number.
                    Text("In your routine — never tested")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                }
                Spacer(minLength: 8)
                measureButton(grip, label: String(localized: "Measure"))
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
                Text("Measure the most a grip can hold and it lands here — every later test draws the curve of you getting stronger.")
                    .font(.system(.subheadline))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// What this screen's numbers are and are not. Percent targets follow the NEWEST number,
    /// including downward — worth one honest line where a bad testing day shows.
    private var footnote: some View {
        Text("Your working max is the newest test, best is your record. Percentage targets follow the newest number — up or down.")
            .font(.system(.footnote))
            .foregroundStyle(Ink.tertiary)
            .fixedSize(horizontal: false, vertical: true)
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

    /// The house outlined capsule, for an action beside a number.
    private func capsuleButton(_ label: String, identifier: String,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                .actionLabelLayout(minHeight: 44)
                .overlay(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityIdentifier(identifier)
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
    private func currentReadout(_ group: GripGroup, sides: [Side]) -> some View {
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
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(side == .both ? String(localized: "Shared max") : side.name)
                    .accessibilityValue(weightUnit.text(record.kg))
                    .accessibilityIdentifier("maxes.current.\(group.grip.key).\(side.rawValue)")
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
            .accessibilityLabel("\(group.grip.spoken): \(progressLine(group))")

            if let legend = chartLegend(group, sides: sides) {
                Text(legend)
                    .font(.system(.caption2))
                    .foregroundStyle(Ink.tertiary)
                    .accessibilityHidden(true)
            }
        }
    }

    private func chartLegend(_ group: GripGroup, sides: [Side]) -> String? {
        var parts: [String] = []
        if !group.tests.isEmpty, !group.records.isEmpty {
            parts.append(String(localized: "blue max · grey critical force"))
        }
        let allSides = Set(sides).union(criticalForceSides(group))
        if allSides.contains(.left) || allSides.contains(.right) {
            parts.append(String(localized: "dashed left · dotted right"))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func dash(for side: Side) -> StrokeStyle {
        switch side {
        case .both:  StrokeStyle(lineWidth: 2.5, lineCap: .round)
        case .left:  StrokeStyle(lineWidth: 2, lineCap: .round, dash: [6, 4])
        case .right: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [1.5, 3.5])
        }
    }
}
