// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// A number chosen from a LADDER, not from a continuum.
///
/// It replaced a `Slider` plus four preset chips, wrong twice over. Nielsen Norman: use a
/// slider only when the precise value will not matter. Every number in a routine is exact
/// — a 3 s hold, 4 pulls, a 5 s rest. And arithmetically, a hold slider over 3…45 in fives
/// lands on 3, 8, 13, 18, leaving 5, 7, 10 and 12 reachable only as chips; the C4
/// protocol's 3 s hold, 5 s rest and 4 pulls were none of them.
///
/// **The dial's positions are the ladder's INDICES, not the value's magnitude.** Detents
/// are evenly spaced, so 3 and 30 are equally easy to hit, and the scale states every
/// value the control can produce. Tap-to-type on the row above is not optional: Zwift's
/// drag-only editor has power users hand-editing exported XML.
struct DialTrack: View {
    @Binding var value: Double
    /// Ascending, and FIXED — the same stops whatever `value` is. Splicing the current value
    /// in re-spaced every INDEX-positioned stop on typing and again on the first drag; an
    /// off-ladder value gets `offLadderMark` instead.
    var values: [Double]
    var format: (Double) -> String
    var spokenUnit: String
    /// An unanswered scale is a real state, not a value below the ladder. The drag still
    /// needs a binding; this flag keeps its placeholder value from drawing a detent or mark.
    var isUnset: Bool = false

    enum RenderingMark: Equatable {
        case detent(Int)
        case offLadder
    }

    /// Fires the per-detent click, bumped by a LANDING, never by the value — a typed number
    /// is not a detent.
    @State private var landings = 0

    /// What the ticks DRAW into.
    private static let trackHeight: CGFloat = 30
    /// What the finger gets. The drawing is 30 pt (taller ticks read as a fence), but the
    /// strip must clear the 44 pt floor, as `BandTrimmer` does. A hit area LARGER than the
    /// drawing is the safe direction; the reverse is the missed-tap bug `ValueRow`'s preset
    /// row already paid for.
    private static let hitHeight: CGFloat = 44

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            GeometryReader { geo in
                let width = geo.size.width
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Ink.tertiary.opacity(0.16))
                        .frame(height: 2)
                        .frame(maxHeight: .infinity, alignment: .center)

