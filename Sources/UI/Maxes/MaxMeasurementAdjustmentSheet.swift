// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Local correction before persistence. Cancelling leaves the captured draft intact.
struct MaxMeasurementAdjustmentSheet: View {
    @Environment(\.weightUnit) private var weightUnit
    @FocusedValue(\.commitValueField) private var commitValueField: ValueFieldCommitAction?
    @State private var values: [MaxMeasurementResult]
    let measuredPeaks: [Side: Double]
    let onApply: ([MaxMeasurementResult]) -> Void
    let onCancel: () -> Void

    init(results: [MaxMeasurementResult], measuredPeaks: [Side: Double],
         onApply: @escaping ([MaxMeasurementResult]) -> Void, onCancel: @escaping () -> Void) {
        _values = State(initialValue: results)
        self.measuredPeaks = measuredPeaks
        self.onApply = onApply
        self.onCancel = onCancel
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Metrics.spacing) {
                    ForEach(values.indices, id: \.self) { index in
                        let value = values[index]
                        let maximum = max(250, measuredPeaks[value.side] ?? 0)
                        ValueRow(title: handTitle(value.side), unit: weightUnit.symbol,
                                 value: weightUnit.binding(Binding(get: { values[index].kg }, set: {
                            values[index] = MaxMeasurementResult(side: value.side, kg: $0)
                        })), range: weightUnit.sliderRangeFromKg(0...100),
                                 limit: weightUnit.rangeFromKg(0...maximum), step: 0.5, decimals: 1,
                                 caption: String(localized: "Measured: \(weightUnit.text(measuredPeaks[value.side] ?? value.kg))"))
                    }
                    Text("Adjusted values save as manual entries. The trace is unchanged.")
                        .font(.footnote)
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(Metrics.hPadding)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background { AppBackground() }
            .navigationTitle("Adjust values")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                        .accessibilityIdentifier("max.adjust.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        commitValueField?.commit()
                        guard !values.isEmpty, values.allSatisfy({ $0.kg.isFinite && $0.kg > 0 }) else { return }
                        onApply(values)
                    }
                        .bold()
                        .disabled(commitValueField == nil && (values.isEmpty || values.contains { !$0.kg.isFinite || $0.kg <= 0 }))
                        .accessibilityIdentifier("max.adjust.apply")
                }
            }
        }
    }

    private func handTitle(_ side: Side) -> String {
        switch side {
        case .left: String(localized: "Left hand")
        case .right: String(localized: "Right hand")
        case .both: String(localized: "Both hands")
        }
    }
}
