// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreTransferable
import Foundation
import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// Freeze model values at the tap; format the selected range off the main actor.
struct AnalysisExportRequest: Identifiable {
    let id = UUID()
    let snapshot: AnalysisExportAssembler.Snapshot
    let worker: AnalysisExportWorker
    var isWorkout = false

    init(snapshot: AnalysisExportAssembler.Snapshot, isWorkout: Bool = false) {
        self.snapshot = snapshot
        self.worker = AnalysisExportWorker(snapshot: snapshot)
        self.isWorkout = isWorkout
    }
}

struct AnalysisExportSheet: View {
    let request: AnalysisExportRequest
    var onClose: () -> Void

    @State private var scope = AnalysisExport.CSVScope.recent
    @State private var detail = AnalysisExport.CSVDetail.summary
    @State private var preparedDocument: (key: String, value: AnalysisExport.CSVDocument)?
    @State private var copiedTick = 0
    /// Reverts after two seconds, like the diagnostics Copy button: the document is worth
    /// re-copying, and a label stuck on "Copied" forever acknowledges nothing.
    @State private var justCopied = false
    @State private var copyResetTask: Task<Void, Never>?

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
                    if request.isWorkout, let session = request.snapshot.sessions.first?.metadata {
                        Text(session.routineName)
                            .font(.headline)
                        Text(AnalysisExport.isoDay(session.day))
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
            // In the safe area rather than at the foot of the document — at the `.medium`
            // detent this opens on, an in-document button sits inside the home-indicator
            // strip and dragging toward it resizes the sheet instead of scrolling.
            .safeAreaInset(edge: .bottom) { actions }
        }
        .presentationDetents([.medium, .large])
        .task(id: selectionKey) {
            let requestedKey = selectionKey
            preparedDocument = nil
            justCopied = false
            let selectedDetail = detail
            let selected = request.isWorkout ? AnalysisExport.CSVScope.workout : scope
            let result = try? await request.worker.document(scope: selected, detail: selectedDetail)
            guard !Task.isCancelled else { return }
            if let result { preparedDocument = (requestedKey, result) }
        }
        .onDisappear { copyResetTask?.cancel() }
    }

    /// Nothing at all when there is nothing to export — a disabled Share button on an
    /// empty history is a dead control explaining itself with grey.
    @ViewBuilder
    private var actions: some View {
        if let document, !document.isEmpty {
            VStack(spacing: 10) {
                // Glass is allowed: a sheet is its own presentation and carries no
                // `.contextMenu`, so nothing here is ever lifted out from under it.
                ShareLink(
                    item: AnalysisExportFile(text: document.text, filename: document.filename),
                    preview: SharePreview("Get a Grip — training export")
                ) {
                    actionLabel(String(localized: "Share CSV"),
                                systemImage: "square.and.arrow.up", prominent: true)
                }
                .buttonStyle(.glassProminent)
                .tint(Accent.graphite)

                Button {
                    UIPasteboard.general.string = document.text
                    copiedTick += 1
                    justCopied = true
                    copyResetTask?.cancel()
                    copyResetTask = Task {
                        try? await Task.sleep(for: .seconds(2))
                        guard !Task.isCancelled else { return }
                        justCopied = false
                    }
                } label: {
                    actionLabel(justCopied ? String(localized: "Copied") : String(localized: "Copy"),
                                systemImage: justCopied ? "checkmark" : "doc.on.doc",
                                prominent: false)
                }
                .buttonStyle(.glass)
                .tint(Accent.graphite)
                .accessibilityLabel("Copy the whole document to the clipboard")
                .sensoryFeedback(.success, trigger: copiedTick)
            }
            .padding(.horizontal, Metrics.hPadding)
            .padding(.bottom, 4)
            .frame(maxWidth: Metrics.maxContentWidth)
            .frame(maxWidth: .infinity)
        }
    }

    /// `prominent` picks the ink: the INVERSE ink is correct only over
    /// `.glassProminent`'s opaque graphite fill — on plain glass it renders white on the
    /// light slate field, the white-on-white trap one colour scheme over.
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
        // Full-width and padded, so the drawn label is nowhere near the hit area SwiftUI
        // would infer from it on its own.
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
        // The belt to the file's braces: receivers that read TEXT (message fields,
        // assistant apps) take the whole document as a string and can never end up
        // holding a path into a sandbox they cannot read. File-capable destinations
        // still prefer the named file above.
        ProxyRepresentation(exporting: \.text)
    }
}
