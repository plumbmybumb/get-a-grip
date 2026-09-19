// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import CoreGraphics

/// **One hand, four marks.** The proportions every drawing of the hand shares —
/// `HandMark` in the widget, `IslandHand` under the cutout, `FingerGlyph` in the app
/// and `EdgeMark` on the routine card.
///
/// They are the same mark at four sizes, which is the whole reason the app icon and the
/// UI cannot drift apart: a climber learns the picture once. Four copies of the same
/// array is how that promise quietly breaks — one of them gets tuned, and the hand on
/// the card stops being the hand on the island. Numbers live here; each drawing still
/// owns its own size, spacing and fill.
enum HandGeometry {
    /// A hand's proportions, INDEX → LITTLE: middle longest, little shortest. Mirrored
    /// with the fingers wherever a hand can face either way, so the middle finger stays
    /// the longest whichever side is drawn.
    ///
    /// Flat bars of one height read as a barcode; these read as a hand at a glance,
    /// which is what makes the glyph work at 6 pt in a History row.
    static let lengthFactor: [CGFloat] = [0.86, 1.0, 0.94, 0.80]

    /// **A finger is TALLER than it is wide**, and the ratio is what stops a bar ever
    /// reading as a dot — the collision that made session dots and finger pips
    /// indistinguishable when both were round. It is the Dynamic Island hand's own
    /// 22 × 38 bar, rounded: `IslandHand` keeps those two measured numbers because it
    /// is drawn against the hardware, and everything else derives its length from its
    /// width with this.
    static let barAspect: CGFloat = 1.75

    /// The longest finger's share of a hand — 1.0 by construction, stated so a caller
    /// sizing a container does not have to know which index it is.
    static var longestFactor: CGFloat { lengthFactor.max() ?? 1 }
}
