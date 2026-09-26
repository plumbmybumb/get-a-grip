// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// REST & HANDS — the parts of a session every set genuinely shares.
///
/// Hold and rest moved onto every set row once the Max day protocol showed timing is a
/// property of a SET (Nuri, 2026-08-10). What is left is what cannot vary per set: the
/// break between sets, how the hands share the work, and when a rest starts counting.
///
/// The label is a plain row, never a `Section` header: plain-style headers PIN.
struct RhythmSection: View, Equatable {
    /// The write path. Nothing is DRAWN from it — see `defaults`, `==` and `BuilderInputs`.
    let access: DraftAccess
    /// `plan.routineLevel`, as a value, so the card compares on what it shows and sits out
    /// every edit that is not its own.
    let defaults: SessionPlan
    /// The strip previews the first set's pulls — the one set-level number read, so the one
    /// compared.
    let firstSetReps: Int
    /// The PAGED builder's Rhythm page (DEBUG prototype, `-builderPages`): the routine's own
    /// hold and rest lead the card, and the hands get a card of their own. Sets still
    /// override per row; a new set follows these.
    var includesPullTiming = false

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.defaults.rhythmKey == b.defaults.rhythmKey && a.firstSetReps == b.firstSetReps
            && a.includesPullTiming == b.includesPullTiming
            && (!a.includesPullTiming
                || (a.defaults.holdSeconds == b.defaults.holdSeconds
                    && a.defaults.restSeconds == b.defaults.restSeconds))
    }

    var body: some View {
        if includesPullTiming { pagedBody } else { documentBody }
    }

    /// Two cards: TIMING (hold, rest, break, when the rest starts) and HANDS.
    private var pagedBody: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                CapsLabel(String(localized: "TIMING"))
                MaterialCard(surface: .flat) {
                    VStack(alignment: .leading, spacing: 12) {
                        IntValueRow(title: String(localized: "Hold"), unit: String(localized: "s"),
                                    value: access.binding(\.plan.holdSeconds, current: defaults.holdSeconds),
                                    range: 1...60, limit: SetPlan.holdRange,
                                    control: .dial([1] + SetRowView.secondsLadder))
                        IntValueRow(title: String(localized: "Rest between pulls"), unit: String(localized: "s"),
                                    value: access.binding(\.plan.restSeconds, current: defaults.restSeconds),
                                    range: 0...60, limit: SetPlan.restRange,
                                    control: .dial([0] + SetRowView.secondsLadder))
                        breakRow
                        releaseToggle
                    }
                }
            }
            VStack(alignment: .leading, spacing: 10) {
                CapsLabel(String(localized: "HANDS"))
                MaterialCard(surface: .flat) {
                    VStack(alignment: .leading, spacing: 12) { handsBlock }
                }
            }
        }
    }

    private var breakRow: some View {
        IntValueRow(title: String(localized: "Break between sets"),
                    unit: String(localized: "s"),
                    value: access.binding(\.plan.setBreakSeconds, current: defaults.setBreakSeconds),
                    range: 0...240, limit: SessionPlan.setBreakRange,
                    control: .dial([0, 30, 60, 90, 120, 180]))
    }

    @ViewBuilder
    private var handsBlock: some View {
        HandModeChipRow(selection: access.binding(\.plan.handMode, current: defaults.handMode))
        HandOrderStrip(mode: defaults.handMode,
                       startingHand: defaults.startingHand,
                       repsPerSide: firstSetReps)
        if defaults.handMode.sideCount > 1 {
            HStack(alignment: .center, spacing: 12) {
                Text(defaults.startingHand == .right
                     ? "Starts on the right hand"
                     : "Starts on the left hand")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                swapHandsButton
            }
        }
    }

    private var documentBody: some View {
        VStack(alignment: .leading, spacing: 10) {
            CapsLabel(String(localized: "REST & HANDS"))

            MaterialCard(surface: .flat) {
                VStack(alignment: .leading, spacing: 12) {
                    IntValueRow(title: String(localized: "Break between sets"),
                                unit: String(localized: "s"),
                                value: access.binding(\.plan.setBreakSeconds, current: defaults.setBreakSeconds),
                                range: 0...240, limit: SessionPlan.setBreakRange,
                                control: .dial([0, 30, 60, 90, 120, 180]))

                    // Here, not in Fine tuning: it decides when every rest STARTS, and a rest
                    // whose meaning is set two cards away cannot be trusted.
                    releaseToggle

                    rowDivider

                    // Categorical, so chips — and ALWAYS expanded: the strip under it is the
                    // only place the app shows what "alternate each pull" does to a set.
                    CapsLabel(String(localized: "HANDS"))
                        .padding(.top, 2)
                    HandModeChipRow(selection: access.binding(\.plan.handMode, current: defaults.handMode))
                    HandOrderStrip(mode: defaults.handMode,
                                   startingHand: defaults.startingHand,
                                   repsPerSide: firstSetReps)
                    if defaults.handMode.sideCount > 1 {
                        // The strip has no legend, so it cannot say which hand starts (Nuri,
                        // 2026-09-18). The sentence says it; the button swaps it.
                        HStack(alignment: .center, spacing: 12) {
                            Text(defaults.startingHand == .right
                                 ? "Starts on the right hand"
                                 : "Starts on the left hand")
                                .font(.system(.footnote, weight: .medium))
                                .foregroundStyle(Ink.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                            swapHandsButton
                        }
                    }
                }
            }
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

    /// The strip draws the FIRST set's sequence, the one about to be done; `executable` so an
    /// emptied-out row cannot decide it.
    /// The one writer of `startingHand` (Nuri, 2026-09-18), beside the sentence naming the
    /// starting hand: tap, and both flip. Hidden under Both hands. The spoken label names
    /// the tap's OUTCOME, as a toggle should.
    private var swapHandsButton: some View {
        let startsRight = defaults.startingHand == .right
        return Button {
            access.mutate { $0.plan.startingHand = startsRight ? .left : .right }
        } label: {
            Label(String(localized: "Swap"), systemImage: "arrow.left.arrow.right")
                .font(.system(.footnote, weight: .medium))
                .foregroundStyle(Ink.secondary)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .overlay(Capsule().stroke(Ink.tertiary.opacity(0.35), lineWidth: 1))
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(startsRight
                            ? String(localized: "Start with the left hand")
                            : String(localized: "Start with the right hand"))
        .sensoryFeedback(.selection, trigger: defaults.startingHand)
    }
}
