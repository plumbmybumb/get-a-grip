// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import SwiftUI

/// Fable's effort ladder: a fixed rising silhouette, filled through the chosen rung.
/// This is the existing optional self-report, not a measured percentage of maximum.
struct EffortPicker: View {
    @Binding var selection: Int?
    let labels: [String]
    let title: String
    let identifier: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .body) private var barWidth: CGFloat = 10
    @ScaledMetric(relativeTo: .body) private var tallest: CGFloat = 44
    @State private var landings = 0
    @State private var stripWidth: CGFloat = 0

    /// Each entire slot is live, including the space around its thin drawn rung.
    /// Clamp before integer conversion so malformed coordinates cannot trap.
    static func level(at x: CGFloat, width: CGFloat, count: Int = 5) -> Int {
        guard x.isFinite, width.isFinite, width > 0, count > 0 else { return 1 }
        let fraction = min(1, max(0, x / width))
        return min(count - 1, Int(floor(fraction * CGFloat(count)))) + 1
    }

    static func height(at index: Int, count: Int = 5, tallest: CGFloat) -> CGFloat {
        guard count > 1 else { return tallest }
        return tallest * (0.4 + 0.6 * CGFloat(min(count - 1, max(0, index))) / CGFloat(count - 1))
    }

    private var selectedLevel: Int? {
        selection.flatMap { (1...max(1, labels.count)).contains($0) && !labels.isEmpty ? $0 : nil }
    }

    private var tint: Color {
        switch selectedLevel {
        case 1, 2: Accent.moss
        case 3, 4: StatusTint.armed
        case 5: Accent.alarm
        default: Accent.bleu
        }
    }

    private var selectedName: String {
        selectedLevel.map { labels[$0 - 1] } ?? String(localized: "Not rated")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(title)
            Text(selectedName)
                .font(.system(.title3, weight: .semibold))
                .foregroundStyle(selectedLevel == nil ? Ink.tertiary : Ink.primary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityHidden(true)

            GeometryReader { geo in
                HStack(alignment: .bottom, spacing: 0) {
                    ForEach(labels.indices, id: \.self) { index in
                        let filled = selectedLevel.map { index < $0 } ?? false
                        Capsule()
                            .fill(filled ? tint : .clear)
                            .overlay {
                                Capsule().strokeBorder(filled ? tint : Ink.tertiary, lineWidth: 1)
                            }
                            .frame(width: min(barWidth, geo.size.width / CGFloat(max(1, labels.count))),
                                   height: Self.height(at: index, count: labels.count, tallest: tallest))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    }
                }
                .animation(Motion.state(reduceMotion), value: selectedLevel)
                .frame(width: geo.size.width, height: geo.size.height)
                .contentShape(.rect)
                .onTapGesture { point in
                    guard !labels.isEmpty else { return }
                    let level = Self.level(at: point.x, width: geo.size.width, count: labels.count)
                    choose(level == selectedLevel ? nil : level)
                }
                // Horizontal-only recognition leaves vertical scrolling to the form.
                // A drag never clears just because it crossed its current selection.
                .gesture(HorizontalPan(
                    began: { x in land(at: x, width: geo.size.width) },
                    changed: { x, _ in land(at: x, width: geo.size.width) }, ended: {}))
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { stripWidth = $0 }
            }
            .frame(height: max(44, tallest))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title)
            .accessibilityValue(selectedName)
            .accessibilityIdentifier(identifier)
            .accessibilityAdjustableAction { direction in
                guard !labels.isEmpty else { return }
                switch direction {
                case .increment: choose(min(labels.count, (selectedLevel ?? 0) + 1))
                case .decrement: choose(max(1, (selectedLevel ?? (labels.count + 1)) - 1))
                @unknown default: break
                }
            }
            .accessibilityActions {
                if selectedLevel != nil {
                    Button(String(localized: "Clear rating")) { choose(nil) }
                }
            }

            if labels.count > 1 { endLabels }
        }
        .sensoryFeedback(.selection, trigger: landings)
    }

    /// Center each endpoint under its rung when it fits. Separate half-width regions
    /// allow long translations and accessibility text to wrap without overlapping.
    private var endLabels: some View {
        let slot = stripWidth / CGFloat(labels.count)
        return HStack(alignment: .top, spacing: 8) {
            Text(labels[0])
                .alignmentGuide(.leading) { d in min(0, d.width / 2 - slot / 2) }
                .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
            Text(labels[labels.count - 1])
                .alignmentGuide(.trailing) { d in max(d.width, d.width / 2 + slot / 2) }
                .frame(minWidth: 0, maxWidth: .infinity, alignment: .trailing)
                .multilineTextAlignment(.trailing)
        }
        .font(.caption2)
        .foregroundStyle(Ink.tertiary)
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityHidden(true)
    }

    private func land(at x: CGFloat, width: CGFloat) {
        guard !labels.isEmpty else { return }
        choose(Self.level(at: x, width: width, count: labels.count))
    }

    private func choose(_ level: Int?) {
        guard selection != level else { return }
        selection = level
        landings += 1
    }
}
