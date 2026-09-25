// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// "New routine": build your own, or start from a known protocol (Nuri, 2026-09-25).
///
/// Build from scratch LEADS — the plan is still yours first. A protocol pushes the same
/// full preview a scanned code gets, so nothing lands without being read, and what lands
/// is an ordinary routine: the card it becomes opens the builder like any other.
struct NewRoutineSheet: View {
    /// The presenter opens the builder AFTER this sheet is gone; two covers cannot overlap.
    var onBuildFromScratch: () -> Void
    var onClose: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    scratchRow
                    CapsLabel(String(localized: "KNOWN PROTOCOLS"))
                        .padding(.top, 12)
                        .padding(.leading, 4)
                    ForEach(RoutineProtocol.allCases) { item in
                        NavigationLink(value: item) { ProtocolRow(item: item) }
                            .buttonStyle(PressFeedbackButtonStyle())
                    }
                    Text("Written up from what each author published. Get a Grip is not affiliated with or endorsed by them.")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                        .padding(.horizontal, 4)
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 12)
                .padding(.bottom, 28)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            // ALWAYS via `.background {}`, never as a ZStack sibling.
            .background { AppBackground() }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle("New routine")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onClose)
                }
            }
            .navigationDestination(for: RoutineProtocol.self) { item in
                RoutinePreview(draft: item.draft, title: item.title, source: item.source,
                               caution: item.caution, onDone: onClose)
            }
        }
    }

    private var scratchRow: some View {
        Button(action: onBuildFromScratch) {
            MaterialCard {
                HStack(spacing: 12) {
                    Image(systemName: "plus")
                        .font(.system(.title3, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                        .frame(width: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Build from scratch")
                            .font(.system(.headline))
                            .foregroundStyle(Ink.primary)
                        Text("Name it, add a set, make it yours.")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(.footnote, weight: .semibold))
                        .foregroundStyle(Ink.tertiary)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityHint("Opens the routine builder.")
    }
}

/// One protocol: its mark, name, attribution, what it is, and what it costs.
private struct ProtocolRow: View {
    let item: RoutineProtocol
    /// Folded once per row, like the preview's: `previewing` walks the rep sequence.
    private let summary: RoutineSummary

    init(item: RoutineProtocol) {
        self.item = item
        self.summary = RoutineSummary(previewing: item.draft.normalized)
    }

    var body: some View {
        MaterialCard {
            HStack(alignment: .top, spacing: 12) {
                // The mark the card will wear once added, intensity on the rung.
                EdgeMark(fingers: summary.signatureFingers ?? .four,
                         rungTint: PlanMath.IntensityBand.band(for: summary.peakIntensity).tint)
                    .padding(.top, 4)
                    .frame(width: 28, alignment: .leading)
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title)
                        .font(.system(.headline))
                        .foregroundStyle(Ink.primary)
                    Text(item.source)
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                    Text(item.blurb)
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                    Text(summary.metaLine)
                        .font(.system(.footnote, weight: .medium))
                        .monospacedDigit()
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
                    .padding(.top, 4)
            }
        }
        .contentShape(RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityHint("Shows the whole protocol before adding it.")
    }
}
