// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A snapshot at the tap, so a CloudKit update cannot reshuffle the plan while it is
/// being read. Editing still opens the current stored routine through its stable ID.
struct RoutineOverviewRequest: Identifiable {
    let id: UUID
    let plan: SessionPlan
    let summary: RoutineSummary
}

/// Preparation, in execution order. This reads the same timing and count helpers as
/// the runner, without allocating a record or a view for each planned pull.
struct RoutineOverviewSheet: View {
    let request: RoutineOverviewRequest
    var onEdit: () -> Void
    var onClose: () -> Void

    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var typeSize

    private var plan: SessionPlan { request.plan }
    private var sets: [SetPlan] { plan.executable.sets }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 22) {
                    identity
                    preparation
                    flow
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 16)
                .padding(.bottom, 28)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .accessibilityIdentifier("routine.overview.scroll")
            .background { AppBackground() }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle("Routine overview")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close", systemImage: "xmark", action: onClose)
                        .accessibilityIdentifier("routine.overview.close")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("Edit", action: onEdit)
                        .fontWeight(.semibold)
                        .accessibilityLabel("Edit routine")
                        .accessibilityIdentifier("routine.overview.edit")
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var identity: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .top, spacing: 12) {
                EdgeMark(fingers: request.summary.signatureFingers ?? .four,
                         rungTint: PlanMath.IntensityBand.band(for: request.summary.peakIntensity).tint)
                    .accessibilityHidden(true)
                    .padding(.top, 4)
                Text(request.summary.name)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Estimated duration")
                    .font(.subheadline)
                    .foregroundStyle(Ink.secondary)
                Text(PlanMath.durationText(request.summary.estimatedSeconds))
                    .font(.largeTitle.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("routine.overview.duration")
                Text(totalLine)
                    .font(.subheadline.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("routine.overview.totals")
            }
        }
    }

    private var preparation: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                Label(plan.handMode.name, systemImage: plan.handMode == .bothHands ? "hands.clap" : "arrow.left.arrow.right")
                    .font(.headline)
                    .foregroundStyle(Ink.primary)
                Text(handExplanation)
                    .font(.subheadline)
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if plan.leadInSeconds > 0 && !sets.isEmpty {
                    Divider()
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Text("Lead-in before each set")
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                        Text(PlanMath.durationText(plan.leadInSeconds))
                            .fontWeight(.semibold)
                            .monospacedDigit()
                            .fixedSize()
                    }
                    .font(.subheadline)
                    .foregroundStyle(Ink.secondary)
                }
            }
        }
    }

    private var flow: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            Text("Your routine")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Ink.primary)
                .padding(.bottom, 12)

            ForEach(Array(sets.enumerated()), id: \.offset) { index, set in
                setCard(set, index: index)
                if index < sets.count - 1 {
                    HStack(spacing: 12) {
                        Rectangle()
                            .fill(Ink.tertiary.opacity(0.25))
                            .frame(width: 1, height: 28)
                            .padding(.leading, 27)
                        Text("\(PlanMath.durationText(plan.setBreakSeconds)) between sets")
                            .font(.footnote)
                            .foregroundStyle(Ink.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private func setCard(_ set: SetPlan, index: Int) -> some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    if !typeSize.isAccessibilitySize {
                        FingerGlyph(fingers: set.grip.fingers, position: set.grip.position,
                                    dot: 8, gap: 3)
                            .frame(width: 56, height: 48)
                            .background(Ink.primary.opacity(0.05),
                                        in: RoundedRectangle(cornerRadius: Metrics.radiusInner))
                            .accessibilityHidden(true)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Set \(index + 1)")
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(Ink.secondary)
                        Text(set.grip.line)
                            .font(.headline)
                            .foregroundStyle(Ink.primary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(pullText(set))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Ink.primary)
                    Text(timingText(set))
                        .font(.subheadline)
                        .foregroundStyle(Ink.secondary)
                    if let target = targetText(set) {
                        Text("Target: \(target)")
                            .font(.subheadline)
                            .foregroundStyle(Ink.secondary)
                    }
                    if !set.note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text(set.note)
                            .font(.footnote)
                            .foregroundStyle(Ink.secondary)
                            .padding(.top, 2)
                    }
                }
                .monospacedDigit()
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("routine.overview.set.\(index)")
    }

    private var totalLine: String {
        let count = request.summary.setCount
        let word = count == 1 ? String(localized: "set") : String(localized: "sets")
        let pulls = request.summary.totalReps == 1 ? String(localized: "1 pull total")
            : String(localized: "\(request.summary.totalReps) pulls total")
        return String(localized: "\(count) \(word)") + " · " + pulls
    }

    private var handExplanation: String {
        switch plan.handMode {
        case .alternateEachRep: String(localized: "Left, right, left, right — swapping hands every pull.")
        case .alternateEachSet: String(localized: "Left hand first, then right, within each set.")
        case .bothHands: String(localized: "One pull with both hands on the edge.")
        }
    }

    private func pullText(_ set: SetPlan) -> String {
        let total = PlanMath.repCount(set, mode: plan.handMode)
        if plan.handMode.sideCount > 1 {
            return String(localized: "\(set.repsPerSide) per side · \(total) pulls total")
        }
        let word = total == 1 ? String(localized: "pull") : String(localized: "pulls")
        return String(localized: "\(total) \(word)")
    }

    private func timingText(_ set: SetPlan) -> String {
        var text = String(localized: "\(PlanMath.durationText(PlanMath.hold(set, in: plan))) hold")
        // A one-pull set has no intra-set rest. Its break belongs to the connector.
        if PlanMath.repCount(set, mode: plan.handMode) > 1 {
            text += " · " + String(localized: "\(PlanMath.durationText(PlanMath.rest(set, in: plan))) rest")
        }
        return text
    }

    private func targetText(_ set: SetPlan) -> String? {
        // Show the prescription. Percentages stay percentages until the runner
        // resolves them against each hand's saved max at session start.
        if let kg = set.targetBand { return weightUnit.bandText(kg) }
        guard let band = PlanMath.targetPercent(set, in: plan) else { return nil }
        let lo = (band.lowerBound * 100).formatted(.number.precision(.fractionLength(0...1)))
        let hi = (band.upperBound * 100).formatted(.number.precision(.fractionLength(0...1)))
        return lo == hi ? String(localized: "\(hi) % of max") : String(localized: "\(lo)–\(hi) % of max")
    }
}
