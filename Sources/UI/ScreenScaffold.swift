// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// Shared screen chrome, ported from Schengen Slice's RootTabView.swift.

/// Standard tab-screen canvas: textured slate mesh, scrolling content column with the
/// house width cap, and real navigation chrome.
///
/// The system title is load-bearing: it buys the large-to-inline collapse, the scroll
/// edge effect and a toolbar. Without it Liquid Glass has no chrome layer to BE, and the
/// app reads as styled rather than native.
struct ScreenScaffold<Content: View>: View {
    var title: String
    var subtitle: String? = nil
    var spacing: CGFloat = Metrics.spacing
    /// **This screen is meant to fit on one page** — a dashboard, not a document (Nuri,
    /// 2026-08-09).
    ///
    /// Still a ScrollView, on purpose: `.basedOnSize` means no scrolling and no rubber-band
    /// when the content fits (the complaint was only that Today had grown ~40 pt too tall,
    /// and a real scroll collapses the large title), and at accessibility sizes it WILL
    /// overflow, where scrolling beats clipping a Start button.
    ///
    /// `ViewThatFits` is wrong here: its proposal ignores the large title, so it chose the
    /// flat layout and ran the last card under the tab bar.
    ///
    /// **The LARGE title stays** (Nuri: *"I liked the bigger today header"*): it is the real
    /// UIKit chrome that makes the screen read as native. The height comes out of the
    /// content instead, starting with the bottom margin.
    var fitsOnePage: Bool = false
    /// The screen lays its cards out in a `CardGrid` on a wide window, so the column opens
    /// to two cards' width.
    var gridsOnWideScreens: Bool = false
    @ViewBuilder var content: Content

    /// Size CLASS, never the idiom: a Slide Over iPad column is compact and gets the phone
    /// width.
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        NavigationStack {
            ScrollView { column }
                // No rubber-band when everything fits — that phantom bounce collapses the
                // large title. Still scrolls when content genuinely overflows.
                .scrollBounceBehavior(.basedOnSize)
                .scrollEdgeEffectStyle(.soft, for: .bottom)
            // ALWAYS via `.background {}`, never as a ZStack sibling — as a sibling it
            // disturbs the ScrollView's safe-area layout and the title creeps under the
            // status bar. (The house rule; other screens refer here.)
            .background { AppBackground() }
            .navigationTitle(title)
            .navigationSubtitle(subtitle ?? "")
        }
    }

    private var column: some View {
        VStack(alignment: .leading, spacing: spacing) {
            content
        }
        .padding(.horizontal, Metrics.hPadding)
        // 22 is the gutter content scrolls off into; a page that fits needs none.
        .padding(.bottom, fitsOnePage ? 6 : Metrics.spacing)
        .frame(maxWidth: sizeClass == .regular
               ? (gridsOnWideScreens ? Metrics.maxContentWidthGrid : Metrics.maxContentWidthRegular)
               : Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }
}

/// The house card: content on `.regularMaterial` in a continuous rounded rect — NOT
/// glassEffect (that's for chrome; cards are content).
struct MaterialCard<Content: View>: View {
    var radius: CGFloat = Metrics.radiusCard
    /// Overridable only where a screen has to fit — see `ConsistencyCard`. Everything
    /// else keeps 16 on all four sides.
    var verticalPadding: CGFloat = 16
    /// `.flat` where many cards share one scrolling screen over `AppBackground` (builder,
    /// History, Maxes, Settings): a blur re-renders every frame a card moves, and over the
    /// static field the fitted fill is indistinguishable. `CardSurface` has the measurement.
    var surface: CardSurface = .material
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(.horizontal, 16)
            .padding(.vertical, verticalPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension View {
    /// The house `List` row: a material card on the slate field, so the `List` contributes
    /// its GESTURES (swipe-to-delete, row-slide physics) and none of its decoration. Every
    /// screen needing a swipe uses a real `List` wearing this; a hand-rolled drag never
    /// matches UIKit's.
    func houseListRow(top: CGFloat = 5, bottom: CGFloat = 5) -> some View {
        self
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: top, leading: Metrics.hPadding,
                                      bottom: bottom, trailing: Metrics.hPadding))
    }
}

