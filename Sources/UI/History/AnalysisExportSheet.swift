// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreTransferable
import Foundation
import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// Freeze an ADDRESS at the tap; fetch, decode and format off the main actor.
///
/// The tap copies nothing that scales with history — the sheet opens at once and the
/// worker reads the store behind its "Preparing export…" line.
struct AnalysisExportRequest: Identifiable {
    /// What the one-workout header says, read off the one row at the tap.
    struct Workout {
        let name: String
        let day: DayStamp
    }

    let id = UUID()
    let worker: AnalysisExportWorker
    let workout: Workout?
    var isWorkout: Bool { workout != nil }

    init(source: AnalysisExportAssembler.Source, workout: Workout? = nil) {
        self.worker = AnalysisExportWorker(source: source)
        self.workout = workout
    }
}

struct AnalysisExportSheet: View {
    let request: AnalysisExportRequest
    var onClose: () -> Void

    @State private var scope = AnalysisExport.CSVScope.recent
    @State private var detail = AnalysisExport.CSVDetail.summary
    @State private var preparedDocument: (key: String, value: AnalysisExport.CSVDocument)?

    private var selectionKey: String {
        request.id.uuidString + scope.rawValue + detail.rawValue
    }

    private var document: AnalysisExport.CSVDocument? {
        // Hide the old actions in the same render as a selection change, before
        // the replacement task has had an opportunity to start.
        preparedDocument?.key == selectionKey ? preparedDocument?.value : nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let workout = request.workout {
                        Text(workout.name)
                            .font(.headline)
                        Text(AnalysisExport.isoDay(workout.day))
                            .font(.subheadline).foregroundStyle(Ink.secondary)
                    } else {
                        Picker("Date range", selection: $scope) {
                            Text("Last 8 weeks").tag(AnalysisExport.CSVScope.recent)
                            Text("All history").tag(AnalysisExport.CSVScope.all)
                        }
                        .pickerStyle(.segmented)
                    }
                    Picker("Export detail", selection: $detail) {
                        Text("Summary").tag(AnalysisExport.CSVDetail.summary)
                        Text("Every pull").tag(AnalysisExport.CSVDetail.pulls)
                    }
                    .pickerStyle(.segmented)
                    Text("A compact CSV for your AI assistant or spreadsheet. Summary groups pulls by set and hand. Choose Every pull for individual measurements and timing.")
                        .font(.subheadline)
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let document {
                        if document.isEmpty {
                            Text("Nothing to export in this range.")
                                .font(.subheadline).foregroundStyle(Ink.secondary)
                        } else {
                            Text("Workouts: \(document.sessionCount) · Pulls: \(document.pullCount) · Maxes: \(document.maxCount)")
                                .font(.footnote).monospacedDigit().foregroundStyle(Ink.secondary)
                            Text("CSV · \(ByteCountFormatter.string(fromByteCount: Int64(document.byteCount), countStyle: .file))")
                                .font(.footnote).foregroundStyle(Ink.tertiary)
                            Text("Column names and the data guide stay in English. Your notes stay as written.")
                                .font(.footnote).foregroundStyle(Ink.tertiary)
                        }
                    } else {
                        ProgressView("Preparing export…")
                    }
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 12)
                .padding(.bottom, 12)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .background { AppBackground() }
            .navigationTitle(request.isWorkout ? String(localized: "Export workout") : String(localized: "Export for analysis"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { onClose() }
                }
            }
            // In the safe area: at the `.medium` detent an in-document button sits in the
            // home-indicator strip and dragging toward it resizes the sheet.
            .safeAreaInset(edge: .bottom) { actions }
        }
        .presentationDetents([.medium, .large])
        .task(id: selectionKey) {
            let requestedKey = selectionKey
            preparedDocument = nil
            let selectedDetail = detail
            let selected = request.isWorkout ? AnalysisExport.CSVScope.workout : scope
            let result = try? await request.worker.document(scope: selected, detail: selectedDetail)
            guard !Task.isCancelled else { return }
            if let result { preparedDocument = (requestedKey, result) }
        }
    }

    /// Nothing at all when there is nothing to export: a disabled Share button is a dead
    /// control explaining itself with grey.
    @ViewBuilder
    private var actions: some View {
        if let document, !document.isEmpty {
            VStack(spacing: 10) {
                // Glass is allowed: a sheet carries no `.contextMenu`, so nothing is lifted.
                ShareLink(
                    item: AnalysisExportFile(text: document.text, filename: document.filename),
                    preview: SharePreview("Get a Grip — training export")
                ) {
                    actionLabel(String(localized: "Share CSV"),
                                systemImage: "square.and.arrow.up", prominent: true)
                }
                .buttonStyle(.glassProminent)
                .tint(Accent.graphite)

                CopyButton(text: { document.text },
                           accessibilityLabel: "Copy the whole document to the clipboard") { copied in
                    actionLabel(copied ? String(localized: "Copied") : String(localized: "Copy"),
                                systemImage: copied ? "checkmark" : "doc.on.doc",
                                prominent: false)
                }
                .buttonStyle(.glass)
                .tint(Accent.graphite)
            }
            .padding(.horizontal, Metrics.hPadding)
            .padding(.bottom, 4)
            .frame(maxWidth: Metrics.maxContentWidth)
            .frame(maxWidth: .infinity)
        }
    }

    /// `prominent` picks the ink — see `RoutineShareSheet.actionLabel`.
    private func actionLabel(_ title: String, systemImage: String?,
                             prominent: Bool) -> some View {
        HStack(spacing: 8) {
            if let systemImage { Image(systemName: systemImage) }
            Text(title)
        }
        .font(.system(.headline, weight: .semibold))
        .foregroundStyle(prominent
                         ? Color.adaptive(Color(hex: "FFFFFF"), Color(hex: "1B1F25"))
                         : Accent.graphite)
        // Full-width and padded: the drawn label is not the hit area.
        .actionLabelLayout(minHeight: Metrics.buttonHeight, fullWidth: true)
        .contentShape(.rect)
    }
}

/// A known system text type plus a string fallback avoids sharing a sandbox URL.
struct AnalysisExportFile: Transferable, Sendable {
    let text: String
    let filename: String

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .commaSeparatedText) { item in
            Data(item.text.utf8)
        }
        .suggestedFileName { $0.filename }
        // Belt and braces: text receivers (message fields, assistants) take the
        // document as a string and never get a sandbox path they cannot read.
        // File-capable destinations still prefer the named file.
        ProxyRepresentation(exporting: \.text)
    }
}