                    ForEach(values.indices, id: \.self) { index in
                        detent(at: index, width: width)
                    }
                    if renderingMark == .offLadder, let x = offLadderX(in: width) {
                        offLadderMark(at: x)
                    }
                }
                .frame(height: Self.hitHeight)
                // The whole strip is live: a 2 pt bar is not a target, and the gap between
                // two is where people aim.
                .contentShape(.rect)
                .onTapGesture { location in land(at: location.x, width: width) }
                // Absolute tracking, not translation: the value belongs under the finger.
                .gesture(HorizontalPan(
                    began: { x in land(at: x, width: width) },
                    changed: { x, _ in land(at: x, width: width) },
                    ended: {}))
            }
            .frame(height: Self.hitHeight)

            scale
        }
        .sensoryFeedback(.selection, trigger: landings)
        // ONE adjustable element, stepping the detents the drag lands on.
        .accessibilityElement(children: .ignore)
        .accessibilityValue(isUnset ? String(localized: "Not set") : String(localized: "\(format(value)) \(spokenUnit)"))
        .accessibilityAdjustableAction { direction in
            guard let next = Self.adjustedIndex(value: value, values: values, isUnset: isUnset,
                                                increasing: direction == .increment) else { return }
            value = values[next]
            landings += 1
        }
    }

    private func detent(at index: Int, width: CGFloat) -> some View {
        // Only when EXACTLY here; off the ladder `offLadderMark` carries it — a
        // highlighted 20 under a face saying 22 would disagree with itself.
        let isCurrent = renderingMark == .detent(index)
        return Capsule()
            .fill(isCurrent ? Accent.graphite : Ink.tertiary.opacity(0.5))
            .frame(width: isCurrent ? 4 : 2,
                   height: isCurrent ? Self.trackHeight : 11)
            .position(x: x(of: index, in: width), y: Self.hitHeight / 2)
    }

    /// A typed off-ladder value, drawn WHERE IT FALLS between its neighbouring stops. Hollow,
    /// reading "here, but not a stop" — the next drag will land on a detent.
    private func offLadderMark(at x: CGFloat) -> some View {
        Capsule()
            .strokeBorder(Accent.graphite, lineWidth: 1.5)
            .frame(width: 6, height: Self.trackHeight)
            .position(x: x, y: Self.hitHeight / 2)
    }

    /// The ladder, stated: every value the control can produce, readable without touching it.
    private var scale: some View {
        HStack(spacing: 0) {
            ForEach(values.indices, id: \.self) { index in
                let isCurrent = renderingMark == .detent(index)
                Text(format(values[index]))
                    .font(.system(.caption2, weight: isCurrent ? .semibold : .regular))
                    .monospacedDigit()
                    .foregroundStyle(isCurrent ? Ink.primary : Ink.tertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityHidden(true)
    }

    // MARK: - Geometry

    /// The detent the value sits exactly on, or nil when it falls between two.
    private var exactIndex: Int? {
        values.firstIndex { abs($0 - value) < 0.001 }
    }

    /// **The single decision about what this dial draws** — the detent highlight, the
    /// scale's bold label and the off-ladder mark all read it, so a test of it tests the real
    /// rendering path. The two marks are exclusive, and both vanish while unset.
    var renderingMark: RenderingMark? {
        guard !isUnset else { return nil }
        if let exactIndex { return .detent(exactIndex) }
        return values.count > 1 ? .offLadder : nil
    }

    /// A typed value between detents steps to its immediate neighbour in the requested
    /// direction. Rounding first skipped a stop (23 mm → 30 mm instead of 25 mm).
    static func adjustedIndex(value: Double, values: [Double], isUnset: Bool,
                              increasing: Bool) -> Int? {
        guard !values.isEmpty else { return nil }
        if isUnset {
            return increasing ? values.startIndex : values.index(before: values.endIndex)
        }
        return increasing ? values.firstIndex(where: { $0 > value })
                          : values.lastIndex(where: { $0 < value })
    }

    /// Where an off-ladder value falls, interpolated between its neighbours. nil when the
    /// value IS a detent or lies outside the ladder (the mark then parks on the end stop).
    /// Pure GEOMETRY: whether to draw is `renderingMark`'s call.
    private func offLadderX(in width: CGFloat) -> CGFloat? {
        guard width > 0 else { return nil }
        guard let upper = values.firstIndex(where: { $0 > value }) else {
            return x(of: values.count - 1, in: width)
        }
        guard upper > 0 else { return x(of: 0, in: width) }
        let lo = values[upper - 1], hi = values[upper]
        let t = (value - lo) / (hi - lo)
        return x(of: upper - 1, in: width) + t * slotWidth(width)
    }

    private func slotWidth(_ width: CGFloat) -> CGFloat {
        values.isEmpty ? width : width / CGFloat(values.count)
    }

    private func x(of index: Int, in width: CGFloat) -> CGFloat {
        (CGFloat(index) + 0.5) * slotWidth(width)
    }

    private func land(at x: CGFloat, width: CGFloat) {
        guard width > 0, !values.isEmpty else { return }
        let slot = Int(floor(x / slotWidth(width)))
        let index = min(values.count - 1, max(0, slot))
        guard isUnset || values[index] != value else { return }
        value = values[index]
        landings += 1
    }
}

#Preview {
    @Previewable @State var hold: Double = 10
    VStack(alignment: .leading, spacing: 30) {
        DialTrack(value: $hold, values: [3, 5, 7, 10, 12, 15, 20, 30],
                  format: { $0.formatted(.number.precision(.fractionLength(0))) },
                  spokenUnit: "seconds")
    }
    .padding(24)
    .background { AppBackground() }
}
