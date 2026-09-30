// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import UIKit

/// Plays a symbol effect on a tab's icon inside the system tab bar.
///
/// **SwiftUI symbol effects on a `Tab` label do nothing.** The tab bar turns the label
/// into a static `UITabBarItem` image once, so `.symbolEffect` — value-triggered or
/// continuous — never reaches the screen. Measured 2026-09-30: a `.pulse` with
/// `.repeat(.continuous)` on the Today label produced ONE frame in a five-second screen
/// recording (the recorder only writes a frame when a pixel changes).
///
/// The tab bar draws each item with ordinary `UIImageView`s — two or three copies per
/// tab on iOS 26 (the compact platter, the item platter, the selection layer) — and
/// `UIImageView.addSymbolEffect` is public API. So this finds every image view in the
/// tab bar showing the named symbol (or its `.fill` twin) and plays the effect on each.
///
/// The match reads the symbol name from `UIImage.description`, which is debug text
/// rather than API. That is the fragile part, and it fails CLOSED: if a future iOS words
/// it differently, nothing matches and the tab simply does not animate. Nothing here
/// can crash, block a tap, or change what the tab bar draws at rest.
@MainActor
enum TabBarSymbolEffects {

    /// The tab's effect, played once on every copy of its icon.
    static func play(_ effect: some DiscreteSymbolEffect & SymbolEffect, onSymbol name: String) {
        for bar in tabBars() {
            for view in imageViews(in: bar) where shows(view, symbol: name) {
                view.addSymbolEffect(effect, options: .nonRepeating)
            }
        }
    }

    // MARK: - Finding the icons

    private static func tabBars() -> [UITabBar] {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .flatMap { descendants(of: $0, as: UITabBar.self) }
    }

    private static func imageViews(in bar: UITabBar) -> [UIImageView] {
        descendants(of: bar, as: UIImageView.self)
    }

    /// `symbol(main: tab.today)` is how a symbol image names itself; the trailing `)` or
    /// `.fill)` keeps `tab.history` from matching a hypothetical `tab.historyX`.
    private static func shows(_ view: UIImageView, symbol name: String) -> Bool {
        guard let image = view.image, image.isSymbolImage else { return false }
        let text = image.description
        return text.contains("(main: \(name))") || text.contains("(main: \(name).fill)")
    }

    private static func descendants<T: UIView>(of root: UIView, as: T.Type) -> [T] {
        var found: [T] = []
        var stack: [UIView] = [root]
        while let view = stack.popLast() {
            if let match = view as? T { found.append(match) }
            stack.append(contentsOf: view.subviews)
        }
        return found
    }
}
