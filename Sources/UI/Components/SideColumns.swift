// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// **The system's side column, used the way Flighty uses it** (Nuri, 2026-11-03: "instead
/// of it being a hard line, using the liquid glass … I want to be using that space to the
/// right").
///
/// On the iPhone Duo's outer display (and, on hardware, in Split View) the tab bar, the
/// clock and the island stand in a vertical column on the trailing edge, and the window
/// reports it as a horizontal safe-area inset: 84 pt on the simulator, measured with
/// `-dumpInteractions` and the iOS 27.1 layout-region probes. That inset already encodes
/// the system's own geometry — 24 pt bar margin + 48 pt glass + a 12 pt gap — so the
/// safe-area line IS the line Flighty's cards stop on (11.9 pt short of its glass,
/// pixel-measured from Nuri's screenshots). Before this, the content column added the
/// house 20 pt on top of it and every rail was clipped and faded at that line
/// (`ClearOfSideBars`): a 32–41 pt dead gutter beside a column of bare background.
///
/// Three rules, each a modifier below, all keyed on `SideColumns` from the environment:
/// - **The content column ends ON the safe-area line** on a side that carries a column
///   (`columnPadding()`, `houseListRow`, `sessionListRow`): zero padding there, the house
///   20 on the other side. Text and controls never enter the column, so nothing legible
///   ever sits under a glass button or beside the clock.
/// - **Horizontal rails run UNDER the column to the window edge** (`sideColumnRail`): the
///   settled card still lands on the column's line, and the neighbour continues beneath
///   the glass instead of being cut and faded at the line.
/// - **The status band stays clear.** The top of the column is an occlusion region (the
///   camera, clock, wifi and battery, 0–174 pt) drawn straight over our background with no
///   glass. A rail passing through it dissolves over the band's last `statusFade` points,
///   like a scroll edge effect, so a card corner never collides with the battery dots.
///
/// **Keyed on the inset, never on width or the idiom.** Every iPhone (portrait-only) and
/// every iPad reports zero horizontal inset, so every modifier here reduces to exactly the
/// layout it replaced — pixel-diffed on the iPhone 17 Pro and an iPad Pro. The Split View
/// simulator gives a BOTTOM bar (inset 0, correctly unchanged) where hardware gives a
/// vertical one, which is why width can never stand in for this.
///
/// Read ONCE, at the root (`readsSideColumns`), from the root's own geometry — the window
/// — and re-read whenever it changes: a fold, a rotation, a Split View drag. Leading and
/// trailing are layout-direction terms, so right-to-left mirrors with no extra code.
/// **Sheets are not beside the column** and reset it (`MaxEditSheet`,
/// `CriticalForceHistorySheet`): an environment value flows into every presentation.
struct SideColumns: Equatable {
    /// The column's width on each side, zero where there is none.
    var leading: CGFloat = 0
    var trailing: CGFloat = 0
    /// Window y where the column's status band (clock, wifi, battery, camera) ends; zero
    /// when the system reports none (anything before iOS 27.1, which has no side columns).
    var statusBottom: CGFloat = 0

    static let none = SideColumns()

    var isEmpty: Bool { leading == 0 && trailing == 0 }

    /// The content column's padding on each side: zero against a column, the house margin
    /// everywhere else.
    var leadingPadding: CGFloat { leading > 0 ? 0 : Metrics.hPadding }
    var trailingPadding: CGFloat { trailing > 0 ? 0 : Metrics.hPadding }

    /// How far the status band dissolves a rail. The battery dots end 23 pt above the
    /// band's reported bottom (151 vs 174, pixel-measured), so the fade is clear of them.
    static let statusFade: CGFloat = 22

    init() {}

