// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Charts
import SwiftData
import SwiftUI

/// How strong you are at your limit, per grip, over time — the fourth tab.
///
/// This is a different question from History's: History charts the WORKING load your
/// sessions actually averaged; this charts the CEILING the gauge has seen you pull.
/// `MaxRecord` is append-only precisely so this screen costs nothing — every max ever
/// recorded is still there, and a card here is just one grip's rows drawn as a curve.
///
/// Editing and deleting numbers deliberately stays in Settings › Maxes (Nuri,
/// 2026-08-10: "I like the flow there"). This tab is see-and-test: the curves, the
/// deltas, and a Measure button per grip that opens the SAME composer Settings uses —
/// one door to writing a max, two places to reach it from.
///
/// The WORKING max is the NEWEST record, not the highest: a benchmark that tests lower
/// honestly lowers your percentage targets too. Best-ever is shown beside it as the PR.
struct MaxesTab: View {
    /// Oldest first — each grip's slice is then already in chart order.
    @Query(sort: [SortDescriptor(\MaxRecord.recordedAt)])
    private var records: [MaxRecord]
    /// Only to know whether routines exist — invitations for never-tested grips are
    /// drawn from real routines, never from the seed palette a blank app would offer.
    @Query private var routines: [SessionTemplate]

    @Environment(TemplateStore.self) private var templates

    @State private var measuring: MeasureTarget?

    var body: some View {
        let gripGroups = groups
        let untestedInvitations = invitations
        ScreenScaffold(title: String(localized: "Maxes"), subtitle: subtitle) {
            if gripGroups.isEmpty && untestedInvitations.isEmpty {
                // The tour anchors the empty card too — a first-run tour arrives here
                // with no maxes, and a spotlight with nothing to light is a black scrim.
                emptyCard.tourAnchor(.maxesCurves).staggerIn(0)
            } else {
                ForEach(Array(gripGroups.enumerated()), id: \.element.id) { index, group in
                    if index == 0 {
                        gripCard(group).tourAnchor(.maxesCurves).staggerIn(index)
                    } else {
                        gripCard(group).staggerIn(index)
                    }
                }
                ForEach(Array(untestedInvitations.enumerated()), id: \.element.key) { index, grip in
                    // First-run tours land here with routines but no maxes — the
                    // leading invitation is the spotlight's home then.
                    if gripGroups.isEmpty, index == 0 {
                        invitationCard(grip).tourAnchor(.maxesCurves).staggerIn(0)
                    } else {
                        invitationCard(grip).staggerIn(gripGroups.count)
                    }
                }
                footnote
            }
        }
        .sheet(item: $measuring) { target in
            MaxEntrySheet(seed: target.grip) {
                measuring = nil
            }
        }
    }

    /// The staleness line the soft nudge is the icon-sized version of.
    private var subtitle: String {
        guard let last = templates.lastMeasuredMaxAt else { return String(localized: "Your ceiling, per grip") }
        return String(localized: "Tested \(last.formatted(.relative(presentation: .named)))")
    }

    // MARK: - Grouping

    private struct MeasureTarget: Identifiable {
        let grip: GripSpec
        var id: String { grip.key }
    }

    private struct GripGroup: Identifiable {
        let key: String
        let grip: GripSpec
        /// Oldest first, every hand mixed — the per-side slices are cut in the card.
        let records: [MaxRecord]
        var id: String { key }
    }

    /// Most recently tested grip first — the one you are mid-progression on leads.
    private var groups: [GripGroup] {
        var byKey: [String: [MaxRecord]] = [:]
        for record in records { byKey[record.gripKey, default: []].append(record) }
        return byKey
            .map { GripGroup(key: $0.key, grip: $0.value.last!.grip, records: $0.value) }
            .sorted { a, b in
                let (ta, tb) = (a.records.last!.recordedAt, b.records.last!.recordedAt)
                // Date tie (same benchmark morning): key order, so two grips tested
                // in one sitting don't swap places between launches.
                return ta == tb ? a.key < b.key : ta > tb
            }
    }

    /// Grips your routines train that have never seen a number — an invitation, not a
    /// reproach, and only once routines exist at all.
    private var invitations: [GripSpec] {
        guard !routines.isEmpty else { return [] }
        let tested = Set(records.map(\.gripKey))
        return templates.recentGrips.filter { !tested.contains($0.key) }
    }

    // MARK: - Cards

