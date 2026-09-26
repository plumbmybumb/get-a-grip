// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Choose a grip once, then measure or type its hand values. The grip remains a value;
/// opening or cancelling this screen never creates a record or a library entry.
struct NewMaxSheet: View {
    @Environment(TemplateStore.self) private var templates
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var grip: GripSpec
    @State private var capture: Capture?
    @State private var choosingMode: GripSpec?
    @State private var editing = false
    @State private var shared = false
    @State private var saved = false
    @State private var gripSelectionTick = 0
    var onClose: () -> Void

    init(seed: GripSpec, onClose: @escaping () -> Void) {
        _grip = State(initialValue: seed)
        self.onClose = onClose
    }

    private struct Capture: Identifiable {
        let grip: GripSpec
        let side: Side
        var id: String { MaxTable.key(grip: grip.key, side: side) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(spacing: 12) {
                        FingerGlyph(fingers: grip.fingers, position: grip.position, dot: 18, gap: 7)
                            .accessibilityHidden(true)
                        Text(grip.displayName)
                            .font(.system(.headline))
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)

                    if !templates.recentGrips.isEmpty { recentGrips }

                    IntValueRow(title: String(localized: "Edge"), unit: String(localized: "mm"),
                                value: $grip.edgeMM, range: 4...45, limit: GripSpec.edgeRange,
                                presets: [6, 10, 20, 30])
                    VStack(alignment: .leading, spacing: 8) {
                        CapsLabel(String(localized: "FINGERS"))
                        FingerPips(fingers: $grip.fingers, position: grip.position)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        CapsLabel(String(localized: "GRIP"))
                        PositionChipRow(selection: $grip.position)
                    }
                    VStack(spacing: 12) {
                        PrimaryGlassButton(title: String(localized: "Measure on the gauge"), tint: Accent.graphite) {
                            choosingMode = grip
                        }
                        .accessibilityIdentifier("newMax.measure")
                        SecondaryGlassButton(title: String(localized: "Enter by hand"), systemImage: "pencil") {
                            editing = true
                        }
                    }
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.vertical, 20)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .background { AppBackground() }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle("New max")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onClose)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("One value for both hands") { shared = true }
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    .accessibilityLabel("More max options")
                }
            }
            .maxMeasureModeDialog(for: $choosingMode) { chosen, side in
                capture = Capture(grip: chosen, side: side)
            }
            .fullScreenCover(item: $capture, onDismiss: closeAfterSave) { target in
                MaxMeasureView(grip: target.grip, initialSide: target.side) { readings in
                    let receipt = templates.recordMaxesWithReceipt(readings.map {
                        .init(grip: target.grip, side: $0.side, kg: $0.kg, source: $0.source)
                    })
                    if receipt != nil { saved = true }
                    return receipt
                }
            }
            .sheet(isPresented: $editing, onDismiss: closeAfterSave) {
                MaxEditSheet(grip: grip, onSaved: { saved = true }) { editing = false }
            }
            .sheet(isPresented: $shared, onDismiss: closeAfterSave) {
                MaxEntrySheet(seed: grip, side: .both, onSaved: { saved = true }) { shared = false }
            }
        }
    }

    /// These are the grips already present in routines, used only to seed this form.
    /// Selecting one creates no saved grip or max and leaves every field editable.
    private var recentGrips: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "START FROM"))
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(templates.recentGrips, id: \.key) { candidate in
                        let selected = candidate.key == grip.key
                        Button {
                            guard !selected else { return }
                            withAnimation(Motion.state(reduceMotion)) { grip = candidate }
                            gripSelectionTick += 1
                        } label: {
                            HStack(spacing: 8) {
                                FingerGlyph(fingers: candidate.fingers,
                                            position: candidate.position, dot: 5.5, gap: 2)
                                Text(candidate.shortName)
                                    .font(.system(.caption, weight: .semibold))
                                    .fixedSize()
                            }
                            .foregroundStyle(selected ? Ink.primary : Ink.secondary)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 44)
                            .background(Ink.primary.opacity(selected ? 0.10 : 0.04),
                                        in: RoundedRectangle(cornerRadius: Metrics.radiusInner,
                                                             style: .continuous))
                            .contentShape(.rect(cornerRadius: Metrics.radiusInner))
                        }
                        .buttonStyle(PressFeedbackButtonStyle())
                        .accessibilityLabel(candidate.spoken)
                        .accessibilityAddTraits(selected ? [.isSelected] : [])
                        .accessibilityIdentifier("newMax.grip.\(candidate.key)")
                    }
                }
                .padding(.vertical, 2)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
            .accessibilityIdentifier("newMax.recentGrips")
        }
        .sensoryFeedback(.selection, trigger: gripSelectionTick)
    }

    private func closeAfterSave() {
        if saved { onClose() }
    }
}
