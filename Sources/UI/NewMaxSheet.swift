// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Choose a grip once, then measure or type its hand values. The grip remains a value;
/// opening or cancelling this screen never creates a record or a library entry.
struct NewMaxSheet: View {
    @Environment(TemplateStore.self) private var templates
    @State private var grip: GripSpec
    @State private var capture: Capture?
    @State private var editing = false
    @State private var shared = false
    @State private var saved = false
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
                            capture = Capture(grip: grip, side: .left)
                        }
                        SecondaryGlassButton(title: String(localized: "Enter by hand"), systemImage: "pencil") {
                            editing = true
                        }
                        Text("Measure your left and right hands in one visit, or enter the values you already know.")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.secondary)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
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
                        Button("Measure both hands together") {
                            capture = Capture(grip: grip, side: .both)
                        }
                        Button("One value for both hands") { shared = true }
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    .accessibilityLabel("More max options")
                }
            }
            .fullScreenCover(item: $capture, onDismiss: closeAfterSave) { target in
                MaxMeasureView(grip: target.grip, initialSide: target.side) { readings in
                    let success = templates.recordMaxes(readings.map {
                        .init(grip: target.grip, side: $0.side, kg: $0.kg, source: .measured)
                    })
                    if success { saved = true }
                    return success
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

    private func closeAfterSave() {
        if saved { onClose() }
    }
}
