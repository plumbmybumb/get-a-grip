// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Keeps a horizontal rail inside the HORIZONTAL safe area, instead of letting it run
/// under a vertical tab bar.
///
/// A SwiftUI `ScrollView` extends beneath the safe-area edges on its scrolling axis and
/// insets its content instead — right for a vertical list under a bottom bar, invisible
/// on every phone because the horizontal safe area there is zero. On the iPhone Duo the
/// tab bar, status column and island stand on the TRAILING edge (safe inset ≈85 pt), so
/// a horizontal deck spanned the whole 466 pt window and the peeking neighbour card slid
/// under the bar and the gauge button (`docs/IPHONE_DUO.md`, simulator first look,
/// 2026-10-20).
///
/// Two mechanisms, because the two hosts differ — each measured on the Duo simulator
/// with `-dumpInteractions`:
/// - In a `List` row (History's trend and month decks) `ignoresSafeArea` on the rail is
///   the whole cure: it removes those edges from the subtree, the scroll view inside finds
///   nothing to extend into, and it stays the row's width (`466 → 382`). The rail itself
///   does not grow — the row was already inset by the system.
/// - Inside the scaffold's vertical `ScrollView` (Today's deck) that strip does NOT reach
///   the nested scroll view: it extended to 466 regardless, and the rail never grew
///   either (padding it by the window inset came out 298, subtracted inside the column).
///   What does stop it is the scroll view's edge not COINCIDING with the safe-area
///   boundary: one point of trailing padding gave `381`, the page column exactly. The
///   hairline is applied only where the window reports a side inset, so a phone or an
///   iPad lays out byte-identically; it is re-read on every size change, which is what
///   a fold, a rotation or a Split View drag does to the rail.
/// The insets come from the WINDOW, as `IslandHand.windowTopInset` does: a
/// `GeometryReader` on the rail reports zero (its parent already applied the safe area),
/// and a probe that ignores the safe area reports zero too (ignoring removes it from
/// that subtree).
///
/// A rail that is UNCLIPPED (Today's deck, `scrollClipDisabled()` for the context-menu
/// lift) still drew its neighbour past the corrected frame — the frame was right and the
/// picture was not. So on a window with side insets the rail is also clipped, but only
/// SIDEWAYS: `SideClip` is the rail's rect with 1000 pt of slack above and below, which
/// keeps the lift's scaled corners and shadow intact vertically while the column edge
/// cuts the neighbour off. The card's own 16 pt margin absorbs the lift's ~2.5 %
/// horizontal growth. On a phone the clip is a rect far larger than the screen — no
/// effect, and the lift behaves exactly as before.
struct ClearOfSideBars: ViewModifier {
    @Environment(\.layoutDirection) private var layoutDirection
    /// Hairline padding on the sides that carry a system column, zero elsewhere.
    @State private var hairline: EdgeInsets = .init()

    func body(content: Content) -> some View {
        content
            .padding(.leading, hairline.leading)
            .padding(.trailing, hairline.trailing)
            .clipShape(SideClip(sideways: hairline != EdgeInsets()))
            .ignoresSafeArea(.container, edges: .horizontal)
            .onGeometryChange(for: CGSize.self) { $0.size } action: { _ in refresh() }
            .onAppear(perform: refresh)
    }

    private func refresh() {
        let window = Self.windowSideInsets
        let left: CGFloat = window.left > 0 ? 1 : 0
        let right: CGFloat = window.right > 0 ? 1 : 0
        let next = layoutDirection == .rightToLeft
            ? EdgeInsets(top: 0, leading: right, bottom: 0, trailing: left)
            : EdgeInsets(top: 0, leading: left, bottom: 0, trailing: right)
        if next != hairline { hairline = next }
    }

    /// The key window's safe-area insets — a vertical bar's column on an iPhone Duo,
    /// zero horizontally everywhere else. Before there is a key window: zero.
    @MainActor
    static var windowSideInsets: UIEdgeInsets {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?
            .safeAreaInsets ?? .zero
    }
}

/// The rail's rect with vertical slack; with `sideways` off, slack on every side too.
private struct SideClip: Shape {
    var sideways: Bool
    func path(in rect: CGRect) -> Path {
        let slack: CGFloat = 1000
        let dx: CGFloat = sideways ? 0 : slack
        return Path(rect.insetBy(dx: -dx, dy: -slack))
    }
}

extension View {
    /// See `ClearOfSideBars`. Apply OUTSIDE the rail's own margins and negative padding.
    func clearOfSideBars() -> some View { modifier(ClearOfSideBars()) }
}
