// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// REST & HANDS — the parts of a session every set genuinely shares.
///
/// Hold and rest used to live here as routine-level defaults, until the Max day
/// protocol made the flaw obvious (Nuri, 2026-08-10: "what if for one pull you want
/// 10 seconds and for the other 20?") — timing is a property of a SET, and it moved
/// onto every set row. What is left here is only what cannot vary per set: the break
/// between sets, how the hands share the work, and when a rest starts counting.
///
/// The label is a plain row, never a `Section` header — plain-style headers PIN, and
/// content then scrolls illegibly behind a clear background.
struct RhythmSection: View {
    @Binding var draft: RoutineDraft

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            CapsLabel(String(localized: "REST & HANDS"))

            MaterialCard {
                VStack(alignment: .leading, spacing: 12) {
                    IntValueRow(title: String(localized: "Break between sets"),
                                unit: String(localized: "s"),
                                value: $draft.plan.setBreakSeconds,
                                range: 0...240, limit: SessionPlan.setBreakRange,
                                control: .dial([0, 30, 60, 90, 120, 180]))

                    // Under the break rather than in Fine tuning: this decides when
                    // every rest in the session actually STARTS, and a rest number
                    // whose meaning is set two cards away cannot be trusted.
                    releaseToggle

                    rowDivider

                    // Hands is a genuinely categorical choice, so it stays chips — and
                    // it is ALWAYS expanded, because this control exists to be SEEN:
                    // the strip under it is the only place the app shows what
                    // "alternate each pull" actually does to a set.
                    CapsLabel(String(localized: "HANDS"))
                        .padding(.top, 2)
                    HandModeChipRow(selection: $draft.plan.handMode)
                    HandOrderStrip(mode: draft.plan.handMode, repsPerSide: firstSetReps)
                }
            }
        }
    }

    /// Whether the rest clock waits for your hand to come off the edge.
    ///
    /// ON by default, because the alternative silently shortens every rest you take: the
    /// hold completes at exactly 10 s, but standing down off a 20 mm edge takes another
    /// two or three, and those come out of the rest rather than out of the hang. Off is
    /// still a real choice — a fixed cadence you pace yourself to, which is what a
    /// metronome-style protocol wants.
    private var releaseToggle: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle("Start the rest when I let go", isOn: $draft.plan.waitForReleaseBeforeRest)
                .font(.system(.subheadline, weight: .medium))
                .foregroundStyle(Ink.primary)
                .tint(Accent.graphite)

            Text(draft.plan.waitForReleaseBeforeRest
                 ? "The hold ends on time; the rest waits until you are off the edge."
                 : "The rest starts the moment the hold ends, whether or not you have let go.")
                .font(.system(.caption, weight: .medium))
                .foregroundStyle(Ink.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var rowDivider: some View {
        Divider().overlay(Ink.tertiary.opacity(0.22))
    }

    /// The strip draws the FIRST set's sequence, because that is the one the reader is
    /// about to do; `executable` so an emptied-out row cannot decide it.
    private var firstSetReps: Int {
        draft.plan.executable.sets.first?.repsPerSide ?? 6
    }
}
