// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// The app's ONE selection control. Every choice in the builder — hold, rest, break,
// pulls, edge, position, hands, sessions a day, threshold, lead-in — is a row of these,
// so a change costs exactly one tap and the whole document reads in one language.
// No wheels, no drag-scrubs, no bare steppers as the primary control: a wheel hides
// every value but one, and a scrub cannot be hit accurately with chalk on your fingers.

/// One capsule option.
///
/// Selected draws a single glass surface; unselected is a hairline capsule with **no
/// glass at all**. That asymmetry is load-bearing rather than cosmetic: an expanded set
/// row carries twenty-plus chips, and every glass surface re-blurs its backdrop. One
/// glass surface per row, not ten.
struct Chip: View {
    var title: String
    var isSelected: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(.subheadline, weight: isSelected ? .semibold : .medium))
                .monospacedDigit()
                .foregroundStyle(isSelected ? Ink.primary : Ink.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 12)
                // Drawn at 44 and shaped at 44: the visual IS the target. Padding and a
                // capsule background contribute nothing to SwiftUI's default hit area,
                // so the content shape is mandatory, not decoration.
                .frame(maxWidth: .infinity, minHeight: 44)
                .modifier(ChipSurface(isSelected: isSelected))
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}

/// The two chip states, kept in one place so no row can invent a third.
private struct ChipSurface: ViewModifier {
    var isSelected: Bool

    func body(content: Content) -> some View {
        if isSelected {
            // `.accessibleGlass`, never raw `.glassEffect`: under Reduce Transparency the
            // tint vanishes, and with it the only thing saying which chip is chosen.
            content.accessibleGlass(Accent.graphiteFlat.opacity(0.20), in: .capsule)
        } else {
            content.overlay(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
        }
    }
}

// MARK: - Layout

/// The shared chip layout: six across, thinning as text grows.
///
/// The ladder is 6 → 3 at `.accessibility1` → 2 at `.accessibility3`. `base` is the
/// comfortable count for THIS row's labels — six for "20 s", fewer for "Half crimp" or
/// "Alternate each pull", which cannot survive a 48pt cell at any type size.
struct ChipGrid<Content: View>: View {
    var base: Int
    @ViewBuilder var content: Content
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            content
        }
    }

    private var columns: [GridItem] {
        // ADAPTIVE, not a fixed count: a fixed 6-column grid squeezes each cell to about
        // 48pt, and "20 mm" does not fit — measured on the pinned sim, the edge row
        // rendered as "6…, 8…, 1…, 1…, 1…, 1…", so 10, 12, 15 and 18 mm were literally
        // indistinguishable. A chip row whose labels can't be read is not a control.
        // `minimum` scales with the type size, so the frozen 6 → 3 → 2 ladder still
        // happens on a numbers-only row; rows carrying a unit simply take one fewer
        // column instead of truncating.
        [GridItem(.adaptive(minimum: minimumChipWidth), spacing: 8, alignment: .leading)]
    }

    /// `base` is the row's intended column count at standard type, and it still carries
    /// the caller's real information: a numeric row (6) wants narrow cells, while
    /// "Half crimp" (3) and "Alternate each pull" (2) need wide ones. It sets the
    /// minimum width rather than a hard count, so the row keeps its intended density
    /// and simply wraps a chip instead of truncating one.
    private var minimumChipWidth: CGFloat {
        let standard: CGFloat = switch base {
        case ...2: 150
        case 3: 104
        case 4...5: 76
        default: 56
        }
        if typeSize >= .accessibility3 { return standard * 2.2 }
        if typeSize >= .accessibility1 { return standard * 1.6 }
        return standard
    }
}

/// How a row swaps between its chips and its stepper. Reduce Motion gets the same
/// change with none of the travel.
private func chipSwap(_ reduceMotion: Bool) -> Animation {
    Motion.state(reduceMotion)
}

// MARK: - Whole-number rows

/// Seconds, millimetres, pulls, sessions — anything counted in whole units.
///
/// The row ends in `Other…`, which swaps the chips in place for a `Stepper` bounded by
/// `otherRange`: one tap for the ~95 % of choices that are on the menu, a real escape
/// hatch for the rest, and no second screen for either.
struct IntChipRow: View {
    let values: [Int]
    var unit: String = ""
    @Binding var selection: Int
    var otherRange: ClosedRange<Int>? = nil

