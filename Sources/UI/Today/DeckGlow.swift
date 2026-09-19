// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// **The deck's glow** — Today's field takes the colour of the routine under your thumb
/// (Nuri, 2026-09-19: *"a subtle glow of the difficulty of the routine, emanating from
/// the top of the screen, that shifts to other colours as you slide through routine
/// cards"*). It is the runner's phase wash one screen earlier: colour at the top of the
/// phone means "what this moment is" on both screens, and the hue is the card's own
/// effort ladder (`PlanMath.IntensityBand.tint`), so the glow never says anything the
/// rung does not — greyscale and colour-blind readers lose nothing.
///
/// **It follows the finger.** The tint is blended between the two cards the deck is
/// between, in proportion to the drag, read off the scroll geometry — direct
/// manipulation, not a landing effect — and `PhaseWash`'s own settle rounds it off. It
/// costs nothing while nothing moves, which keeps the measured rule that the field is
/// static: it never drifts on its own, it only answers a swipe.
///
/// An `@Observable` box rather than `@State` on the screen, so the per-frame writes
/// during a swipe invalidate the wash alone and never the cards above it.
@Observable @MainActor
final class DeckGlow {
    var tint: Color?

    /// The colour between two cards: `page` is the deck's fractional position, `tints`
    /// the cards' own colours in deck order. A `nil` entry — the ghost card that offers
    /// a new routine — fades the glow out rather than inventing a colour for it.
    static func blend(page: CGFloat, tints: [Color?]) -> Color? {
        guard !tints.isEmpty else { return nil }
        let clamped = min(max(page, 0), CGFloat(tints.count - 1))
        let lower = Int(clamped.rounded(.down))
        let upper = min(lower + 1, tints.count - 1)
        let fraction = Double(clamped - CGFloat(lower))
        switch (tints[lower], tints[upper]) {
        case let (a?, b?): return fraction == 0 ? a : a.mix(with: b, by: fraction)
        case let (a?, nil): return a.opacity(1 - fraction)
        case let (nil, b?): return b.opacity(fraction)
        case (nil, nil): return nil
        }
    }
}

/// The wash itself, as a leaf that observes the box — see `DeckGlow`.
struct DeckGlowWash: View {
    let glow: DeckGlow
    /// How far down the screen the glow reaches before it has faded: the title and the
    /// top of the deck, never the whole page.
    static let reach: CGFloat = 420

    var body: some View {
        if let tint = glow.tint {
            PhaseWash(tint: tint, edge: .top, length: Self.reach)
                .ignoresSafeArea()
                .transition(.opacity)
        }
    }
}
