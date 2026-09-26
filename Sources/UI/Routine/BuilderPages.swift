// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - The paged builder (1.3.0 design prototype)
//
// The same `BuilderDocument`, laid out as three pages — Rhythm · Sets · Schedule —
// instead of one scrolling document. ONE view for creating and editing, as ever: creating
// walks Next/Back, editing jumps with a switcher, and everything else is shared.
//
// DEBUG only, behind `-builderPages`, so the single document stays the default. The
// variants are launch arguments too, so the same build can be screenshotted both ways:
//
//   -builderPagesIndicator dots|steps   dots under the buttons (A) or the switcher (B)
//   -builderPagesTarget rhythm|sets     routine-wide % on page 1 (A) or per set only (B)
//   -builderPagesRows compact|table     a card per set (A) or one card of rows (B)

/// The three pages, in the order creating walks them.
enum BuilderPage: Int, CaseIterable, Identifiable {
    case rhythm, sets, schedule

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .rhythm:   String(localized: "Rhythm")
        case .sets:     String(localized: "Sets")
        case .schedule: String(localized: "Schedule")
        }
    }

    var next: BuilderPage? { BuilderPage(rawValue: rawValue + 1) }
    var previous: BuilderPage? { BuilderPage(rawValue: rawValue - 1) }
}

/// Which layout the builder draws, read once from the launch arguments.
struct BuilderPagesPrototype {
    enum Indicator { case dots, steps }

    let isOn: Bool
    let indicator: Indicator
    let targetOnRhythm: Bool
    let rowFace: SetRowFace

    static let current: BuilderPagesPrototype = {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        func value(_ flag: String) -> String? {
            guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
            return args[i + 1]
        }
        return BuilderPagesPrototype(
            isOn: args.contains("-builderPages"),
            indicator: value("-builderPagesIndicator") == "steps" ? .steps : .dots,
            targetOnRhythm: value("-builderPagesTarget") != "sets",
            rowFace: value("-builderPagesRows") == "table" ? .table : .compact)
        #else
        return BuilderPagesPrototype(isOn: false, indicator: .dots,
                                     targetOnRhythm: true, rowFace: .compact)
        #endif
    }()

    #if DEBUG
    /// `-flag N`, for the headless states (`simctl` cannot tap).
    static func debugInt(_ flag: String) -> Int? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
        return Int(args[i + 1])
    }

    static func debugString(_ flag: String) -> String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
        return args[i + 1]
    }
    #endif
}

// MARK: - Page dots

/// Where you are in the three pages while creating. Not a control: Back and Next are.
struct BuilderPageDots: View {
    let current: BuilderPage

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 6) {
            ForEach(BuilderPage.allCases) { page in
                Capsule()
                    .fill(page == current ? Accent.graphite : Ink.tertiary.opacity(0.45))
                    .frame(width: page == current ? 18 : 6, height: 6)
            }
        }
        .animation(Motion.state(reduceMotion), value: current)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "\(current.title), page \(current.rawValue + 1) of 3"))
    }
}

// MARK: - The routine's target, as a percentage

/// The load every set follows unless it says otherwise — a percentage of each grip's own
/// max, so one band lands on the right kilograms for every grip at once. Kilograms stay
/// per set: a kilogram figure only means something for one grip.
///
/// Shut by default, like `TargetBandRow`: the header states the load.
struct RoutineTargetRow: View, Equatable {
    /// The routine's band, nil = none.
    let band: ClosedRange<Double>?
    /// Some set carries a target of its own, so "None" here is not the whole story.
    let setsVary: Bool
    /// Writes the routine band and clears every set's own target: one band for all.
    let onChange: (ClosedRange<Double>?) -> Void

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.band == b.band && a.setsVary == b.setsVary
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = false
    @State private var custom = false

    private static let bands: [(label: String, range: ClosedRange<Double>)] = [
        ("15–25", 0.15...0.25),
        ("20–30", 0.20...0.30),
        ("40–60", 0.40...0.60),
        ("80–100", 0.80...1.00),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            if expanded {
                ChipGrid(base: 3) {
                    Chip(title: String(localized: "None"), isSelected: band == nil && !custom && !setsVary) {
                        custom = false
                        onChange(nil)
                    }
                    ForEach(Self.bands, id: \.label) { option in
                        Chip(title: String(localized: "\(option.label) %"),
                             isSelected: band == option.range && !custom) {
                            custom = false
                            onChange(option.range)
                        }
                    }
                    Chip(title: String(localized: "Custom"), isSelected: custom) {
                        custom = true
                        if band == nil { onChange(0.20...0.30) }
                    }
                }
                if custom {
                    IntValueRow(title: String(localized: "From"), unit: "%",
                                value: percentBinding(\.lowerBound), range: 1...100,
                                limit: 1...100, control: .stepper)
                    IntValueRow(title: String(localized: "To"), unit: "%",
                                value: percentBinding(\.upperBound), range: 1...100,
                                limit: 1...100, control: .stepper)
                }
            }
        }
        .onAppear {
            if let band, !Self.bands.contains(where: { $0.range == band }) { custom = true }
        }
    }

    private var header: some View {
        Button {
            withAnimation(Motion.state(reduceMotion)) { expanded.toggle() }
        } label: {
            HStack(spacing: 8) {
                Text("Target load")
                    .font(.system(.subheadline, weight: .medium))
                    .foregroundStyle(Ink.primary)
                Spacer(minLength: 8)
                Text(valueText)
                    .font(.system(.title3, weight: .semibold))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .foregroundStyle(band == nil ? Ink.tertiary : Ink.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "Target load"))
        .accessibilityValue(valueText)
    }

    private var valueText: String {
        if let band {
            return "\(Int((band.lowerBound * 100).rounded()))–\(Int((band.upperBound * 100).rounded())) %"
        }
        return setsVary ? String(localized: "Per set") : String(localized: "None")
    }

    private func percentBinding(_ edge: KeyPath<ClosedRange<Double>, Double>) -> Binding<Int> {
        Binding(
            get: { Int(((band ?? 0.20...0.30)[keyPath: edge] * 100).rounded()) },
            set: { new in
                let current = band ?? 0.20...0.30
                let value = Double(min(max(new, 1), 100)) / 100
                let lo = edge == \.lowerBound ? value : current.lowerBound
                let hi = edge == \.upperBound ? value : current.upperBound
                onChange(min(lo, hi)...max(lo, hi))
            })
    }
}
