// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Body weight as a number you TYPE, never a slider (Nuri, 2026-09-25): a slider is a
/// poor way to state an exact personal number, and a track whose far end reads as a
/// verdict on your body is worse. Empty until entered, with no invented default; in the
/// user's weight unit; the decimal keypad with a Done key.
struct BodyWeightField: View {
    /// Kilograms. nil = not entered.
    @Binding var kilograms: Double?
    var title: String = String(localized: "Body weight")
    /// A card title in Settings, a row label inside a form.
    var titleFont: Font = .system(.subheadline, weight: .medium)

    @Environment(\.weightUnit) private var weightUnit
    @FocusState private var focused: Bool
    @State private var text = ""

    /// Stored kilograms are clamped to what a person can weigh; a typo is not a weight.
    private static let limit: ClosedRange<Double> = 25...250

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(title)
                .font(titleFont)
                .foregroundStyle(Ink.primary)
            Spacer(minLength: 12)
            TextField("—", text: $text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .font(.system(.title3, weight: .semibold))
                .monospacedDigit()
                .focused($focused)
                .frame(minWidth: 60, maxWidth: 110)
                .accessibilityLabel(title)
                .accessibilityIdentifier("bodyWeight.field")
            Text(weightUnit.symbol)
                .font(.system(.subheadline))
                .foregroundStyle(Ink.tertiary)
        }
        .frame(minHeight: 44)
        .contentShape(.rect)
        .onTapGesture { focused = true }
        .onAppear { text = display(kilograms) }
        .onChange(of: kilograms) { _, kg in if !focused { text = display(kg) } }
        .onChange(of: weightUnit) { _, _ in text = display(kilograms) }
        .onChange(of: focused) { _, isFocused in if !isFocused { commit() } }
        .onSubmit(commit)
        .toolbar {
            if focused {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focused = false }
                }
            }
        }
    }

    private func display(_ kg: Double?) -> String {
        kg.map { weightUnit.fromKg($0).formatted(.number.precision(.fractionLength(0...1))) } ?? ""
    }

    /// An empty or unreadable entry keeps what was there, rather than wiping a stored weight.
    private func commit() {
        let normalized = text.replacingOccurrences(of: ",", with: ".")
        guard let typed = Double(normalized), typed.isFinite, typed > 0 else {
            text = display(kilograms)
            return
        }
        let kg = min(max(weightUnit.toKg(typed), Self.limit.lowerBound), Self.limit.upperBound)
        kilograms = kg
        text = display(kg)
    }
}
