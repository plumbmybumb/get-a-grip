// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

extension View {
    /// Inner breathing room belongs to the label, before its capsule is drawn. A
    /// minimum height lets translated and larger text wrap without touching the edge.
    func actionLabelLayout(minHeight: CGFloat = Metrics.compactButtonHeight,
                           fullWidth: Bool = false,
                           fillsRowHeight: Bool = false) -> some View {
        self
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, Metrics.buttonHorizontalPadding)
            .padding(.vertical, Metrics.buttonVerticalPadding)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: minHeight,
                   maxHeight: fillsRowHeight ? .infinity : nil)
    }
}

/// How a cell's label keeps to one line, read by `AdaptiveActionRow(oneLinePerCell:)`.
///
/// A label that WRAPS (the default) gives itself away by growing taller at the cell's
/// width. A label that SHRINKS instead (`lineLimit(1)` + `minimumScaleFactor`) never
/// grows — it truncates once even its smallest scale is too wide — so it declares that
/// scale and the row checks its ideal width against the cell.
enum ActionLabelFit: LayoutValueKey {
    case wraps
    case shrinks(minimumScale: CGFloat)
    static let defaultValue = ActionLabelFit.wraps
}

extension View {
    /// See `ActionLabelFit`. Set on the row's direct child, or inside its body.
    func actionLabelFit(_ fit: ActionLabelFit) -> some View {
        layoutValue(key: ActionLabelFit.self, value: fit)
    }
}

/// Keep equal action widths while padded labels fit in a compact row; reflow rather
/// than shaving off their inner margins or shrinking accessibility text. Measuring
/// the actual localized labels also handles narrower phones without device lists.
struct AdaptiveActionRow: Layout {
    var spacing: CGFloat = 10
    /// Two ordinary lines fit comfortably. Larger text gets fewer columns instead
    /// of three very tall, narrow capsules stealing all of the live graph's height.
    var maximumRowHeight: CGFloat = 72
    /// **A capsule label never wraps or truncates**: a cell whose label would take a
    /// second line, or shrink past its floor, moves the row to fewer columns instead —
    /// two, then one. The runner dock's rule (2026-10-31): at XXXL "Hold to end"
    /// truncated to "Hold t…" while "Skip / pull" wrapped beside it. Off by default, so
    /// every other row keeps the two-line allowance it was measured with.
    var oneLinePerCell = false

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews,
                     cache: inout ()) -> CGSize {
        let width = proposal.width ?? subviews.map { $0.sizeThatFits(.unspecified).width }.reduce(0, +)
            + spacing * CGFloat(max(0, subviews.count - 1))
        let rows = rows(width: width, subviews: subviews)
        return CGSize(width: width, height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize,
                       subviews: Subviews, cache: inout ()) {
        for row in rows(width: bounds.width, subviews: subviews) {
            for (column, index) in row.indices.enumerated() {
                subviews[index].place(at: CGPoint(x: bounds.minX + CGFloat(column) * (row.width + spacing),
                                                 y: bounds.minY + row.y),
                                     anchor: .topLeading,
                                     proposal: ProposedViewSize(width: row.width, height: row.height))
            }
        }
    }

    private struct Row {
        let indices: Range<Int>
        let width: CGFloat
        let height: CGFloat
        let y: CGFloat
    }

    /// Whether `subview`'s label reads on one line at `cellWidth` — see `ActionLabelFit`.
    /// The half point of slack absorbs rounding, never a real second line.
    static func staysOnOneLine(_ subview: LayoutSubview, in cellWidth: CGFloat,
                               height: CGFloat) -> Bool {
        switch subview[ActionLabelFit.self] {
        case .wraps:
            return height <= subview.sizeThatFits(.unspecified).height + 0.5
        case .shrinks(let minimumScale):
            return shrunkWidth(ideal: subview.sizeThatFits(.unspecified).width,
                               minimumScale: minimumScale) <= cellWidth + 0.5
        }
    }

    /// A shrinking label's narrowest one-line width: the text scales, its insets do not.
    static func shrunkWidth(ideal: CGFloat, minimumScale: CGFloat) -> CGFloat {
        let insets = Metrics.buttonHorizontalPadding * 2
        return insets + max(0, ideal - insets) * minimumScale
    }

    private func rows(width: CGFloat, subviews: Subviews) -> [Row] {
        guard !subviews.isEmpty else { return [] }
        let columns = (1...subviews.count).reversed().first { count in
            let cellWidth = max(0, (width - spacing * CGFloat(count - 1)) / CGFloat(count))
            return subviews.allSatisfy { subview in
                let height = subview.sizeThatFits(ProposedViewSize(width: cellWidth, height: nil)).height
                guard height <= maximumRowHeight else { return false }
                return !oneLinePerCell || Self.staysOnOneLine(subview, in: cellWidth, height: height)
            }
        } ?? 1
        var rows: [Row] = []
        var y: CGFloat = 0
        for start in stride(from: 0, to: subviews.count, by: columns) {
            let end = min(start + columns, subviews.count)
            let cellWidth = max(0, (width - spacing * CGFloat(end - start - 1)) / CGFloat(end - start))
            let height = (start..<end).map {
                subviews[$0].sizeThatFits(ProposedViewSize(width: cellWidth, height: nil)).height
            }.max() ?? 0
            rows.append(Row(indices: start..<end, width: cellWidth, height: height, y: y))
            y += height + spacing
        }
        return rows
    }
}
