// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// What a card's backdrop is made of.
///
/// `.material` is the house card — `.regularMaterial`, a live blur. `.flat` is a
/// translucent fill FITTED to what that material renders over the slate field. A blur
/// re-renders every frame the card moves or resizes, and the builder stacks nine over a
/// background with nothing to blur; a tester's iPhone 13 mini stuttered on row expansion
/// and keyboard rise (2026-09-18).
///
/// `AppBackground` is static, so a fill at the material's tint and opacity is
/// indistinguishable there — measured from screenshot pixels in both schemes (see
/// `CardFill`). Only OVER THAT FIELD: chrome that content scrolls under keeps real glass.
enum CardSurface {
    case material
    case flat
}

enum CardFill {
    /// `.regularMaterial` over `AppBackground`, fitted 2026-09-18 by sampling card interiors
    /// and the field at three heights per scheme, solving `card = α·tint + (1 − α)·field`:
    /// light is white at 0.88, dark #313233 at 0.73. Re-fit if the mesh or material changes.
    static let flat = Color.adaptive(Color(hex: "F6F7F7").opacity(0.88),
                                     Color(hex: "313233").opacity(0.73))
}

private struct CardSurfaceModifier<S: Shape>: ViewModifier {
    var surface: CardSurface
    var shape: S
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        switch surface {
        case .material:
            content.background(.regularMaterial, in: shape)
        case .flat:
            // A material turns opaque under Reduce Transparency by itself; a plain fill
            // has to be told, with the same opaque backdrop `AccessibleGlass` reaches for.
            if reduceTransparency {
                content.background(Color(uiColor: .secondarySystemBackground), in: shape)
            } else {
                content.background(CardFill.flat, in: shape)
            }
        }
    }
}

extension View {
    func cardSurface(_ surface: CardSurface, in shape: some Shape) -> some View {
        modifier(CardSurfaceModifier(surface: surface, shape: shape))
    }
}
