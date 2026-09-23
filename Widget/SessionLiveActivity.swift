// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import ActivityKit
import SwiftUI
import WidgetKit

/// The session, on the lock screen and in the Dynamic Island.
///
/// **What it is for:** you swipe home mid-session and the session keeps running; the
/// island tells you whether to be pulling from anywhere.
///
/// **The hand is the point.** Compact leading draws the in-app island mark, mirrored with
/// the pulling hand, so the glance says which fingers and which side before a word.
///
/// **Every countdown is a `Text(timerInterval:)`**, ticked by the widget itself. The app
/// pushes only on real state changes, which keeps a long session inside ActivityKit's
/// budget.
///
/// **The whole card is colour-coded by phase**, on the runner's ladder
/// (`SessionActivity.Phase.tint`). It changes on the push — the only "pulse" an archived
/// render can have.
struct SessionLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SessionActivity.self) { context in
            Group {
                if context.isStale {
                    staleLockScreen(context)
                } else {
                    lockScreen(context)
                }
            }
            .activityBackgroundTint(cardTint(context))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HandMark(fingers: context.state.grip.fingers,
                             position: context.state.grip.position,
                             side: context.state.side,
                             barWidth: 11,
                             tint: tint(context))
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    countdown(context, font: .system(.title2, design: .default).weight(.medium))
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.isStale ? Self.staleWord.uppercased()
                                         : context.state.phase.word.uppercased())
                        .font(.system(.caption, weight: .semibold))
                        .foregroundStyle(tint(context))
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 8) {
                        Text(context.isStale ? context.attributes.routineName
                                             : context.state.grip.shortName)
                            .font(.system(.footnote, weight: .medium))
                            .lineLimit(1)
                        Spacer(minLength: 6)
                        if !context.isStale {
                            Text("Pull \(context.state.repPosition) of \(context.attributes.plannedReps)")
                                .font(.system(.footnote))
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } compactLeading: {
                // **The hand carries the colour in the compact island**, where there is
                // no room for a word and no background to tint.
                HandMark(fingers: context.state.grip.fingers,
                         position: context.state.grip.position,
                         side: context.state.side,
                         barWidth: 4.5,
                         tint: tint(context))
            } compactTrailing: {
                countdown(context, font: .system(.caption, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(tint(context))
            } minimal: {
                // One glyph only — the minimal presentation is a circle barely wider
                // than a glyph, so four bars is all that can survive in it.
                HandMark(fingers: context.state.grip.fingers,
                         position: context.state.grip.position,
                         side: context.state.side,
                         barWidth: 3.5,
                         tint: tint(context))
            }
            .keylineTint(tint(context))
        }
    }

    // MARK: - Stale

    /// **A card past its stale date is a card whose app stopped pushing** — nearly
    /// always because the process died mid-session (see `ContentState.staleDate`). Its
    /// phase, colour and countdown are claims nobody stands behind, so none are drawn.
    /// Tapping it opens the app, which clears orphaned cards at launch.
    private static var staleWord: String { String(localized: "Open Get a Grip") }

    private func tint(_ context: ActivityViewContext<SessionActivity>) -> Color {
        context.isStale ? .gray : context.state.phase.tint
    }

    private func cardTint(_ context: ActivityViewContext<SessionActivity>) -> Color {
        context.isStale ? SessionActivity.Phase.resting.cardTint : context.state.phase.cardTint
    }

    private func staleLockScreen(_ context: ActivityViewContext<SessionActivity>) -> some View {
        HStack(spacing: 14) {
            HandMark(fingers: context.state.grip.fingers,
                     position: context.state.grip.position,
                     side: context.state.side,
                     barWidth: 13,
                     tint: .gray)
                .opacity(0.5)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(Self.staleWord)
                    .font(.system(.headline, weight: .semibold))
                    .foregroundStyle(.white)
                Text(context.attributes.routineName)
                    .font(.system(.footnote))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .layoutPriority(1)
            Spacer(minLength: 8)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
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
            // The text column gets the slack, or the clock's reserved width truncates the
            // grip and wraps the counters.
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

    /// Where you are in the session. **"Set 1 of 1" is dropped** — a constant dressed as
    /// a counter, costing the pull count its room.
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
        // `endsAt > .now` is a CRASH GUARD: `Date.now...endsAt` traps on a past deadline,
        // and a trapped widget renders as a blank placeholder with no clue why.
        if context.isStale {
            // Nothing is counting any more — the app that owned this clock is gone.
            Text("—").font(font).foregroundStyle(.secondary)
        } else if let endsAt = context.state.endsAt, endsAt > .now, context.state.phase.runsCountdown {
            Text(timerInterval: Date.now...endsAt, countsDown: true)
                .font(font)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
        } else if let pending = context.state.pendingSeconds {
            // ARMED: the hold ahead. A STOPPED hold: the seconds still owed. No clock is
            // running either way, so it is dimmed rather than read as a countdown.
            Text("\(pending)s")
                .font(font)
                .monospacedDigit()
                .foregroundStyle(.secondary)
        } else {
            Text("—").font(font).foregroundStyle(.secondary)
        }
    }
}
