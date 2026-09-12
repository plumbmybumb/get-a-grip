// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Editing a working max appends a record; it never changes a past measurement.
/// Each hand is independent, including whether it has any value of its own yet.
struct MaxEditSheet: View {
    let grip: GripSpec
    var onSaved: (() -> Void)? = nil
    var onClose: () -> Void

    @Environment(TemplateStore.self) private var templates
    @Environment(\.weightUnit) private var weightUnit
    @State private var draft = MaxEditDraft()
    @State private var loaded = false
    @State private var failed = false
    @State private var editingShared = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Metrics.spacing) {
                    gripHeader
                    handValues
                    if let sharedKg { sharedValue(sharedKg) }
                    earlierRecords
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 16)
                .padding(.bottom, Metrics.spacing)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .background { AppBackground() }
            .navigationTitle("Edit maxes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onClose() }
                        .accessibilityIdentifier("maxEdit.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .bold()
                        .disabled(!loaded || !draft.canSave)
                        .accessibilityIdentifier("maxEdit.save")
                }
            }
            .sheet(isPresented: $editingShared) {
                MaxEntrySheet(seed: grip, side: .both) { editingShared = false }
            }
        }
        .onAppear { loadOnce() }
        .onChange(of: templates.maxTable) { _, current in
            guard loaded else { return }
            draft.rebase(leftKg: current.exact(grip: grip.key, side: .left),
                         rightKg: current.exact(grip: grip.key, side: .right))
        }
    }

    private var gripHeader: some View {
        HStack(spacing: 14) {
            FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 10, gap: 4)
                .padding(12)
                .background(Ink.primary.opacity(0.05),
                            in: RoundedRectangle(cornerRadius: Metrics.radiusInner,
                                                 style: .continuous))
            Text(grip.displayName)
                .font(.system(.title3, weight: .semibold))
                .foregroundStyle(Ink.primary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(grip.spoken)
    }

    private var handValues: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 16) {
                handValue(.left, kilograms: $draft.leftKg)
                Divider()
                handValue(.right, kilograms: $draft.rightKg)
                Text("Percentage targets follow the values you save. Earlier records stay in your history.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if draft.hasInvalidChanges {
                    Text("Enter a max above zero. To remove a record, open Earlier records.")
                        .font(.system(.footnote))
                        .foregroundStyle(Accent.alarm)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if failed {
                    Text("Couldn’t save your maxes. Your changes are still here—please try again.")
                        .font(.system(.footnote))
                        .foregroundStyle(Accent.alarm)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func handValue(_ side: Side, kilograms: Binding<Double>) -> some View {
        ValueRow(
            title: side == .left ? String(localized: "Left hand") : String(localized: "Right hand"),
            unit: weightUnit.symbol,
            value: weightUnit.binding(kilograms),
            range: weightUnit.sliderRangeFromKg(0...100),
            limit: weightUnit.rangeFromKg(0...250),
            step: 0.5,
            decimals: 1,
            caption: draft.originalKg(for: side) == nil && kilograms.wrappedValue == 0
                ? String(localized: "Not set. Enter a value to save a max for this hand.") : nil
        )
    }

    private var sharedKg: Double? {
        templates.currentMaxes[MaxTable.key(grip: grip.key, side: .both)]?.kg
    }

    private func sharedValue(_ kg: Double) -> some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text("Shared max")
                        .font(.system(.subheadline, weight: .semibold))
                    Spacer(minLength: 8)
                    Text(weightUnit.text(kg))
                        .font(.system(.subheadline, weight: .semibold))
                        .monospacedDigit()
                }
                .foregroundStyle(Ink.primary)
                Text("Used when a hand has no max of its own, and for two-handed pulls.")
                    .font(.system(.footnote))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    editingShared = true
                } label: {
                    Text("Edit shared max")
                        .font(.system(.subheadline, weight: .semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(PressFeedbackButtonStyle())
                .foregroundStyle(Accent.graphite)
                .accessibilityIdentifier("maxEdit.shared")
            }
        }
    }

    private var earlierRecords: some View {
        NavigationLink {
            MaxesView(grip: grip)
        } label: {
            HStack(spacing: 12) {
                Text("Earlier records")
                    .font(.system(.subheadline, weight: .semibold))
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
                    .accessibilityHidden(true)
            }
            .foregroundStyle(Accent.graphite)
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityIdentifier("maxEdit.history")
    }

    private func loadOnce() {
        guard !loaded else { return }
        draft = MaxEditDraft(
            leftKg: templates.currentMaxes[MaxTable.key(grip: grip.key, side: .left)]?.kg,
            rightKg: templates.currentMaxes[MaxTable.key(grip: grip.key, side: .right)]?.kg
        )
        loaded = true
    }

    private func save() {
        guard loaded, draft.canSave else { return }
        let values = draft.changes.map {
            TemplateStore.MaxSave(grip: grip, side: $0.side, kg: $0.kg, source: .manual)
        }
        guard templates.recordMaxes(values) else {
            failed = true
            return
        }
        onSaved?()
        onClose()
    }
}
