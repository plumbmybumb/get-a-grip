// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Optional detail after a successful max save. Saving the benchmark already happened;
/// only an explicit scale action here is allowed to change typed weight targets.
struct MaxSaveReceiptView: View {
    let receipt: TemplateStore.MaxSaveReceipt
    var onDone: () -> Void

    @Environment(TemplateStore.self) private var templates
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var scaled: Set<String> = []
    @State private var failed: Set<String> = []
    @State private var savedTick = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Metrics.spacing) {
                    savedValues
                    if !receipt.percentMoves.isEmpty { percentageChanges }
                    ForEach(receipt.rescaleOffers) { offer in
                        weightOffer(offer)
                    }
                    if receipt.rescaleOffers.contains(where: { !scaled.contains($0.id) }) {
                        Button("Leave them as they are", action: onDone)
                            .font(.system(.footnote, weight: .semibold))
                            .foregroundStyle(Accent.graphite)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(.rect)
                            .buttonStyle(PressFeedbackButtonStyle())
                            .accessibilityIdentifier("max.receipt.leave")
                    }
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.vertical, 16)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .background { AppBackground() }
            .navigationTitle("Saved")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDone)
                        .bold()
                        .accessibilityIdentifier("max.receipt.done")
                }
            }
        }
        .accessibilityIdentifier("max.receipt")
        .sensoryFeedback(.success, trigger: savedTick)
    }

    private var savedValues: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "SAVED"))
                ForEach(receipt.values, id: \.self) { value in
                    let layout = dynamicTypeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                        : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 12))
                    layout {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(value.side == .both ? String(localized: "Shared max") : value.side.name)
                                .font(.system(.headline, weight: .semibold))
                                .foregroundStyle(Ink.primary)
                            Text(value.grip.displayName)
                                .font(.footnote)
                                .foregroundStyle(Ink.secondary)
                        }
                        .fixedSize(horizontal: false, vertical: true)
                        if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 8) }
                        Text(weightUnit.text(value.kg))
                            .font(.system(.title2, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(Ink.primary)
                            .fixedSize(horizontal: !dynamicTypeSize.isAccessibilitySize, vertical: true)
                    }
                }
            }
        }
    }

    private var percentageChanges: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "TARGETS THAT FOLLOWED"))
                ForEach(receipt.percentMoves) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.move.side == .both ? item.move.routineName :
                                "\(item.move.routineName) · \(item.move.side.name)")
                            .font(.system(.subheadline, weight: .semibold))
                            .foregroundStyle(Ink.primary)
                        if Set(receipt.values.map { $0.grip.key }).count > 1 {
                            Text(item.grip.displayName)
                                .font(.footnote)
                                .foregroundStyle(Ink.secondary)
                        }
                        Text(item.move.line(unit: weightUnit))
                            .font(.footnote)
                            .monospacedDigit()
                            .foregroundStyle(Ink.secondary)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("max.receipt.percent.\(item.id)")
                }
                Text("Percent targets always follow your newest max — nothing to do.")
                    .font(.footnote)
                    .foregroundStyle(Ink.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func weightOffer(_ offer: TemplateStore.MaxSaveReceipt.RescaleOffer) -> some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(String(localized: "Weight targets").uppercased())
                ForEach(offer.routines) { routine in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(routine.routineName)
                            .font(.system(.subheadline, weight: .semibold))
                            .foregroundStyle(Ink.primary)
                        ForEach(routine.moves, id: \.self) { move in
                            Text(String(localized: "\(weightUnit.bandText(move.oldBand, withUnit: false)) \(weightUnit.symbol)  →  \(weightUnit.bandText(move.newBand, withUnit: false)) \(weightUnit.symbol)"))
                                .font(.footnote)
                                .monospacedDigit()
                                .foregroundStyle(Ink.secondary)
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
                if scaled.contains(offer.id) {
                    Label("Weight targets updated", systemImage: "checkmark.circle.fill")
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(Ink.secondary)
                        .accessibilityIdentifier("max.receipt.scaled.\(offer.id)")
                } else {
                    Text("These were typed by hand, so they never move on their own. Scale them with the new max, or leave them.")
                        .font(.footnote)
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    PrimaryGlassButton(title: String(localized: "Scale with the new max"), tint: Accent.graphite) {
                        if templates.applyMaxRescale(offer) {
                            scaled.insert(offer.id)
                            failed.remove(offer.id)
                            savedTick += 1
                        } else {
                            failed.insert(offer.id)
                        }
                    }
                    .accessibilityIdentifier("max.receipt.scale.\(offer.id)")
                    if failed.contains(offer.id) {
                        Text("Couldn’t update the weight targets. Your maxes are saved. Try again, or leave the targets as they are.")
                            .font(.footnote)
                            .foregroundStyle(Accent.alarm)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("max.receipt.scaleFailed.\(offer.id)")
                    }
                }
            }
        }
    }
}