    @State private var showsCustom = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// A value that is not on the menu shows the stepper WITHOUT being asked. A chip row
    /// rendering with nothing selected reads as a bug, and silently snapping the value to
    /// the nearest chip would edit a routine the user never touched.
    private var isCustom: Bool {
        otherRange != nil && (showsCustom || !values.contains(selection))
    }

    /// The chips as drawn. A row with no `Other…` escape gives an off-menu value its own
    /// chip rather than dropping it — same rule, and the same reason, as `PositionChipRow`.
    private var drawnValues: [Int] {
        guard otherRange == nil, !values.contains(selection) else { return values }
        return (values + [selection]).sorted()
    }

    /// Density is driven by the row's OWN longest label, not by a fixed column count.
    /// "1 2 3 4 5 6" and "6 mm 8 mm 10 mm" want different grids, and a single frozen
    /// number gives one of them truncated chips: at six columns the edge row rendered
    /// "10…, 12…, 15…, 18…", which is worse than useless — those are four different
    /// edges a climber has to tell apart.
    private var densityForLongestLabel: Int {
        // The VALUES decide the density, not "Other…" — it is one chip at the end of the
        // row and can wrap, whereas letting its six characters set the width would drop
        // a row of bare numbers from six columns to four for nothing.
        let longest = drawnValues.map { label($0).count }.max() ?? 1
        return switch longest {
        case ...2: 6
        case 3...4: 5
        default: 4
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if isCustom, let otherRange {
                custom(in: otherRange)
            } else {
                ChipGrid(base: densityForLongestLabel) {
                    ForEach(drawnValues, id: \.self) { value in
                        Chip(title: label(value), isSelected: value == selection) {
                            selection = value
                        }
                    }
                    if otherRange != nil {
                        Chip(title: String(localized: "Other…"), isSelected: false) {
                            withAnimation(chipSwap(reduceMotion)) { showsCustom = true }
                        }
                    }
                }
            }
        }
        .sensoryFeedback(.selection, trigger: selection)
    }

    @ViewBuilder
    private func custom(in range: ClosedRange<Int>) -> some View {
        // A Stepper states its value, moves by exactly one unit and reads to VoiceOver as
        // "adjustable" for free — none of which is true of a wheel or a scrub. Its own
        // label is the value, so the control needs no separate caption.
        Stepper(value: $selection, in: range, step: step(for: range)) {
            Text(label(selection))
                .font(.system(.body, weight: .semibold))
                .monospacedDigit()
                .contentTransition(.numericText())
                .foregroundStyle(Ink.primary)
        }
        .frame(minHeight: 44)

        Button {
            withAnimation(chipSwap(reduceMotion)) {
                showsCustom = false
                // Snap on the way back, so the chips can never reappear with no answer
                // showing. The move is visible — the nearest chip lights up.
                selection = nearest(to: selection)
            }
        } label: {
            Text("Back to the usual values")
                .font(.system(.footnote, weight: .medium))
                .foregroundStyle(Accent.graphite)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }

    private func label(_ value: Int) -> String {
        unit.isEmpty ? "\(value)" : "\(value) \(unit)"
    }

    /// Rest and set-break run to 600 and 900 seconds; one-unit steps there are a
    /// thousand taps. Ranges that tight-fit a real edit keep their unit step.
    private func step(for range: ClosedRange<Int>) -> Int {
        range.upperBound - range.lowerBound > 120 ? 5 : 1
    }

    private func nearest(to value: Int) -> Int {
        values.min { abs($0 - value) < abs($1 - value) } ?? value
    }
}

// MARK: - Kilogram rows


struct PositionChipRow: View {
    @Binding var selection: GripPosition
    /// `false` lays the chips out as ONE horizontally scrolling row instead of a
    /// wrapping grid — six grips wrap to two rows, and on the setup deck's grip
    /// card that second row is 45 pt the card does not have. The document wraps, because
    /// there the height is free and seeing every option at once is worth more.
    var wraps: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Group {
                if wraps {
                    ChipGrid(base: 3) { chips }
                } else {
                    ScrollView(.horizontal) {
                        HStack(spacing: 8) { chips }
                            .padding(.vertical, 2)
                    }
                    .scrollIndicators(.hidden)
                    .scrollBounceBehavior(.basedOnSize)
                }
            }
            if selection == .fingerCurl {
                Text("Start in half crimp and build force by trying to curl your fingers into the edge.")
                    .font(.system(.caption))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .sensoryFeedback(.selection, trigger: selection)
    }

