// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// Shared screen chrome, ported from Schengen Slice's RootTabView.swift.

/// Standard tab-screen canvas: textured slate mesh, scrolling content column with
/// the house width cap, and real navigation chrome.
///
/// The system title is load-bearing, not decoration: it is what buys the
/// large-to-inline collapse, the scroll edge effect where content meets chrome, and
/// a toolbar to hang actions off. Without it Liquid Glass has no chrome layer to BE,
/// which is what makes an app read as styled-rather-than-native.
struct ScreenScaffold<Content: View>: View {
    var title: String
    var subtitle: String? = nil
    var spacing: CGFloat = Metrics.spacing
    /// **This screen is meant to fit on one page** — a dashboard rather than a document
    /// (Nuri, 2026-08-09: *"there's no reason it needs to scroll"*).
    ///
    /// It is still a ScrollView, and that is deliberate twice over. `.basedOnSize` already
    /// means "no scrolling, and no rubber-band, when the content fits", so a page that
    /// fits genuinely does not move — the earlier complaint was simply that Today had
    /// grown about forty points too tall, and a real scroll collapses the large title and
    /// slides the whole screen. And at accessibility text sizes it WILL overflow, where
    /// scrolling beats clipping a Start button nobody can reach.
    ///
    /// `ViewThatFits` was tried here and is wrong: the size it proposes does not account
    /// for the large title, so it chose the flat layout and ran the last card under the
    /// tab bar.
    ///
    /// **The LARGE title stays.** Sending it inline bought the missing points and was the
    /// wrong trade — it is the piece of real UIKit chrome that makes the screen read as a
    /// native app rather than a styled one (Nuri: *"I liked the bigger today header, I
    /// think that respects the Apple design better"*). The height comes out of the content
    /// instead, starting with the bottom margin: 22 is the gutter content scrolls off
    /// into, and nothing scrolls off a page that fits.
    var fitsOnePage: Bool = false
    @ViewBuilder var content: Content

    var body: some View {
        NavigationStack {
            ScrollView { column }
                // No rubber-band when everything already fits — that phantom bounce is
                // enough to collapse the large title. It still scrolls when content
                // genuinely overflows, because the alternative is clipping something
                // unreachable.
                .scrollBounceBehavior(.basedOnSize)
                .scrollEdgeEffectStyle(.soft, for: .bottom)
            // ALWAYS via `.background {}`, never as a ZStack sibling — as a sibling it
            // disturbs the ScrollView's safe-area layout and the title creeps under
            // the status bar.
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
        // 22 is the gutter content scrolls off into. Nothing scrolls off a page that
        // cannot scroll, so it is the cheapest 16 pt on the screen.
        .padding(.bottom, fitsOnePage ? 6 : Metrics.spacing)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }
}

/// The house card: content on `.regularMaterial` in a continuous rounded rect —
/// deliberately NOT glassEffect (that's for chrome; cards are content).
struct MaterialCard<Content: View>: View {
    var radius: CGFloat = Metrics.radiusCard
    /// Overridable only where a screen has to fit — see `ConsistencyCard`. Everything
    /// else keeps 16 on all four sides.
    var verticalPadding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(.horizontal, 16)
            .padding(.vertical, verticalPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension View {
    /// The house `List` row: a material card floating on the slate field, so the `List`
    /// contributes its GESTURES — swipe-to-delete, the row-slide physics — and none of
    /// its decoration. Every screen that needs a swipe uses a real `List` wearing this;
    /// a hand-rolled drag gesture never matches UIKit's.
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
    /// History's sessions are a LIST OF THINGS THAT HAPPENED, and Apple's own answer for
    /// that — the Music song list Nuri asked us to copy (2026-08-08) — is artwork, two
    /// lines of text, a trailing accessory and a separator that starts where the words
    /// do. Cards with gaps between them make ten sessions look like ten documents; this
    /// makes them look like a log, and it fits far more of one on a screen.
    ///
    /// The summary cards above keep `houseListRow` — they ARE separate objects, and the
    /// contrast between "cards for summaries, plain rows for the log" is the same
    /// grouping iOS uses everywhere.
    func sessionListRow(leadingInset: CGFloat = 60) -> some View {
        self
            .listRowBackground(Color.clear)
            .listRowSeparator(.visible)
            .listRowSeparatorTint(Ink.tertiary.opacity(0.25))
            // Starts after the artwork, exactly like Music — a separator running under
            // the thumbnail cuts the row in half instead of dividing it from the next.
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
                // Delay CAPPED at four steps: an unbounded 0.06s-per-row ladder leaves
                // tail content invisible for half a second on list-y screens, and
                // choreography that long reads as lag.
                // CRITICALLY DAMPED, with only the stagger for choreography. It used to
                // carry `dampingFraction: 0.82`, and overshoot on content that merely
                // appeared is decoration — Apple spends bounce on things a hand actually
                // threw, and nothing threw a row that faded in.
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
/// Shape, not colour. The house rule on both calendars is that every state survives
/// greyscale, Reduce Transparency and colourblindness, which a tone-only difference does
/// not — and in History's grid the obvious accent is already spoken for, since `bleu`
/// rings today. (Nuri asked for "a different colour, dashed or something"; a punched
/// slash is the version that still reads at twelve points.)
///
/// The fill stays GRAPHITE and stays full: a climb completes the day, and a lighter or
/// partial-looking cell would contradict the sentence on Today. Identical glyph in the
/// 12 pt strip and the 28 pt grid, so the vocabulary is learned once.
struct ClimbNotch: ViewModifier {
    func body(content: Content) -> some View {
        content
            .overlay {
                Rectangle()
                    .frame(width: 2.5)
                    .frame(maxHeight: .infinity)
                    // Overscaled before rotating so the slash still spans the corners of
                    // a square cell rather than stopping short of them.
                    .scaleEffect(y: 1.8)
                    .rotationEffect(.degrees(45))
                    // A real HOLE, not a stroke in the backdrop's colour: these cells sit
                    // on a material card over a mesh field, so nothing can be painted to
                    // match what is behind them.
                    .blendMode(.destinationOut)
            }
            .compositingGroup()
    }
}

extension View {
    /// `compositingGroup` is not free, so the modifier is applied only when there is a
    /// notch to punch rather than always-on with an invisible slash.
    @ViewBuilder
    func climbNotch(_ show: Bool) -> some View {
        if show { modifier(ClimbNotch()) } else { self }
    }
}

/// A BENCHMARK day, marked by a round bore punched through the centre of the fill —
/// the calendars' third glyph, beside the plain fill (hangs) and the slash (climbs).
///
/// A hole, for the same reason the notch is one: these cells sit on material over the
/// mesh field, so nothing can be painted to match what is behind them. Round rather
/// than another slash because it should read as a different KIND of mark at twelve
/// points, not a different angle of the same one — the gauge's point against the
/// climb's stroke. Shape carries the meaning; the bleu fill the calendars pair it
/// with is the glance, never the message (see `StatusTint`, and the house rule that
/// every state survives greyscale).
struct BenchmarkBore: ViewModifier {
    /// Bore diameter — proportional to the cell, passed in because the strip's 12 pt
    /// dots and the grid's 28 pt cells cannot share a constant.
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