    init(_ proxy: GeometryProxy) {
        let insets = proxy.safeAreaInsets
        leading = insets.leading
        trailing = insets.trailing
        guard leading > 0 || trailing > 0 else { return }
        if #available(iOS 27.1, *) {
            let width = proxy.size.width
            let column = max(leading, trailing)
            // Only regions that live IN a side column: the camera hole and the status
            // column, never an island at the top of a phone.
            for region in proxy.reservedRegions(kind: .occlusion) where region.isActive {
                let frame = region.frame
                if frame.maxX <= column + 0.5 || frame.minX >= width - column - 0.5 {
                    statusBottom = max(statusBottom, frame.maxY)
                }
            }
        }
    }
}

private struct SideColumnsKey: EnvironmentKey {
    static var defaultValue: SideColumns { .none }
}

extension EnvironmentValues {
    /// Set once by `RootTabView` — see `SideColumns`.
    var sideColumns: SideColumns {
        get { self[SideColumnsKey.self] }
        set { self[SideColumnsKey.self] = newValue }
    }
}

/// The root reader. Its geometry is the window's, so its safe area is the window's.
private struct ReadsSideColumns: ViewModifier {
    @State private var columns = SideColumns.none

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: SideColumns.self) { SideColumns($0) } action: { next in
                if next != columns { columns = next }
            }
            .environment(\.sideColumns, columns)
    }
}

/// A content column's horizontal padding: the house margin, except zero on a side that
/// carries a system column.
private struct ColumnPadding: ViewModifier {
    @Environment(\.sideColumns) private var side

    func body(content: Content) -> some View {
        content
            .padding(.leading, side.leadingPadding)
            .padding(.trailing, side.trailingPadding)
    }
}

/// A horizontal deck that runs under the side column to the window's edge.
///
/// The rail is widened by negative padding until it reaches the window edge on a column
/// side, and the SAME distance is given back as a content margin, so the settled card
/// lands on the column's line and only the neighbour travels on beneath the glass.
///
/// **The widening is SYMMETRIC** — both sides by the larger of the two — with the excess
/// hanging off-screen on the side that has no column, so the rail stays centred on the
/// page column it belongs to. In forced right-to-left beside a column the cards land on
/// the grid but the deck opens one card toward its end (it does on every order of these
/// modifiers tried); the app ships English and French only, so no user reaches it.
///
/// `ignoresSafeArea` stops the scroll view adding the column's inset to those margins on
/// its own (a scroll view extends into, and insets for, any safe-area edge it touches).
/// Without a column all of this is the deck geometry it replaced, value for value:
/// full-bleed inside the page column, the house margin leading, house + `peek` trailing.
private struct SideColumnRail: ViewModifier {
    @Environment(\.sideColumns) private var side
    /// Only without a column: beside one the neighbour's peek IS the column.
    var peek: CGFloat
    var insideColumn: Bool

    func body(content: Content) -> some View {
        // How far each edge must travel to reach the window edge: the page column's own
        // padding (only when the rail sits inside it), plus the side column.
        let reachLeading = (insideColumn ? side.leadingPadding : 0) + side.leading
        let reachTrailing = (insideColumn ? side.trailingPadding : 0) + side.trailing
        let widen = max(reachLeading, reachTrailing)
        // Where the settled card's edge sits inside the rail's natural frame: on the house
        // grid, which a `List` row with zero insets has to supply itself.
        let insetLeading = insideColumn ? 0 : side.leadingPadding
        let insetTrailing = insideColumn ? 0 : side.trailingPadding
        content
            .contentMargins(.leading, widen + insetLeading, for: .scrollContent)
            .contentMargins(.trailing, widen + insetTrailing + (side.isEmpty ? peek : 0),
                            for: .scrollContent)
            // INSIDE the padding. Outside it, a rail whose proposed edge touches the
            // safe-area line (the scaffold's column) was grown by the inset a second time
            // and measured 550 pt wide in a 466 pt window.
            .ignoresSafeArea(.container, edges: .horizontal)
            // On a column side the rail ends exactly at the window edge, so the column's strip
            // is the rail's outermost `side.leading` / `side.trailing` points.
            .mask { SideColumnMask(side: side) }
            .padding(.horizontal, -widen)
    }
}