    private func gripCard(_ group: GripGroup) -> some View {
        let sides = presentSides(in: group)
        return MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    glyphTile(group.grip)
                    Text(group.grip.displayName)
                        .font(.system(.title3, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                    Spacer(minLength: 8)
                    currentReadout(group, sides: sides)
                }

                if group.records.count >= 2 {
                    chart(group, sides: sides)
                }

                HStack(spacing: 8) {
                    Text(progressLine(group))
                        .font(.system(.footnote))
                        .monospacedDigit()
                        .foregroundStyle(Ink.tertiary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Spacer(minLength: 8)
                    measureButton(group.grip, label: String(localized: "Measure again"))
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func invitationCard(_ grip: GripSpec) -> some View {
        MaterialCard {
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
        MaterialCard {
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
                measureButton(templates.recentGrips.first ?? GripSpec(), label: String(localized: "Measure a max"))
            }
        }
    }

    /// The same footnote contract as History's: what this screen's numbers are and are
    /// not. Percent targets follow the NEWEST number, including downward — worth one
    /// honest line on the screen where a bad testing day becomes visible.
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
            .frame(width: 44, height: 44)
            .overlay {
                FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 5, gap: 2.5)
            }
            .accessibilityHidden(true)
    }

    private func measureButton(_ grip: GripSpec, label: String) -> some View {
        Button {
            measuring = MeasureTarget(grip: grip)
        } label: {
            Text(label)
                .font(.system(.footnote, weight: .semibold))
                .foregroundStyle(Accent.graphite)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .overlay(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel("\(label) for \(grip.spoken)")
    }

    /// The current working numbers, trailing the title. One value for a both-hands
    /// grip; a compact L/R pair once the hands have their own records.
    @ViewBuilder
    private func currentReadout(_ group: GripGroup, sides: [Side]) -> some View {
        if sides == [.both], let newest = newest(in: group, side: .both) {
            kgText(newest.kg, style: .title2)
        } else {
            VStack(alignment: .trailing, spacing: 2) {
                ForEach(sides.filter { $0 != .both }, id: \.self) { side in
                    if let newest = newest(in: group, side: side) {
                        HStack(spacing: 4) {
                            Text(side == .left ? "L" : "R")
                                .font(.system(.caption, weight: .semibold))
                                .foregroundStyle(Ink.tertiary)
                            kgText(newest.kg, style: .subheadline)
                        }
                    }
                }
            }
        }
    }

    private func kgText(_ kg: Double, style: Font.TextStyle) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 2) {
            Text(kg.formatted(.number.precision(.fractionLength(1))))
                .font(.system(style, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
            Text("kg")
                .font(.system(.caption))
                .foregroundStyle(Ink.tertiary)
        }
    }

    /// "Best 31.5 kg · up 2.4 kg since 12 Jul" — the PR beside how the newest test
    /// moved against the one before it, on the same hand.
    private func progressLine(_ group: GripGroup) -> String {
        let best = group.records.map(\.kg).max() ?? 0
        var parts = [String(localized: "Best \(best.formatted(.number.precision(.fractionLength(1)))) kg")]
        if let newest = group.records.last {
            let series = group.records.filter { $0.side == newest.side }
            if series.count >= 2 {
                let previous = series[series.count - 2]
                let delta = newest.kg - previous.kg
                let when = previous.recordedAt.formatted(.dateTime.day().month(.abbreviated))
                if abs(delta) >= 0.05 {
                    let verb = delta > 0 ? String(localized: "up") : String(localized: "down")
                    parts.append(String(localized: "\(verb) \(abs(delta).formatted(.number.precision(.fractionLength(1)))) kg since \(when)"))
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

    /// One line per hand, all in bleu — measured kilograms get the measurement colour
    /// everywhere in this app. Hands differ by DASH, not hue (survives greyscale and
    /// every colour vision); the wash under the curve appears only on a single-series
    /// chart, where it cannot smear two hands into one shape.
    private func chart(_ group: GripGroup, sides: [Side]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Chart {
                ForEach(sides, id: \.self) { side in
                    let series = group.records.filter { $0.side == side }
                    if sides.count == 1 {
                        ForEach(series) { record in
                            AreaMark(x: .value("Date", record.recordedAt),
                                     y: .value("Max", record.kg))
                                .interpolationMethod(.monotone)
                                .foregroundStyle(LinearGradient(
                                    colors: [Accent.bleu.opacity(0.28), Accent.bleu.opacity(0.02)],
                                    startPoint: .top, endPoint: .bottom))
                        }
                    }
                    ForEach(series) { record in
                        LineMark(x: .value("Date", record.recordedAt),
                                 y: .value("Max", record.kg),
                                 series: .value("Hand", side.name))
                            .interpolationMethod(.monotone)
                            .foregroundStyle(Accent.bleu)
                            .lineStyle(dash(for: side))
                        PointMark(x: .value("Date", record.recordedAt),
                                  y: .value("Max", record.kg))
                            .foregroundStyle(Accent.bleu)
                            .symbolSize(24)
                    }
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 3)) { _ in
                    AxisGridLine().foregroundStyle(Ink.tertiary.opacity(0.2))
                    AxisValueLabel(format: .dateTime.day().month(.abbreviated))
                }
            }
            .chartYAxis {
                AxisMarks { _ in
                    AxisGridLine().foregroundStyle(Ink.tertiary.opacity(0.2))
                    AxisValueLabel()
                }
            }
            .frame(height: 130)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(group.grip.spoken): \(progressLine(group))")

            if sides.contains(.left) || sides.contains(.right) {
                Text("dashed left · dotted right")
                    .font(.system(.caption2))
                    .foregroundStyle(Ink.tertiary)
                    .accessibilityHidden(true)
            }
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