extension View {
    /// The MUSIC-APP row: full-bleed, no card, a hairline separator inset to the text.
    ///
    /// History's sessions are a LIST OF THINGS THAT HAPPENED, and the Music song list (Nuri,
    /// 2026-08-08) is Apple's answer for that: artwork, two lines, a trailing accessory, a
    /// separator starting where the words do. Cards with gaps make ten sessions look like
    /// ten documents. The summary cards above keep `houseListRow` — they ARE separate
    /// objects, the grouping iOS uses everywhere.
    func sessionListRow(leadingInset: CGFloat = 60) -> some View {
        self
            .listRowBackground(Color.clear)
            .listRowSeparator(.visible)
            .listRowSeparatorTint(Ink.tertiary.opacity(0.25))
            // After the artwork, like Music: a separator under the thumbnail cuts the
            // row in half.
            .alignmentGuide(.listRowSeparatorLeading) { _ in leadingInset }
            .listRowInsets(EdgeInsets(top: 6, leading: Metrics.hPadding,
                                      bottom: 6, trailing: Metrics.hPadding))
    }
}

/// Staggered entrance: fade + rise; a plain fast fade under Reduce Motion.
struct StaggerIn: ViewModifier {
    var index: Int
    @State private var appeared = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(appeared ? 1 : 0)
            .offset(y: appeared || reduceMotion ? 0 : 14)
            .onAppear {
                // Delay CAPPED at four steps: an unbounded ladder leaves tail content
                // invisible for half a second and reads as lag.
                // CRITICALLY DAMPED: overshoot on content that merely appeared is
                // decoration — nothing threw a row that faded in.
                let animation = Motion.state(reduceMotion)
                    .delay(min(Double(index), 4) * 0.05)
                withAnimation(animation) { appeared = true }
            }
    }
}

extension View {
    func staggerIn(_ index: Int) -> some View { modifier(StaggerIn(index: index)) }
}

/// A CLIMB day, marked by a diagonal notch punched clean through the fill.
///
/// Shape, not colour: every calendar state must survive greyscale, Reduce Transparency
/// and colourblindness, and History's `bleu` already rings today. A punched slash still
/// reads at twelve points.
///
/// The fill stays GRAPHITE and full: a climb completes the day, and a lighter cell would
/// contradict Today. Identical in the 12 pt strip and the 28 pt grid, so it is learned
/// once.
struct ClimbNotch: ViewModifier {
    func body(content: Content) -> some View {
        content
            .overlay {
                Rectangle()
                    .frame(width: 2.5)
                    .frame(maxHeight: .infinity)
                    // Overscaled before rotating so the slash spans a square cell's corners.
                    .scaleEffect(y: 1.8)
                    .rotationEffect(.degrees(45))
                    // A real HOLE, not a stroke in the backdrop's colour: over material and a
                    // mesh, nothing can be painted to match what is behind.
                    .blendMode(.destinationOut)
            }
            .compositingGroup()
    }
}

extension View {
    /// `compositingGroup` is not free, so this applies only when there is a notch to punch.
    @ViewBuilder
    func climbNotch(_ show: Bool) -> some View {
        if show { modifier(ClimbNotch()) } else { self }
    }
}

/// A BENCHMARK day, marked by a round bore through the centre of the fill — the third
/// glyph, beside the plain fill (hangs) and the slash (climbs).
///
/// A hole for the notch's reason. Round, so at twelve points it reads as a different
/// KIND of mark, not another angle of the slash. Shape carries the meaning; the bleu
/// fill is the glance, never the message.
struct BenchmarkBore: ViewModifier {
    /// Bore diameter, passed in because 12 pt dots and 28 pt cells cannot share a constant.
    var size: CGFloat

    func body(content: Content) -> some View {
        content
            .overlay {
                Circle()
                    .frame(width: size, height: size)
                    .blendMode(.destinationOut)
            }
            .compositingGroup()
    }
}

extension View {
    /// Same contract as `climbNotch`: applied only when there is a bore to punch.
    @ViewBuilder
    func benchmarkBore(_ show: Bool, size: CGFloat) -> some View {
        if show { modifier(BenchmarkBore(size: size)) } else { self }
    }
}

/// A label styled in the app's "instrument" voice: small caps, tracked, tertiary ink.
struct CapsLabel: View {
    var text: String
    var size: CGFloat = 12
    var tint: Color = Ink.tertiary

    init(_ text: String, size: CGFloat = 12, tint: Color = Ink.tertiary) {
        self.text = text
        self.size = size
        self.tint = tint
    }

    var body: some View {
        Text(text)
            .font(.labelCaps(size))
            .tracking(0.8)
            .foregroundStyle(tint)
    }
}
