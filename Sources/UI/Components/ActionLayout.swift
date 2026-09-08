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

/// Keep equal action widths while padded labels fit in a compact row; reflow rather
/// than shaving off their inner margins or shrinking accessibility text. Measuring
/// the actual localized labels also handles narrower phones without device lists.
struct AdaptiveActionRow: Layout {
    var spacing: CGFloat = 10
    /// Two ordinary lines fit comfortably. Larger text gets fewer columns instead
    /// of three very tall, narrow capsules stealing all of the live graph's height.
    var maximumRowHeight: CGFloat = 72

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

    private func rows(width: CGFloat, subviews: Subviews) -> [Row] {
        guard !subviews.isEmpty else { return [] }
        let columns = (1...subviews.count).reversed().first { count in
            let cellWidth = max(0, (width - spacing * CGFloat(count - 1)) / CGFloat(count))
            return subviews.allSatisfy {
                $0.sizeThatFits(ProposedViewSize(width: cellWidth, height: nil)).height <= maximumRowHeight
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
