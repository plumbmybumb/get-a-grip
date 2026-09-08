// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import ActivityKit
import SwiftUI
import WidgetKit

/// The session, on the lock screen and in the Dynamic Island.
///
/// **What it is for:** you swipe home mid-session — to change the music, to answer
/// something — and the session keeps running. Without this it either pauses (which is
/// what the app used to do) or it runs invisibly and you have to reopen it to know
/// whether to be pulling. The island answers that from anywhere.
///
/// **The hand is the point.** Compact leading draws the same mark that hangs off the
/// island in-app, so the glance tells you which fingers before you read a word — and it
/// mirrors with the hand you are pulling with, so LEFT and RIGHT are legible without one.
///
/// **Every countdown is a `Text(timerInterval:)`.** The widget ticks it down itself, once
/// a second, with no updates from the app at all. The app only pushes on real state
/// changes — a rep ending, a hand swapping — which is what keeps a twenty-minute session
/// inside ActivityKit's budget.
///
/// **The whole card is colour-coded by phase** — steel while resting, amber the moment
/// it is on you, bleu while the clock runs — on the same ladder as the runner screen.
/// See `SessionActivity.Phase.tint`: it changes on the push, which is the only "pulse" an
/// archived render can have, and the only one worth having.
struct SessionLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SessionActivity.self) { context in
            lockScreen(context)
                .activityBackgroundTint(context.state.phase.cardTint)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HandMark(fingers: context.state.grip.fingers,
                             position: context.state.grip.position,
                             side: context.state.side,
                             barWidth: 11,
                             tint: context.state.phase.tint)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    countdown(context, font: .system(.title2, design: .default).weight(.medium))
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.phase.word.uppercased())
                        .font(.system(.caption, weight: .semibold))
                        .foregroundStyle(context.state.phase.tint)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 8) {
                        Text(context.state.grip.shortName)
                            .font(.system(.footnote, weight: .medium))
                            .lineLimit(1)
                        Spacer(minLength: 6)
                        Text("Pull \(context.state.repPosition) of \(context.attributes.plannedReps)")
                            .font(.system(.footnote))
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                    }
                }
            } compactLeading: {
                // **The hand carries the colour in the compact island**, where there is
                // no room for a word and no background to tint: four bars going amber and
                // then blue is the entire state of the session, read without looking.
                HandMark(fingers: context.state.grip.fingers,
                         position: context.state.grip.position,
                         side: context.state.side,
                         barWidth: 4.5,
                         tint: context.state.phase.tint)
            } compactTrailing: {
                countdown(context, font: .system(.caption, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(context.state.phase.tint)
            } minimal: {
                // One glyph only — the minimal presentation is a circle barely wider
                // than a glyph, so four bars is all that can survive in it.
                HandMark(fingers: context.state.grip.fingers,
                         position: context.state.grip.position,
                         side: context.state.side,
                         barWidth: 3.5,
                         tint: context.state.phase.tint)
            }
            .keylineTint(context.state.phase.tint)
        }
    }

    // MARK: - Lock screen

    private func lockScreen(_ context: ActivityViewContext<SessionActivity>) -> some View {
        HStack(spacing: 14) {
            HandMark(fingers: context.state.grip.fingers,
                     position: context.state.grip.position,
                     side: context.state.side,
                     barWidth: 13,
                     tint: context.state.phase.tint)
                // Still dimmed while the phase is passive — the colour says WHICH state,
                // the weight says whether it is on you now.
                .opacity(context.state.phase.isActive ? 1 : 0.7)

            VStack(alignment: .leading, spacing: 3) {
                Text(context.state.phase.word)
                    .font(.system(.headline, weight: .semibold))
                    .foregroundStyle(context.state.phase.tint)
                Text(context.state.grip.line)
                    .font(.system(.footnote))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(positionLine(context))
                    .font(.system(.caption))
                    .monospacedDigit()
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
            // The text column gets the slack. Without this the clock's reserved width
            // squeezed it until the grip read "20 mm · 4 finger…" and the counters wrapped
            // onto two lines — on the one surface whose whole job is a one-second glance.
            .layoutPriority(1)

            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 2) {
                countdown(context, font: .system(.title2, weight: .medium))
                // The TARGET, which is constant for the whole rep — the only load
                // figure a surface that updates on state changes can state honestly.
                if let band = context.state.targetBand {
                    let unit = context.state.displayWeightUnit ?? .kg
                    Text(unit.bandText(band))
                        .accessibilityLabel("\(unit.number(band.lowerBound))–\(unit.number(band.upperBound)) \(unit.spokenName)")
                        .font(.system(.caption))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    /// Where you are in the session. **"Set 1 of 1" is dropped** when there is only one:
    /// it is a constant dressed up as a counter, and it was costing the pull count the
    /// room it needed to stay on one line.
    private func positionLine(_ context: ActivityViewContext<SessionActivity>) -> String {
        let pulls = String(localized: "Pull \(context.state.repPosition) of \(context.attributes.plannedReps)")
        guard context.attributes.setCount > 1 else { return pulls }
        return String(localized: "Set \(context.state.setNumber) of \(context.attributes.setCount) · \(pulls)")
    }

    /// The self-ticking clock — or, while armed, the hold that has not started yet.
    /// Falls back to a dash when there is genuinely nothing to say.
    @ViewBuilder
    private func countdown(_ context: ActivityViewContext<SessionActivity>,
                           font: Font) -> some View {
        // `endsAt > .now` is a CRASH GUARD, not tidiness: `Date.now...endsAt` traps when
        // the deadline has already passed, and a trapped widget process renders as a
        // blank placeholder with no clue why. A deadline in the past means the app has
        // not pushed in a while — show the dash and let the card say the rest.
        if let endsAt = context.state.endsAt, endsAt > .now, context.state.phase.runsCountdown {
            Text(timerInterval: Date.now...endsAt, countsDown: true)
                .font(font)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
        } else if context.state.phase == .armed, let pending = context.state.pendingSeconds {
            // ARMED: the length of the hold ahead, not a clock. Dimmed, so a number
            // sitting still cannot be read as a countdown that has stopped.
            Text("\(pending)s")
                .font(font)
                .monospacedDigit()
                .foregroundStyle(.secondary)
        } else {
            Text("—").font(font).foregroundStyle(.secondary)
        }
    }
}
