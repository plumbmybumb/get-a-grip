// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Charts
import SwiftData
import SwiftUI

/// Critical force on the Maxes tab: one card per grip and hand that has been tested,
/// the endurance number beside the ceiling ones. Maxes answer "how hard can I pull";
/// these answer "how much of that can I keep using". Nothing shows until a test exists,
/// because Today's line is the door.
struct CriticalForceCards: View {
    var onTest: (GripSpec, Side) -> Void

    @Query(sort: [SortDescriptor(\CriticalForceRecord.recordedAt)])
    private var records: [CriticalForceRecord]
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var chartHeight: CGFloat = 110
    @State private var history: HistoryTarget?

    private struct HistoryTarget: Identifiable {
        let key: String
        let title: String
        var id: String { key }
    }

    var body: some View {
        ForEach(series, id: \.key) { group in
            card(group)
        }
        .sheet(item: $history) { target in
            CriticalForceHistorySheet(testKey: target.key, title: target.title)
        }
    }

    private struct Series {
        let key: String
        let grip: GripSpec
        let side: Side
        /// Oldest first.
        let records: [CriticalForceRecord]
        var title: String { side == .both ? grip.displayName : "\(grip.displayName) · \(side.name)" }
    }

    private var series: [Series] {
        var byKey: [String: [CriticalForceRecord]] = [:]
        for record in records { byKey[record.testKey, default: []].append(record) }
        return byKey.compactMap { key, rows in
            guard let last = rows.last else { return nil }
            return Series(key: key, grip: last.grip, side: last.side, records: rows)
        }
        .sorted { $0.records.last!.recordedAt > $1.records.last!.recordedAt }
    }

    private func card(_ group: Series) -> some View {
        let latest = group.records.last!
        return MaterialCard(surface: .flat) {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    CapsLabel(String(localized: "Critical force"))
                    Text(group.title)
                        .font(.system(.title3, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    HStack(alignment: .firstTextBaseline, spacing: 2) {
                        Text(weightUnit.number(latest.criticalForceKg))
                            .font(.system(.title2, weight: .semibold))
                            .monospacedDigit()
                        Text(weightUnit.symbol)
                            .font(.system(.caption))
                            .foregroundStyle(Ink.tertiary)
                    }
                    .foregroundStyle(Ink.primary)
                    Text(ratios(latest))
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.secondary)
                }
                .accessibilityElement(children: .combine)

                if group.records.count >= 2 { chart(group) }

                Text(progressLine(group))
                    .font(.system(.footnote))
                    .monospacedDigit()
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 12) {
                    Button {
                        history = HistoryTarget(key: group.key, title: group.title)
                    } label: {
                        Label("All tests", systemImage: "list.bullet")
                            .font(.system(.subheadline, weight: .semibold))
                            .foregroundStyle(Accent.graphite)
                            .actionLabelLayout(minHeight: 44)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(PressFeedbackButtonStyle())
                    .accessibilityIdentifier("maxes.cf.history.\(group.key)")
                    Spacer(minLength: 0)
                    Button { onTest(group.grip, group.side) } label: {
                        Text("Test again")
                            .font(.system(.subheadline, weight: .semibold))
                            .foregroundStyle(Accent.graphite)
                            .actionLabelLayout(minHeight: 44)
                            .overlay(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
                            .contentShape(.capsule)
                    }
                    .buttonStyle(PressFeedbackButtonStyle())
                    .accessibilityLabel("Test critical force again on \(group.grip.spoken)")
                    .accessibilityIdentifier("maxes.cf.test.\(group.key)")
                }
            }
        }
    }

    private func ratios(_ record: CriticalForceRecord) -> String {
        var parts: [String] = []
        if let pct = record.percentOfMax { parts.append(String(localized: "\(Int(pct.rounded())) % of max")) }
        if let pct = record.percentOfBodyMass { parts.append(String(localized: "\(Int(pct.rounded())) % of body weight")) }
        return parts.joined(separator: " · ")
    }

