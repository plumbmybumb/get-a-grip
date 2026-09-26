// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// Chips express categorical choices: position, hands and sessions per day.
// Quantities use ValueRow, where a slider and direct entry share the same value.

/// One capsule option.
///
/// Selected draws a single glass surface; unselected is a hairline capsule with **no
/// glass at all**. Load-bearing: an expanded set row can carry twenty-plus chips, and
/// every glass surface re-blurs its backdrop.
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
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 12)
                .padding(.vertical, Metrics.buttonVerticalPadding)
                // Drawn at 44 and shaped at 44: the visual IS the target, and padding plus
                // a capsule background are not hit-tested without the content shape.
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
            // `.accessibleGlass`: under Reduce Transparency raw glass loses its tint,
            // the only thing saying which chip is chosen.
            content.accessibleGlass(Accent.graphiteFlat.opacity(0.20), in: .capsule)
        } else {
            content.overlay(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
        }
    }
}

// MARK: - Layout

/// The shared chip layout: six across, thinning as text grows (6 → 3 at
/// `.accessibility1` → 2 at `.accessibility3`). `base` is the comfortable count for
/// THIS row's labels.
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
        // ADAPTIVE, not a fixed count: a fixed 6-column grid squeezed cells to ~48pt
        // and the edge row rendered "6…, 8…, 1…, 1…, 1…, 1…" — 10, 12, 15 and 18 mm
        // indistinguishable. `minimum` scales with type size, so the 6 → 3 → 2 ladder
        // still happens; rows with a unit take one fewer column instead of truncating.
        [GridItem(.adaptive(minimum: minimumChipWidth), spacing: 8, alignment: .leading)]
    }

    /// `base` is the row's intended column count at standard type (6 for numbers, 3 for
    /// "Half crimp", 2 for "Alternate each pull"). It sets a minimum width rather than a hard
    /// count, so the row wraps a chip instead of truncating one.
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
                Text("Start in half crimp and curl your fingers into the edge.")
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

    /// A position written by a newer build is not in `known` and still gets a chip — the
    /// reason `GripPosition` is an open struct. Dropping it would let this build silently
    /// rewrite the set.
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
/// **"Both hands" leads and is the default**: the honest answer for anyone who has not
/// thought about it, and the pre-hands behaviour (applies to every rep of that grip).
/// Choosing a side says "this number is NOT true of my other hand", and the engine treats
/// it that way — see `MaxTable`.
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

    /// NOT `Side.name`, the runner's arm's-length vocabulary ("Left", "Both"). Here the
    /// question is which hand the max is for, answered by "Both hands", "Left hand".
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