/// Solid everywhere, except that the part of the rail inside a column fades out over the
/// column's status band. 1000 pt of vertical slack keeps Today's context-menu lift — the
/// card scaled ~2.5 % with its shadow, parented inside this rail — whole above and below.
/// With no column it is one solid rectangle: no effect.
private struct SideColumnMask: View {
    var side: SideColumns

    var body: some View {
        HStack(spacing: 0) {
            if side.leading > 0 {
                StatusBandFade(statusBottom: side.statusBottom).frame(width: side.leading)
            }
            Color.black
            if side.trailing > 0 {
                StatusBandFade(statusBottom: side.statusBottom).frame(width: side.trailing)
            }
        }
        .padding(.vertical, -1000)
    }
}

/// Transparent above the status band's bottom (in WINDOW coordinates, so it holds still
/// while the page scrolls the rail through it), ramping to solid over `statusFade`.
/// Only this strip re-evaluates on scroll; the rail's content does not.
private struct StatusBandFade: View {
    var statusBottom: CGFloat

    var body: some View {
        if statusBottom > 0 {
            GeometryReader { proxy in
                let cut = statusBottom - proxy.frame(in: .global).minY
                let fade = SideColumns.statusFade
                Canvas { context, size in
                    let solidTop = max(0, cut)
                    context.fill(Path(CGRect(x: 0, y: solidTop, width: size.width,
                                             height: max(0, size.height - solidTop))),
                                 with: .color(.black))
                    context.fill(Path(CGRect(x: 0, y: cut - fade, width: size.width, height: fade)),
                                 with: .linearGradient(Gradient(colors: [.clear, .black]),
                                                       startPoint: CGPoint(x: 0, y: cut - fade),
                                                       endPoint: CGPoint(x: 0, y: cut)))
                }
            }
        } else {
            Color.black
        }
    }
}

/// A deck card that has slid INTO the side column is a picture, not a control.
///
/// Running the neighbour under the column puts ~76 pt of it on screen beside the bar
/// (Today's: its title, its dark Start button), all of it live — a thumb reaching for the
/// tab bar could start the routine it never chose. Less than half visible means it is
/// the neighbour, and it takes no taps: they fall through to the rail, which still pans,
/// so a swipe begun on the peek still turns the page. No coordinates are involved, so it
/// holds in right-to-left and wherever the deck sits. On a window with no column nothing
/// changes, the 20 pt peek included.
private struct InertInSideColumn: ViewModifier {
    @Environment(\.sideColumns) private var side
    @State private var mostlyVisible = true

    func body(content: Content) -> some View {
        content
            .allowsHitTesting(side.isEmpty || mostlyVisible)
            .onScrollVisibilityChange(threshold: 0.5) { visible in
                if visible != mostlyVisible { mostlyVisible = visible }
            }
    }
}

extension View {
    /// On each card of a `sideColumnRail` deck — see `InertInSideColumn`.
    func inertInSideColumn() -> some View { modifier(InertInSideColumn()) }

    /// At the root only: publishes the window's `SideColumns` to everything below.
    func readsSideColumns() -> some View { modifier(ReadsSideColumns()) }

    /// The content column's horizontal padding — see `SideColumns`. Replaces
    /// `.padding(.horizontal, Metrics.hPadding)` on tab roots and the pages they push.
    func columnPadding() -> some View { modifier(ColumnPadding()) }

    /// A horizontal deck — see `SideColumnRail`. `peek` is the trailing margin past the
    /// house grid on a window with no column (8 gives the house 20 pt peek); `insideColumn`
    /// when the rail sits in a padded content column (Today) rather than a `List` row
    /// with zero insets (History). Apply after the rail's own scroll modifiers.
    func sideColumnRail(peek: CGFloat, insideColumn: Bool) -> some View {
        modifier(SideColumnRail(peek: peek, insideColumn: insideColumn))
    }

    /// For a sheet's root: a presentation is not beside the window's side column.
    func outsideSideColumns() -> some View { environment(\.sideColumns, .none) }
}
