// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// The chrome AROUND the runner's curve: the tint it glides between, the wash under
// the glass, and where the plot sits inside the full-bleed canvas. Lifted out of
// `RunnerView.swift` whole — none of it draws a sample, and all of it is geometry and
// colour the layout hands down.

/// The trace's colour between two phases, interpolated per frame on the house curve.
/// An `Animatable` modifier rather than an animated `Color` state: SwiftUI does not
/// interpolate a `Color` value, but it does interpolate this fraction, and the
/// `Canvas` underneath redraws every frame anyway, so the mixed colour simply flows
/// through the environment into the drawing.
struct BlendedTint: ViewModifier, @preconcurrency Animatable {
    var fraction: Double
    var from: Color
    var to: Color
    var animatableData: Double {
        get { fraction }
        set { fraction = newValue }
    }
    func body(content: Content) -> some View {
        content.environment(\.blendedTraceTint, from.mix(with: to, by: min(max(fraction, 0), 1)))
    }
}

private struct BlendedTraceTintKey: EnvironmentKey {
    static let defaultValue: Color? = nil
}

extension EnvironmentValues {
    var blendedTraceTint: Color? {
        get { self[BlendedTraceTintKey.self] }
        set { self[BlendedTraceTintKey.self] = newValue }
    }
}

/// **The phase colour, under the glass.** Glass only reads as glass when something
/// with colour passes beneath it, and the panel sat over an empty headroom. This is
/// the trace's own wash carried up from the open region's edge to the top of the
/// screen in the phase tint — blue while the clock runs, amber while it waits on you,
/// steel at rest, red when the link is gone — so the top third of the phone says the
/// state before a word is read, and the panel has colour to refract. A plain fill
/// under a fixed mask, so the colour change between phases animates as a fill does;
/// the field itself stays static, which is what every glass surface needs to sample.
struct PhaseWash: View {
    enum Edge { case top, leading }
    var tint: Color
    /// The edge the wash hangs from: the top under the phone's panel, the leading
    /// edge under the iPad's column.
    var edge: Edge = .top
    /// How far it reaches from that edge before it has faded out.
    var length: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Rectangle()
            .fill(tint)
            .mask(alignment: edge == .top ? .top : .leading) {
                LinearGradient(stops: [.init(color: .black.opacity(0.30), location: 0),
                                       .init(color: .clear, location: 1)],
                               startPoint: edge == .top ? .top : .leading,
                               endPoint: edge == .top ? .bottom : .trailing)
            }
            .frame(width: edge == .leading ? max(0, length) : nil,
                   height: edge == .top ? max(0, length) : nil)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .animation(Motion.state(reduceMotion), value: tint)
            .accessibilityHidden(true)
    }
}

/// Where the stacked layout's full-bleed plot goes, measured from the layout on top
/// of it.
///
/// `ForceTraceView` keeps `TraceAxis.ceilingHeadroom` above the highest load it has
/// seen, so a pull's peak lands at 80 % of the plot's height. Solving for the plot top
/// that puts that 80 % line exactly on the open region's upper edge keeps the curve
/// the climber actually reaches in the clear and puts only the HEADROOM under the
/// panel: a new peak crosses under the glass while the axis glides to make room for
/// it — which is also the one moment the panel is unmistakably glass. The FLOOR sits
/// on the region's lower edge, just above the controls: with it at the screen's bottom
/// edge instead, the 0 kg line and the threshold rule ran underneath the buttons, and a
/// dashed rule crossing the gap between two rows of capsules read as a stray line
/// (measured on the iOS 27 sim, 2026-09-19). The canvas still runs under the controls
/// — the fill's wash is what the capsules refract — but nothing the plot LABELS does.
struct BackgroundTraceGeometry {
    /// The open region between the panel and the controls, in window coordinates.
    var region: CGRect = .zero
    /// The canvas — the whole window, once it has been measured.
    var canvas: CGRect = .zero

    /// The open region's upper edge in the canvas's own coordinates — where the
    /// phase wash ends and the curve's usual range begins. Zero until measured.
    var regionTopInCanvas: CGFloat {
        guard canvas.height > 0, region.height > 0 else { return 0 }
        return max(0, region.minY - canvas.minY)
    }

    /// The open region's leading edge — in the wide layout, where the glass column
    /// ends and the wash under it fades out.
    var regionLeadingInCanvas: CGFloat {
        guard canvas.width > 0, region.width > 0 else { return 0 }
        return max(0, region.minX - canvas.minX)
    }

    /// Clearance between the wide layout's plot and the screen's top and bottom edges,
    /// so the curve does not kiss the bezel.
    private static let wideEdgeInset: CGFloat = 24

    func plot(wide: Bool = false) -> ForceTraceView.PlotInsets {
        guard canvas.height > 0, region.height > 0 else { return .card }
        if wide {
            // Nothing sits above or below the open region on a wide screen — the
            // column is beside it — so the plot simply spans the region's height,
            // and the curve's history slides under the column on its way out.
            return ForceTraceView.PlotInsets(
                top: max(0, region.minY - canvas.minY) + Self.wideEdgeInset,
                bottom: max(0, canvas.maxY - region.maxY) + Self.wideEdgeInset,
                trailing: 8)
        }
        let headroom = 1 - 1 / TraceAxis.ceilingHeadroom
        let regionTop = region.minY - canvas.minY
        let bottom = max(0, canvas.maxY - region.maxY)
        // top = regionTop − headroom · (height − bottom − top), solved for top.
        let top = (regionTop - headroom * (canvas.height - bottom)) / (1 - headroom)
        return ForceTraceView.PlotInsets(top: max(0, top), bottom: bottom, trailing: 8)
    }
}
