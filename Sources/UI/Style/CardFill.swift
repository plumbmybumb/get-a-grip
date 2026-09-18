// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// What a card's backdrop is made of.
///
/// `.material` is the house card — `.regularMaterial`, a live backdrop blur. `.flat` is
/// a translucent fill FITTED to what that material renders over the slate field, and it
/// exists for one reason: a blur is re-rendered every frame the card moves or resizes,
/// and the builder stacks nine of them on one screen over a background with nothing in
/// it to blur. A tester's iPhone 13 mini stuttered whenever a row expanded or the
/// keyboard rose while they were editing (2026-09-18); each material card was buying a
/// tint and paying a blur pass per frame for it.
///
/// `AppBackground` is static and low-frequency, so a blur of it IS it, and a fill at the
/// material's own tint and opacity is indistinguishable from the material there —
/// measured from screenshot pixels in both schemes, not eyeballed (see `CardFill`). It
/// is only right OVER THAT FIELD: chrome that content scrolls under (the name field,
/// the bars) keeps real glass, because there the blur is doing visible work.
enum CardSurface {
    case material
    case flat
}

enum CardFill {
    /// `.regularMaterial` over `AppBackground`, fitted on the pinned sim 2026-09-18 by
    /// sampling card interiors and the field beside them at three heights per scheme
    /// and solving `card = α·tint + (1 − α)·field`: light is white at 0.88, dark is
    /// #313233 at 0.73. Re-fit if the field's mesh colours or the material ever change.
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
