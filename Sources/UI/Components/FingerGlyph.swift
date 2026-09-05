// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A grip drawn as four BARS — index to little, filled when that finger is on the edge
/// and hairline when it is not.
///
/// Bars, not dots, and that is the whole point. As squares with a full corner radius
/// these rendered as literal CIRCLES at open hand and drag — indistinguishable from the
/// session dots one row above them and the consistency strip below, so a card that
/// meant three different things looked like circles all the way down. A finger is
/// taller than it is wide; drawing it that way makes the collision impossible rather
/// than merely unlikely, and it matches the app icon, which is this same mark.
///
/// Under the no-emoji rule this is the app's only iconography, and it is **the same
/// drawing as the hand hanging off the Dynamic Island** — see `radius`.
struct FingerGlyph: View {
    let fingers: FingerSet
    var position: GripPosition = .halfCrimp
    /// `@ScaledMetric` so the glyph grows with the text it sits beside instead of
    /// shrinking into a speck at accessibility sizes. Callers still pass a plain point
    /// size (`dot: 12`) and get it scaled; the declared size stays the design size.
    @ScaledMetric(relativeTo: .caption) var dot: CGFloat = 5.5
    @ScaledMetric(relativeTo: .caption) var gap: CGFloat = 3
    var tint: Color = Accent.graphite

    /// Fingers are taller than they are wide — the ratio is what stops a bar ever
    /// reading as a dot, at any size.
    private var barHeight: CGFloat { dot * 1.75 }

    /// A HAND's proportions — middle longest, little shortest — matching `FingerPips`
    /// and the island. Flat bars read as a barcode; these read as a hand at a glance,
    /// which is what makes the glyph work at 6 pt in a History row.
    private static let lengthFactor: [CGFloat] = [0.86, 1.0, 0.94, 0.80]

    /// **A FULL CAPSULE — byte-for-byte the island hand's rule** (`IslandHand.radius`),
    /// asked for twice by Nuri (2026-08-09: *"they need to have the identical radii so
    /// it's clear that they're the same fingers"*).
    ///
    /// It used to be scaled by `GripPosition.closure`, so the bar squared off as the hand
    /// closed — a genuine extra signal that separated the two front-2 sets at a glance in
    /// a truncated row. That is now spent: half a radius is a subtler difference than
    /// "same mark or not", and one mark drawn one way across the island, the builder, the
    /// widget and every thumbnail is worth more than a hint only its author could read.
    /// The POSITION is still spoken in words everywhere the glyph appears.
    private var radius: CGFloat { dot / 2 }

    var body: some View {
        VStack(alignment: .leading, spacing: max(1.5, gap * 0.6)) {
            // BOTTOM-aligned, so the varying lengths hang from a common knuckle line
            // the way fingers do — top-aligned they splay downward and read as a chart.
            HStack(alignment: .bottom, spacing: gap) {
                ForEach(Array(fingers.occupied.indices), id: \.self) { index in
                    pip(on: fingers.occupied[index], index: index)
                }
            }
            if fingers.hasThumb {
                // The thumb bar: horizontal, under the index side, because that is
                // where a thumb sits when a hand pinches. Nuri asked for exactly this
                // ("a little rectangle under the 4"), and it keeps the shape grammar —
                // BARS are fingers; this one just lies down.
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(tint)
                    .frame(width: dot * 2 + gap, height: dot * 0.62)
            }
        }
        // The parent always speaks the whole grip as one sentence (`GripSpec.spoken`).
        // Four unlabelled dots would be four meaningless VoiceOver stops.
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func pip(on: Bool, index: Int) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        Group {
            if on {
                shape.fill(tint)
            } else {
                // Stroked rather than a lighter fill: fill-vs-outline survives
                // greyscale, Reduce Transparency and colourblindness; a tint step
                // does not.
                // FULL tertiary, no opacity — measured: 0.45 gave 2.3:1 and even 0.7
                // only 2.6:1 on the dark well, because a 1 pt hairline is nearly all
                // antialiased edge. Which fingers are OFF is half the grip's meaning,
                // and it has to clear the 3:1 graphics floor, not flirt with it.
                shape.strokeBorder(Ink.tertiary, lineWidth: 1)
            }
        }
        .frame(width: dot, height: barHeight * Self.lengthFactor[index])
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 18) {
        ForEach(GripPosition.known, id: \.rawValue) { position in
            HStack(spacing: 16) {
                FingerGlyph(fingers: .four, position: position, dot: 14, gap: 6)
                FingerGlyph(fingers: .frontThree, position: position, dot: 14, gap: 6)
                FingerGlyph(fingers: .frontTwo, position: position, dot: 14, gap: 6)
                FingerGlyph(fingers: .backTwo, position: position, dot: 14, gap: 6)
                Text(position.name)
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
            }
        }
    }
    .padding()
    .background { AppBackground() }
}
