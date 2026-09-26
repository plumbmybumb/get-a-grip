// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Page 1 of the builder: the routine's own timing and how the hands share the work.
///
/// **Laid out to fit ONE screen while creating, iPhone SE included** — measured at 484 of
/// 628 pt on an iPhone 17 and 485 of 497 pt on an SE (2026-09-26); three full dials took
/// 985. Every number is a `− value +` row on the dial's own ladder (see
/// `ValueControl.ladderStepper`), so the exact values a protocol names are one tap apart.
///
/// Hold and rest here are what every set FOLLOWS; a set opts out with Custom timing
/// (`SetRowView`). The label is a plain row, never a `Section` header: plain headers PIN.
struct RhythmSection: View, Equatable {
    /// The write path. Nothing is DRAWN from it — see `defaults`, `==` and `BuilderInputs`.
    let access: DraftAccess
    /// `plan.routineLevel`, as a value, so the card compares on what it shows and sits out
    /// every edit that is not its own.
    let defaults: SessionPlan
    /// The strip previews the first set's pulls — the one set-level number read, so the one
    /// compared.
    let firstSetReps: Int

    @Environment(\.dynamicTypeSize) private var typeSize

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.defaults.rhythmKey == b.defaults.rhythmKey && a.firstSetReps == b.firstSetReps
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            MaterialCard(verticalPadding: 4, surface: .flat) {
                VStack(alignment: .leading, spacing: 0) {
                    TimingStepper(kind: .hold,
                                  value: access.binding(\.plan.holdSeconds, current: defaults.holdSeconds))
                    rowDivider
                    TimingStepper(kind: .rest,
                                  value: access.binding(\.plan.restSeconds, current: defaults.restSeconds))
                    rowDivider
                    TimingStepper(kind: .setBreak,
                                  value: access.binding(\.plan.setBreakSeconds, current: defaults.setBreakSeconds))
                    rowDivider
                    releaseToggle
                        .frame(minHeight: 46)
                }
            }

            // The critical force setup's hands, exactly (Nuri, 2026-09-26): the label row
            // carries which hand goes first, one segmented control underneath, no card.
            VStack(alignment: .leading, spacing: 8) {
                HandsHeader(menuTitle: defaults.handMode.sideCount > 1 ? startingHandTitle : nil,
                            side: access.binding(\.plan.startingHand, current: defaults.startingHand),
                            leftTitle: String(localized: "Left first"),
                            rightTitle: String(localized: "Right first"),
                            hiddenReason: String(localized: "Both hands pull together, so neither goes first."),
                            reservesMenuHeight: true)
                    .padding(.leading, 6)
                handModePicker
                // The only picture of what "Alternate" does to a set, so it stays; centred
                // under the control it describes.
                HandOrderStrip(mode: defaults.handMode,
                               startingHand: defaults.startingHand,
                               repsPerSide: firstSetReps)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 4)
            }
        }
    }

    private var startingHandTitle: String {
        defaults.startingHand == .right ? String(localized: "Right first") : String(localized: "Left first")
    }

    /// ONE native segmented control. At accessibility sizes its three labels would
    /// truncate, so the wrapping chips come back there — words over a tidy row.
    @ViewBuilder
    private var handModePicker: some View {
        if typeSize.isAccessibilitySize {
            HandModeChipRow(selection: access.binding(\.plan.handMode, current: defaults.handMode))
        } else {
            Picker(String(localized: "Hands"),
                   selection: access.binding(\.plan.handMode, current: defaults.handMode)) {
                ForEach(HandMode.allCases, id: \.self) { mode in
                    Text(mode.segmentName).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .sensoryFeedback(.selection, trigger: defaults.handMode)
        }
    }

    /// Whether the rest clock waits for your hand to come off the edge.
    ///
    /// ON by default: otherwise the two or three seconds of standing down off a 20 mm edge
    /// come out of every rest. Off is a real choice — a fixed cadence you pace yourself to,
    /// as metronome-style protocols want.
    private var releaseToggle: some View {
        Toggle("Start the rest when I let go",
               isOn: access.binding(\.plan.waitForReleaseBeforeRest, current: defaults.waitForReleaseBeforeRest))
            .font(.system(.subheadline, weight: .medium))
            .foregroundStyle(Ink.primary)
            .tint(Accent.graphite)
    }

    private var rowDivider: some View {
        Divider().overlay(Ink.tertiary.opacity(0.22))
    }
}

// MARK: - Timing steppers

/// One timing number as a `− value +` row on its ladder. Shared by the Rhythm page and a
/// set's Custom timing, so the two can never offer different stops.
struct TimingStepper: View {
    enum Kind {
        case hold, rest, setBreak
    }

    let kind: Kind
    @Binding var value: Int

    /// Every detent the shipping protocols use (3 s C4 holds, 5/7/10/12 s repeaters,
    /// 15–60 s rests). Hold and rest differ only at the floor: a 0 s rest is a cadence, a
    /// 0 s hold is not a hold.
    static let secondsLadder: [Double] = [3, 5, 7, 10, 12, 15, 20, 30, 45, 60]

    var body: some View {
        switch kind {
        case .hold:
            IntValueRow(title: String(localized: "Hold"), unit: String(localized: "s"),
                        value: $value, range: 1...60, limit: SetPlan.holdRange,
                        control: .ladderStepper([1] + Self.secondsLadder),
                        spokenUnit: String(localized: "seconds"))
        case .rest:
            IntValueRow(title: String(localized: "Rest between pulls"), unit: String(localized: "s"),
                        value: $value, range: 0...60, limit: SetPlan.restRange,
                        control: .ladderStepper([0] + Self.secondsLadder),
                        spokenUnit: String(localized: "seconds"))
        case .setBreak:
            IntValueRow(title: String(localized: "Break between sets"), unit: String(localized: "s"),
                        value: $value, range: 0...240, limit: SessionPlan.setBreakRange,
                        control: .ladderStepper([0, 15, 30, 45, 60, 90, 120, 180, 240]),
                        spokenUnit: String(localized: "seconds"))
        }
    }
}

extension HandMode {
    /// The segmented control's short form: the section is labelled HANDS, so the noun goes.
    var segmentName: String {
        switch self {
        case .alternateEachRep: String(localized: "Alternate")
        case .alternateEachSet: String(localized: "One at a time")
        case .bothHands:        String(localized: "Both")
        }
    }
}
