// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

extension View {
    /// The one question before a max visit: one hand at a time, or both together. Asked
    /// up front because it decides what the visit can save — two per-hand values, or one
    /// combined value that is never derived from two separate hands.
    func maxMeasureModeDialog(for grip: Binding<GripSpec?>,
                              onChoose: @escaping (GripSpec, Side) -> Void) -> some View {
        confirmationDialog("How are you measuring?",
                           isPresented: Binding(get: { grip.wrappedValue != nil },
                                                set: { if !$0 { grip.wrappedValue = nil } }),
                           titleVisibility: .visible,
                           presenting: grip.wrappedValue) { chosen in
            Button("One hand at a time") { onChoose(chosen, .left) }
                .accessibilityIdentifier("max.mode.hands")
            Button("Both hands together") { onChoose(chosen, .both) }
                .accessibilityIdentifier("max.mode.both")
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Pull as many times as you like. Each hand keeps its hardest pull.")
        }
    }
}
