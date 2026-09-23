// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The intensity ladder's ONE mapping to colour, beside the mark that wears it. Three
/// surfaces draw the rung (Today's card, import preview, share sheet), so the judgement
/// lives in `PlanMath.IntensityBand` and the paint here, stated once.
extension PlanMath.IntensityBand {
    var tint: Color {
        switch self {
        case .unknown: Accent.bleu
        case .light: Accent.moss
        case .moderate: StatusTint.armed
        case .nearMax: Accent.alarm
        }
    }
}

/// The routine's signature grip on the bleu edge — the card's mark.
///
/// It began as the app icon verbatim, and every routine wore the same badge. Now it draws
/// the routine's SIGNATURE grip in `FingerGlyph`'s vocabulary, so cards differ exactly
/// when routines do — still one mark per card, never the per-set inventory. The SUMMARY
/// picks the signature (`RoutineSummary.signatureFingers`); the mark just draws it. The
/// rung is the one place bleu appears at rest: the icon's one accent, the edge the force
/// passes through.
struct EdgeMark: View {
    var fingers: FingerSet = .four
    /// The rung doubles as the INTENSITY signal (Nuri, 2026-08-17) — see
    /// `RoutineCard.rungTint`. The caller maps the band; the mark wears it.
    var rungTint: Color = Accent.bleu

    /// Scales with the title it sits beside; the declared size is the design size.
    @ScaledMetric(relativeTo: .title2) private var barWidth: CGFloat = 5.5

    private var gap: CGFloat { barWidth * 0.55 }
    /// **A deliberate departure from `HandGeometry.barAspect`**: at this small size the
    /// house ratio rounds bars into DOTS, one row above the session dots — the "circles all
    /// the way down" collision. Elongation keeps a bar a bar as it shrinks.
    private static let smallMarkAspect: CGFloat = 2.2
    private var tallestBar: CGFloat { barWidth * Self.smallMarkAspect }
    private var handWidth: CGFloat { barWidth * 4 + gap * 3 }

    var body: some View {
        VStack(alignment: .leading, spacing: barWidth * 0.32) {
            // Bottom-aligned at a common fingertip line: the tips are what is ON the edge.
            HStack(alignment: .bottom, spacing: gap) {
                ForEach(Array(fingers.occupied.indices), id: \.self) { index in
                    bar(on: fingers.occupied[index], index: index)
                }
            }
            if fingers.hasThumb {
                // The thumb lies down under the index side, as in `FingerPips`/`FingerGlyph`.
                RoundedRectangle(cornerRadius: barWidth * 0.31, style: .continuous)
                    .fill(Accent.graphite)
                    .frame(width: barWidth * 2 + gap, height: barWidth * 0.62)
            }
            RoundedRectangle(cornerRadius: barWidth * 0.36, style: .continuous)
                .fill(rungTint)
                .frame(width: handWidth, height: barWidth * 0.72)
        }
        // Decoration to a screen reader; the routine's NAME is the identity.
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func bar(on: Bool, index: Int) -> some View {
        let shape = RoundedRectangle(cornerRadius: barWidth / 2, style: .continuous)
        Group {
            if on {
                shape.fill(Accent.graphite)
            } else {
                // Stroked, full tertiary — the measured reason is in `FingerGlyph`.
                shape.strokeBorder(Ink.tertiary, lineWidth: 1)
            }
        }
        .frame(width: barWidth, height: tallestBar * HandGeometry.lengthFactor[index])
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 20) {
        ForEach([FingerSet.four, .frontThree, .frontTwo, .backTwo], id: \.rawValue) { set in
            HStack(spacing: 16) {
                EdgeMark(fingers: set)
                Text("Daily no-hangs")
                    .font(.system(.title2, weight: .semibold))
            }
        }
    }
    .padding()
    .background { AppBackground() }
}
