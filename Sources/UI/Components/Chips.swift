// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// Chips express categorical choices: position, hands and sessions per day.
// Quantities use ValueRow, where a slider and direct entry share the same value.

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

// MARK: - Whole-number rows

/// A small categorical integer choice, currently sessions per day. An off-menu stored
/// value receives its own chip so merely opening the editor never rewrites it.
struct IntChipRow: View {
    let values: [Int]
    var unit: String = ""
    @Binding var selection: Int

    private var drawnValues: [Int] {
        values.contains(selection) ? values : (values + [selection]).sorted()
    }

    private var densityForLongestLabel: Int {
        switch drawnValues.map({ label($0).count }).max() ?? 1 {
        case ...2: 6
        case 3...4: 5
        default: 4
        }
    }

    var body: some View {
        ChipGrid(base: densityForLongestLabel) {
            ForEach(drawnValues, id: \.self) { value in
                Chip(title: label(value), isSelected: value == selection) { selection = value }
            }
        }
        .sensoryFeedback(.selection, trigger: selection)
    }

    private func label(_ value: Int) -> String {
        unit.isEmpty ? "\(value)" : "\(value) \(unit)"
    }
}

// MARK: - Grip position

struct PositionChipRow: View {
    @Binding var selection: GripPosition
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ChipGrid(base: 3) { chips }
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
        }
    }

    /// A position written by a newer build is not in `known`, and it still gets a chip —
    /// showing it selected is the whole reason `GripPosition` is an open struct rather
    /// than an enum. Dropping it here would let this build silently rewrite the set.
    private var options: [GripPosition] {
        GripPosition.known.contains(selection) ? GripPosition.known : GripPosition.known + [selection]
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
