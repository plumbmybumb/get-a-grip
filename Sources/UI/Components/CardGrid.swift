// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Cards two to a row wherever two fit at `Metrics.cardMinWidth` — an iPad, an unfolded
/// iPhone Duo — and one per row otherwise (and at accessibility text sizes below an
/// iPad's width; see `CardColumns`), top-aligned, on the house gap.
///
/// The regular-width answer to a phone column floating in a tablet (Nuri, 2026-09-19).
/// **Two columns, never three**, and that cap is why this is a `Layout` rather than
/// `LazyVGrid(.adaptive)`: an adaptive minimum low enough to give the Duo's 629 pt
/// content column two cards (≈303 pt each) gives a 1080 pt iPad column THREE ~345 pt
/// cards, and no single minimum gives both "two at 629" and "two at 1080". A fixed pair
/// is also the HIG's even column count for the Duo, and keeps one card's chart from
/// becoming a banner on a 13-inch screen. Measured 2026-10-31 on the Duo simulator —
/// see `docs/IPHONE_DUO.md`, "Unfolded layouts". Regular width only.
struct CardGrid<Content: View>: View {
    @ViewBuilder var content: Content
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        CardGridLayout(spacing: Metrics.spacing,
                       accessibilitySize: typeSize.isAccessibilitySize) { content }
    }
}

enum CardColumns {
    /// How many columns a content width holds: two once two cards of the minimum and the
    /// gap fit, else one. Today asks the same question before choosing its side-by-side
    /// layout, so the two screens split at one width.
    ///
    /// At ACCESSIBILITY text sizes the minimum goes back to the phone's 440: a 303 pt
    /// card was measured at default sizes, and big type in it wraps every line, so the
    /// unfolded Duo goes back to one card per row while an iPad's 529 pt pair stays.
    static func count(for width: CGFloat, accessibilitySize: Bool) -> Int {
        let minimum = accessibilitySize ? Metrics.maxContentWidth : Metrics.cardMinWidth
        return width >= minimum * 2 + Metrics.spacing ? 2 : 1
    }
}

/// Row-major, top-aligned, equal-width columns. Each card keeps its OWN height (as a
/// `.top`-aligned `GridItem` did); a row is as tall as its tallest card.
struct CardGridLayout: Layout {
    var spacing: CGFloat
    var accessibilitySize = false

    private func columnWidth(_ width: CGFloat, columns: Int) -> CGFloat {
        max(0, (width - spacing * CGFloat(columns - 1)) / CGFloat(columns))
    }

    /// Row heights for `width`, with the column count and width that produced them.
    private func rows(_ subviews: Subviews, width: CGFloat) -> (columns: Int, column: CGFloat, heights: [CGFloat]) {
        let columns = CardColumns.count(for: width, accessibilitySize: accessibilitySize)
        let column = columnWidth(width, columns: columns)
        var heights: [CGFloat] = []
        for start in stride(from: 0, to: subviews.count, by: columns) {
            let row = subviews[start..<min(start + columns, subviews.count)]
            heights.append(row.map { $0.sizeThatFits(.init(width: column, height: nil)).height }.max() ?? 0)
        }
        return (columns, column, heights)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        // An unspecified width falls back to the phone column, never to the cards' ideal
        // width — a child's ideal must not widen the screen (CLAUDE.md, Dynamic Type).
        let width = proposal.width ?? Metrics.maxContentWidth
        let laid = rows(subviews, width: width)
        let height = laid.heights.reduce(0, +) + spacing * CGFloat(max(0, laid.heights.count - 1))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let laid = rows(subviews, width: bounds.width)
        var y = bounds.minY
        for (rowIndex, height) in laid.heights.enumerated() {
            for column in 0..<laid.columns {
                let index = rowIndex * laid.columns + column
                guard index < subviews.count else { break }
                let x = bounds.minX + CGFloat(column) * (laid.column + spacing)
                subviews[index].place(at: CGPoint(x: x, y: y), anchor: .topLeading,
                                      proposal: .init(width: laid.column, height: nil))
            }
            y += height + spacing
        }
    }
}
