// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The pulls of one visit, per hand, and the one each hand will save.
///
/// Each hand keeps its hardest pull; tap another to keep that one instead. A pull made
/// with the wrong hand selected moves across with a swipe or its menu — the commonest
/// slip in a two-hand visit — and a bad one can be deleted. Nothing is written until Save.
struct MaxAttemptReviewSheet: View {
    let session: LiveMaxSession
    var saveFailed: Bool
    var onSave: () -> Void
    var onClose: () -> Void

    @Environment(\.weightUnit) private var weightUnit
    @State private var adjusting = false
    @State private var pickTick = 0

    var body: some View {
        let draft = session.snapshot
        NavigationStack {
            List {
                ForEach(draft.sides, id: \.self) { side in
                    section(side, draft: draft)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background { AppBackground() }
            .safeAreaBar(edge: .bottom) { footer(draft) }
            .navigationTitle("Review pulls")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Keep pulling", action: onClose)
                        .tint(Accent.graphite)
                        .accessibilityIdentifier("max.review.back")
                }
            }
        }
        .presentationDetents([.large])
        .sensoryFeedback(.selection, trigger: pickTick)
        .sheet(isPresented: $adjusting) {
            MaxMeasurementAdjustmentSheet(results: draft.results,
                                          measuredPeaks: Dictionary(uniqueKeysWithValues: draft.results.compactMap { result in
                draft.measuredPeak(for: result.side).map { (result.side, $0) }
            }), onApply: { values in
                if session.correct(values) { adjusting = false }
            }, onCancel: { adjusting = false })
        }
    }

    @ViewBuilder
    private func section(_ side: Side, draft: MaxMeasurementDraft) -> some View {
        let attempts = draft.log.attempts(for: side)
        let kept = draft.kept(for: side)?.id
        let best = draft.log.best(for: side)?.id
        Section {
            if attempts.isEmpty {
                Text("No pulls with this hand.")
                    .font(.system(.subheadline))
                    .foregroundStyle(Ink.tertiary)
            }
            ForEach(Array(attempts.enumerated()), id: \.element.id) { index, attempt in
                row(attempt, number: index + 1, kept: attempt.id == kept, best: attempt.id == best,
                    bothTogether: draft.bothTogether)
            }
        } header: {
            Text(handTitle(side))
        } footer: {
            if let peak = draft.peak(for: side) {
                Text(draft.isCorrected(side)
                     ? String(localized: "Saves \(weightUnit.text(peak)), adjusted by hand.")
                     : String(localized: "Saves \(weightUnit.text(peak))."))
                    .accessibilityIdentifier("max.review.saving.\(side.rawValue)")
            }
        }
    }

    private func row(_ attempt: MaxAttemptLog.Attempt, number: Int, kept: Bool, best: Bool,
                     bothTogether: Bool) -> some View {
        Button {
            guard !kept else { return }
            session.pick(attempt.id)
            pickTick += 1
        } label: {
            HStack(spacing: 10) {
                Image(systemName: kept ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(kept ? Accent.bleu : Ink.tertiary)
                    .font(.system(.body))
                Text("Pull \(number)")
                    .foregroundStyle(Ink.primary)
                if best {
                    CapsLabel(String(localized: "Best"), size: 11, tint: Ink.secondary)
                }
                Spacer(minLength: 8)
                Text(weightUnit.text(attempt.peakKg))
                    .font(.system(.body, weight: kept ? .semibold : .regular))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(kept ? [.isSelected] : [])
        .accessibilityIdentifier("max.review.pull.\(attempt.id)")
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { session.remove(attempt.id) } label: {
                Label("Delete", systemImage: "trash")
            }
            if !bothTogether {
                Button { session.move(attempt.id, to: attempt.side.other) } label: {
                    Label(moveTitle(attempt.side), systemImage: "arrow.left.arrow.right")
                }
                .tint(Accent.graphite)
            }
        }
        .contextMenu {
            if !bothTogether {
                Button(moveTitle(attempt.side), systemImage: "arrow.left.arrow.right") {
                    session.move(attempt.id, to: attempt.side.other)
                }
            }
            Button("Delete", systemImage: "trash", role: .destructive) { session.remove(attempt.id) }
        }
    }

    private func footer(_ draft: MaxMeasurementDraft) -> some View {
        VStack(spacing: 10) {
            if saveFailed {
                Text("Couldn’t save. Your pulls are still here — try again.")
                    .font(.footnote)
                    .foregroundStyle(Accent.alarm)
                    .multilineTextAlignment(.center)
            }
            PrimaryGlassButton(title: draft.results.count == 1 ? String(localized: "Save max")
                                                               : String(localized: "Save maxes"),
                               systemImage: "checkmark", tint: Accent.graphite, action: onSave)
                .disabled(draft.results.isEmpty)
                .accessibilityIdentifier("max.review.save")
            SecondaryGlassButton(title: String(localized: "Adjust values"), systemImage: "pencil") {
                adjusting = true
            }
            .disabled(draft.results.isEmpty)
            .accessibilityIdentifier("max.measure.adjust")
            Text(draft.results.contains { $0.source == .manual }
                 ? String(localized: "Adjusted values are saved as manual entries. Your recorded trace stays unchanged.")
                 : String(localized: "Saved maxes update percentage targets. Weight targets stay as entered."))
                .font(.caption)
                .foregroundStyle(Ink.tertiary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, Metrics.hPadding)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    private func handTitle(_ side: Side) -> String {
        switch side {
        case .left: String(localized: "Left hand")
        case .right: String(localized: "Right hand")
        case .both: String(localized: "Both hands together")
        }
    }

    private func moveTitle(_ from: Side) -> String {
        from == .left ? String(localized: "Move to right hand") : String(localized: "Move to left hand")
    }
}
