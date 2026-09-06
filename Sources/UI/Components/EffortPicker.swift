// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import SwiftUI

/// The existing optional five-level rating, drawn with FingerGlyph's capsule ends.
/// The colors describe reported effort, never a measured percentage or a safety verdict.
struct EffortPicker: View {
    @Binding var selection: Int?
    let labels: [String]
    let title: String
    let identifier: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var landings = 0

    static let barWidth: CGFloat = 44
    private static let trackHeight: CGFloat = 72

    static func level(at x: CGFloat, width: CGFloat) -> Int {
        guard x.isFinite, width.isFinite, width > 0 else { return 1 }
        let bar = min(Self.barWidth, width / 5)
        let fraction = min(1, max(0, (x - bar / 2) / max(1, width - bar)))
        return Int((fraction * 4).rounded()) + 1
    }

    private func tint(_ level: Int?) -> Color {
        switch level {
        case 1, 2: Accent.moss
        case 3, 4: StatusTint.armed
        case 5: Accent.alarm
        default: Accent.bleu
        }
    }

    private var selectedName: String {
        guard let selection, (1...labels.count).contains(selection) else { return String(localized: "Not rated") }
        return labels[selection - 1]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CapsLabel(title)
            GeometryReader { geo in
                let width = geo.size.width
                let bar = min(Self.barWidth, width / 5)
                ZStack(alignment: .topLeading) {
                    ForEach(0..<5) { index in
                        // Leave room inside the track for the selected bar's lift.
                        // The surrounding form and the five hit regions never move.
                        let active = selection == index + 1 && !reduceMotion
                        let scale: CGFloat = active ? 1.10 : 1
                        let drawnWidth = min(bar * scale, width / 5)
                        let restingHeight = bar * 1.15 + (Self.trackHeight / 1.10 - bar * 1.15) * CGFloat(index) / 4
                        let height = restingHeight * scale
                        let center = min(width - drawnWidth / 2,
                            max(drawnWidth / 2, bar / 2 + CGFloat(index) * (width - bar) / 4))
                        RoundedRectangle(cornerRadius: drawnWidth / 2, style: .continuous)
                            .fill(selection == index + 1 ? tint(selection) : Ink.tertiary.opacity(0.18))
                            .frame(width: drawnWidth, height: height)
                            .position(x: center, y: Self.trackHeight - height / 2)
                        if selection == index + 1 {
                            Circle().fill(Ink.primary).frame(width: 6, height: 6)
                                .position(x: center, y: Self.trackHeight - 10)
                        }
                    }
                }
                .animation(Motion.state(reduceMotion), value: selection)
                .frame(width: width, height: Self.trackHeight)
                .contentShape(.rect)
                .onTapGesture { point in choose(Self.level(at: point.x, width: width)) }
                .gesture(HorizontalPan(
                    began: { x in choose(Self.level(at: x, width: width)) },
                    changed: { x, _ in choose(Self.level(at: x, width: width)) }, ended: {}))
            }
            .frame(height: Self.trackHeight)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title)
            .accessibilityValue(selectedName)
            .accessibilityIdentifier(identifier)
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: choose(min(5, (selection ?? 0) + 1))
                case .decrement: choose(max(1, (selection ?? 2) - 1))
                @unknown default: break
                }
            }
            HStack {
                Text(labels.first ?? "")
                Spacer()
                Text(labels.last ?? "")
            }
            .font(.caption2).foregroundStyle(Ink.secondary)
            .accessibilityHidden(true)
            HStack(spacing: 8) {
                Circle().fill(tint(selection)).frame(width: 8, height: 8).accessibilityHidden(true)
                Text(selectedName).font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(selection == nil ? Ink.secondary : Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                if selection != nil {
                    Button { choose(nil) } label: {
                        Text("Clear")
                            .font(.caption).foregroundStyle(Ink.secondary)
                            .frame(minWidth: 44, minHeight: 44).contentShape(.rect)
                    }
                        .buttonStyle(PressFeedbackButtonStyle())
                        .accessibilityLabel(String(localized: "Clear rating"))
                        .accessibilityIdentifier(identifier + ".clear")
                }
            }
            .frame(minHeight: 44)
        }
        .sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: landings)
    }

    private func choose(_ level: Int?) {
        guard selection != level else { return }
        selection = level
        landings += 1
    }
}