    @ViewBuilder
    private var chips: some View {
        ForEach(options, id: \.rawValue) { position in
            Chip(title: position.name, isSelected: position == selection) {
                selection = position
            }
            // Chips fill their container by default, which is right in a grid and wrong
            // in a horizontal scroller.
            .modifier(HugIfNeeded(hugs: !wraps))
        }
    }

    /// A position written by a newer build is not in `known`, and it still gets a chip —
    /// showing it selected is the whole reason `GripPosition` is an open struct rather
    /// than an enum. Dropping it here would let this build silently rewrite the set.
    private var options: [GripPosition] {
        GripPosition.known.contains(selection) ? GripPosition.known : GripPosition.known + [selection]
    }
}

/// `.fixedSize()` applied conditionally, which a plain `if` inside a `ForEach` body
/// cannot express without changing the view's identity on every toggle.
private struct HugIfNeeded: ViewModifier {
    var hugs: Bool

    func body(content: Content) -> some View {
        if hugs { content.fixedSize() } else { content }
    }
}

/// How a set is shared between hands.
struct HandModeChipRow: View {
    @Binding var selection: HandMode

    // Two across at most: "Alternate each pull" cannot be read in a 48pt cell.
    var body: some View {
        ChipGrid(base: 2) {
            ForEach(HandMode.allCases, id: \.self) { mode in
                Chip(title: mode.name, isSelected: mode == selection) {
                    selection = mode
                }
            }
        }
        .sensoryFeedback(.selection, trigger: selection)
    }
}

/// Which hand a max was pulled with.
///
/// **"Both hands" leads and is the default**, because it is the honest answer for anyone
/// who has not thought about it and the one that keeps the app behaving exactly as it did
/// before hands existed — a both-hands max applies to every rep of that grip. Choosing a
/// side is a deliberate act that says "this number is NOT true of my other hand", and the
/// engine treats it that way: see `MaxTable`.
struct MaxSideChipRow: View {
    @Binding var selection: Side

    /// Both first, then the hands in the order the runner alternates them.
    private static let order: [Side] = [.both, .left, .right]

    var body: some View {
        ChipGrid(base: 3) {
            ForEach(Self.order, id: \.self) { side in
                Chip(title: name(side), isSelected: selection == side) {
                    selection = side
                }
                .accessibilityLabel(spoken(side))
            }
        }
        .sensoryFeedback(.selection, trigger: selection)
    }

    /// NOT `Side.name` — that vocabulary is the runner's ("Left", "Both"), read at arm's
    /// length mid-set. Here the chips answer "which hand is this max for", where "Both
    /// hands" and "Left hand" are what the question actually wants back.
    private func name(_ side: Side) -> String {
        switch side {
        case .both:  String(localized: "Both hands")
        case .left:  String(localized: "Left hand")
        case .right: String(localized: "Right hand")
        }
    }

    private func spoken(_ side: Side) -> String {
        side == .both ? String(localized: "For both hands") : String(localized: "For the \(side.name.lowercased()) hand only")
    }
}

#Preview {
    @Previewable @State var hold = 10
    @Previewable @State var position = GripPosition.halfCrimp
    @Previewable @State var hands = HandMode.alternateEachRep
    @Previewable @State var side = Side.both

    ScrollView {
        VStack(alignment: .leading, spacing: 18) {
            CapsLabel("SESSIONS A DAY")
            IntChipRow(values: [1, 2, 3, 4], selection: $hold)
            CapsLabel("GRIP")
            PositionChipRow(selection: $position)
            CapsLabel("HANDS")
            HandModeChipRow(selection: $hands)
            CapsLabel("THIS MAX IS FOR")
            MaxSideChipRow(selection: $side)
        }
        .padding(Metrics.hPadding)
    }
    .background { AppBackground() }
}