    /// "Tested 3 weeks ago · up 1.2 kg since 12 Jul" — like a max card's line.
    private func progressLine(_ group: Series) -> String {
        let latest = group.records.last!
        var parts = [String(localized: "Tested \(latest.recordedAt.formatted(.relative(presentation: .named)))")]
        if group.records.count >= 2 {
            let previous = group.records[group.records.count - 2]
            let delta = latest.criticalForceKg - previous.criticalForceKg
            let when = previous.recordedAt.formatted(.dateTime.day().month(.abbreviated))
            if abs(delta) >= 0.05 {
                let verb = delta > 0 ? String(localized: "up") : String(localized: "down")
                parts.append(String(localized: "\(verb) \(weightUnit.number(abs(delta))) \(weightUnit.symbol) since \(when)"))
            } else {
                parts.append(String(localized: "held since \(when)"))
            }
        }
        return parts.joined(separator: " · ")
    }

    private func chart(_ group: Series) -> some View {
        Chart(group.records) { record in
            LineMark(x: .value("Date", record.recordedAt),
                     y: .value("Critical force", weightUnit.fromKg(record.criticalForceKg)))
                .interpolationMethod(.monotone)
                .foregroundStyle(Accent.bleu)
                .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
            PointMark(x: .value("Date", record.recordedAt),
                      y: .value("Critical force", weightUnit.fromKg(record.criticalForceKg)))
                .foregroundStyle(Accent.bleu)
                .symbolSize(24)
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: dynamicTypeSize.isAccessibilitySize ? 2 : 3)) { _ in
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
        .frame(height: chartHeight)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(group.grip.spoken): \(progressLine(group))")
    }
}

/// Every test on one grip and hand, newest first. A real `List`, so delete is the house
/// full swipe with ten seconds of Undo, and a row opens the test as it was saved.
struct CriticalForceHistorySheet: View {
    let testKey: String
    let title: String

    @Query(sort: [SortDescriptor(\CriticalForceRecord.recordedAt, order: .reverse)])
    private var all: [CriticalForceRecord]
    @Environment(TemplateStore.self) private var templates
    @Environment(\.dismiss) private var dismiss
    @Environment(\.weightUnit) private var weightUnit
    @State private var undoTick = 0

    private var records: [CriticalForceRecord] { all.filter { $0.testKey == testKey } }

    var body: some View {
        NavigationStack {
            List {
                ForEach(records) { record in
                    NavigationLink {
                        ScrollView {
                            CriticalForceResultView(summary: CriticalForceSummary(record))
                                .padding(.horizontal, Metrics.hPadding)
                                .padding(.vertical, 16)
                                .frame(maxWidth: Metrics.maxContentWidth)
                                .frame(maxWidth: .infinity)
                        }
                        .background { AppBackground() }
                        .navigationTitle(record.recordedAt.formatted(date: .abbreviated, time: .omitted))
                        .navigationBarTitleDisplayMode(.inline)
                    } label: {
                        row(record)
                    }
                    // History's Music-style row: tests are things that happened, not
                    // separate documents. No artwork, so the separator runs from the text.
                    .sessionListRow(leadingInset: 0)
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            withAnimation { _ = templates.deleteCriticalForce(record) }
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background { AppBackground() }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if templates.lastDeletedCriticalForce != nil {
                    UndoBar(message: String(localized: "Test deleted")) {
                        templates.undoDeleteCriticalForce()
                        undoTick += 1
                    }
                    .padding(.horizontal, Metrics.hPadding)
                    .padding(.bottom, 8)
                }
            }
            .sensoryFeedback(.success, trigger: undoTick)
        }
        .onDisappear { templates.dismissCriticalForceUndo() }
    }

    private func row(_ record: CriticalForceRecord) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(record.recordedAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.system(.subheadline, weight: .medium))
                    .foregroundStyle(Ink.primary)
                Text(record.percentOfMax.map { String(localized: "\(Int($0.rounded())) % of max") }
                     ?? String(localized: "\(record.repsRun) pulls"))
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.tertiary)
            }
            Spacer(minLength: 8)
            Text(weightUnit.text(record.criticalForceKg))
                .font(.system(.title3, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
        }
        .padding(.vertical, 6)
    }
}
