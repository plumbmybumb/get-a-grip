// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The intensity ladder's ONE mapping to colour, kept beside the mark that wears it.
/// Three surfaces draw the rung (Today's card, the import preview, the share sheet) and
/// a routine changing colour between two of them would be the mark contradicting
/// itself — so the judgement lives in `PlanMath.IntensityBand` and the paint lives
/// here, stated once.
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
/// It began as the app icon verbatim (all four bars, always) and Nuri's first
/// reaction named the flaw: every routine wore the same badge. So the mark now draws
/// the routine's own SIGNATURE grip — filled where a finger is on the edge, hairline
/// where it is not, the thumb as its horizontal bar — in exactly `FingerGlyph`'s
/// vocabulary, so cards differ precisely when routines differ. It is still one mark
/// per card, never the per-set inventory that was removed as clutter: the SUMMARY
/// picks the signature (`RoutineSummary.signatureFingers`), the mark just draws it.
/// The rung stays the one place bleu appears at rest — the icon's own rule: one
/// accent element, the edge the force passes through.
struct EdgeMark: View {
    var fingers: FingerSet = .four
    /// The rung doubles as the routine's INTENSITY signal (Nuri, 2026-08-17): bleu
    /// when nothing resolves, moss for light, the armed orange between, alarm red at
    /// near-max. The caller maps `PlanMath.IntensityBand` to a colour; the mark just
    /// wears it. Colour is reinforcement, not the sole carrier — the spoken value on
    /// the plan row states the percentage.
    var rungTint: Color = Accent.bleu

    /// Scales with the title it sits beside; the declared size is the design size.
    @ScaledMetric(relativeTo: .title2) private var barWidth: CGFloat = 5.5

    /// A HAND's proportions — `FingerGlyph.lengthFactor`, verbatim.
    private static let lengthFactor: [CGFloat] = [0.86, 1.0, 0.94, 0.80]

    private var gap: CGFloat { barWidth * 0.55 }
    /// 2.2×, deliberately more elongated than `FingerGlyph`'s 1.75: at this mark's
    /// small size the glyph's ratio rounds the bars into DOTS — and four filled dots
    /// one row above the session dots is the "circles all the way down" collision the
    /// shape vocabulary exists to prevent. Elongation is what keeps a bar a bar when
    /// the drawing shrinks; the icon renders big enough never to need it.
    private var tallestBar: CGFloat { barWidth * 2.2 }
    private var handWidth: CGFloat { barWidth * 4 + gap * 3 }

    var body: some View {
        VStack(alignment: .leading, spacing: barWidth * 0.32) {
            // Bottom-aligned: the varying lengths meet at a common fingertip line,
            // because the tips are what is ON the edge below.
            HStack(alignment: .bottom, spacing: gap) {
                ForEach(Array(fingers.occupied.indices), id: \.self) { index in
                    bar(on: fingers.occupied[index], index: index)
                }
            }
            if fingers.hasThumb {
                // The thumb lies down under the index side, exactly as `FingerPips`
                // and `FingerGlyph` draw it — bars are digits; this one is horizontal.
                RoundedRectangle(cornerRadius: barWidth * 0.31, style: .continuous)
                    .fill(Accent.graphite)
                    .frame(width: barWidth * 2 + gap, height: barWidth * 0.62)
            }
            RoundedRectangle(cornerRadius: barWidth * 0.36, style: .continuous)
                .fill(rungTint)
                .frame(width: handWidth, height: barWidth * 0.72)
        }
        // Pure decoration to a screen reader — the routine's NAME is the identity it
        // can use, and four unlabelled stops would say nothing.
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func bar(on: Bool, index: Int) -> some View {
        let shape = RoundedRectangle(cornerRadius: barWidth / 2, style: .continuous)
        Group {
            if on {
                shape.fill(Accent.graphite)
            } else {
                // Stroked, not tinted lighter — fill-vs-outline survives greyscale and
                // Reduce Transparency; full tertiary for the same measured reason as
                // `FingerGlyph` (a 1 pt hairline is nearly all antialiased edge).
                shape.strokeBorder(Ink.tertiary, lineWidth: 1)
            }
        }
        .frame(width: barWidth, height: tallestBar * Self.lengthFactor[index])
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
