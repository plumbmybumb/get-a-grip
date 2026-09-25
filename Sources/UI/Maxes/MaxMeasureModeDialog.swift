// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

extension View {
    /// The one question before a measurement on a grip: a max (one hand at a time, or
    /// both together), or, where offered, a critical force test (Nuri, 2026-09-25: "when
    /// you hit measure… shouldn't it ask if you are measuring CF or max?"). Asked up front
    /// because it decides what the visit can save — two per-hand values, or one combined
    /// value that is never derived from two separate hands.
    ///
    /// `onCriticalForce` nil hides the third choice: a new-max form is about a max.
    func maxMeasureModeDialog(for grip: Binding<GripSpec?>,
                              onChoose: @escaping (GripSpec, Side) -> Void,
                              onCriticalForce: ((GripSpec) -> Void)? = nil) -> some View {
        confirmationDialog(onCriticalForce == nil ? "How are you measuring?" : "What are you measuring?",
                           isPresented: Binding(get: { grip.wrappedValue != nil },
                                                set: { if !$0 { grip.wrappedValue = nil } }),
                           titleVisibility: .visible,
                           presenting: grip.wrappedValue) { chosen in
            Button(onCriticalForce == nil ? "One hand at a time" : "Max, one hand at a time") {
                onChoose(chosen, .left)
            }
            .accessibilityIdentifier("max.mode.hands")
            Button(onCriticalForce == nil ? "Both hands together" : "Max, both hands together") {
                onChoose(chosen, .both)
            }
            .accessibilityIdentifier("max.mode.both")
            if let onCriticalForce {
                Button("Critical force test") { onCriticalForce(chosen) }
                    .accessibilityIdentifier("max.mode.criticalForce")
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text(onCriticalForce == nil
                 ? "Pull as many times as you like. Each hand keeps its hardest pull."
                 : "A max is your hardest pull. Critical force is the four-minute endurance test.")
        }
    }
}
