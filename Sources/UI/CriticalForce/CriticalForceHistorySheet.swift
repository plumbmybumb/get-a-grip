// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Charts
import SwiftData
import SwiftUI

/// Every critical force test on one grip, newest first, every hand. A real `List`, so
/// delete is the house full swipe with ten seconds of Undo, and a row opens the test as it
/// was saved. Opened from the grip's card on Benchmarks.
struct CriticalForceHistorySheet: View {
    let gripKey: String
    let title: String

    @Query(sort: [SortDescriptor(\CriticalForceRecord.recordedAt, order: .reverse)])
    private var all: [CriticalForceRecord]
    @Environment(TemplateStore.self) private var templates
    @Environment(\.dismiss) private var dismiss
    @Environment(\.weightUnit) private var weightUnit
    @State private var undoTick = 0

    private var records: [CriticalForceRecord] { all.filter { $0.gripKey == gripKey } }

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
                        .navigationSubtitle(record.side == .both ? String(localized: "Both hands") : record.side.name)
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
                Text(record.side == .both ? String(localized: "Both hands") : record.side.name)
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
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
