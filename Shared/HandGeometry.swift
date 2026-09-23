// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreGraphics

/// **One hand, four marks.** The proportions every drawing of the hand shares —
/// `HandMark` in the widget, `IslandHand` under the cutout, `FingerGlyph` in the app
/// and `EdgeMark` on the routine card.
///
/// One mark at four sizes, so a climber learns the picture once; four copies of the
/// array would drift the moment one was tuned. Numbers live here; each drawing owns its
/// own size, spacing and fill.
enum HandGeometry {
    /// A hand's proportions, INDEX → LITTLE: middle longest, little shortest. Mirrored
    /// with the fingers wherever a hand can face either way, so the middle finger stays
    /// the longest whichever side is drawn.
    ///
    /// Flat bars of one height read as a barcode; these read as a hand at a glance,
    /// which is what makes the glyph work at 6 pt in a History row.
    static let lengthFactor: [CGFloat] = [0.86, 1.0, 0.94, 0.80]

    /// **A finger is TALLER than it is wide**, so a bar never reads as a session dot.
    /// The island hand's 22 × 38 bar, rounded: `IslandHand` keeps the measured numbers
    /// (it is drawn against hardware); everything else derives length from width.
    static let barAspect: CGFloat = 1.75

    /// The longest finger's share of a hand — 1.0 by construction, stated so a caller
    /// sizing a container does not have to know which index it is.
    static var longestFactor: CGFloat { lengthFactor.max() ?? 1 }
}
