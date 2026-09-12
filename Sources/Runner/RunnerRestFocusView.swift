// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Rest changes the information above the trace, never the trace itself. The
/// coarse snapshot already points to the next hand, grip and target during rest.
struct RunnerRestFocusSummary: View {
    let snapshot: RunnerSnapshot
    let showsGlyph: Bool
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .largeTitle) private var numeralSize: CGFloat = 80

    var body: some View {
        VStack(spacing: 6) {
            if showsGlyph, let grip = snapshot.grip {
                RunnerGripGlyph(grip: grip, emphasized: snapshot.gripChangesNext)
                    .scaleEffect(snapshot.gripChangesNext && !reduceMotion ? 1 : 1.2)
                    .padding(.vertical, 3)
                    .accessibilityHidden(true)
            }
            VStack(spacing: 4) {
                Text(nextHand)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Ink.secondary)
                    .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
                    .minimumScaleFactor(typeSize.isAccessibilitySize ? 1 : 0.75)
                    .accessibilityIdentifier("runner.restFocus.hand")
                if let grip = snapshot.grip {
                    Text(grip.line)
                        .font(.headline)
                        // Use the existing readable orange text tone; the brighter
                        // signal orange remains on the larger glyph and graph outline.
                        .foregroundStyle(snapshot.gripChangesNext ? GlassTint.armed.text : Ink.secondary)
                        .lineLimit(typeSize.isAccessibilitySize ? nil : 2)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityLabel(snapshot.gripChangesNext
                            ? String(localized: "New grip next: \(grip.spoken)") : grip.spoken)
                        .accessibilityIdentifier("runner.restFocus.grip")
                }
                if let band = snapshot.targetBand {
                    let lower = weightUnit.number(band.lowerBound)
                    let upper = weightUnit.number(band.upperBound)
                    Text(String(localized: "Next target: \(lower)–\(upper) \(weightUnit.symbol)"))
                        .font(.subheadline)
                        .foregroundStyle(Ink.secondary)
                        .monospacedDigit()
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("runner.restFocus.target")
                }
            }
            // The current instruction owns the countdown. Give it the whole row,
            // so a translated set-break label never competes for the timer's width.
            VStack(spacing: 0) {
                Text(phaseLabel)
                    .font(.title.weight(.semibold))
                    .foregroundStyle(snapshot.phase.isPaused ? StatusTint.armed : Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("runner.restFocus.phase")
                if typeSize.isAccessibilitySize {
                    countdown
                    HStack(alignment: .top, spacing: 16) {
                        setCount
                        pullCount
                    }
                } else {
                    HStack(alignment: .center, spacing: 8) {
                        setCount
                        countdown.layoutPriority(1)
                        pullCount
                    }
                }
            }
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("runner.restFocus")
        .allowsHitTesting(false)
    }

    private var countdown: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text("\(snapshot.secondsShown)")
                .font(.system(size: numeralSize, weight: .thin))
                .displayTracking(numeralSize)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .contentTransition(reduceMotion ? .identity : .numericText(countsDown: true))
                .animation(reduceMotion ? nil : Motion.live, value: snapshot.secondsShown)
                .accessibilityIdentifier("runner.restFocus.countdown")
            Text("s")
                .font(.title3)
                .foregroundStyle(Ink.tertiary)
        }
        .foregroundStyle(Ink.primary)
    }

    private var setCount: some View {
        count(label: String(localized: "Set"), current: snapshot.setNumber ?? 1,
              total: snapshot.setCount,
              spoken: String(localized: "Set \(snapshot.setNumber ?? 1) of \(snapshot.setCount)"),
              identifier: "runner.restFocus.setCount")
    }

    private var pullCount: some View {
        let position = min(snapshot.completedRepCount + 1, snapshot.plannedRepCount)
        return count(label: String(localized: "Pull"), current: position,
                     total: snapshot.plannedRepCount,
                     spoken: String(localized: "Pull \(position) of \(snapshot.plannedRepCount)"),
                     identifier: "runner.restFocus.pullCount")
    }

    private func count(label: String, current: Int, total: Int, spoken: String,
                       identifier: String) -> some View {
        VStack(spacing: 4) {
            Text(label)
                .font(.footnote.weight(.medium))
                .foregroundStyle(Ink.secondary)
            Text("\(current)/\(total)")
                .font(.title2.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(Ink.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spoken)
        .accessibilityIdentifier(identifier)
    }

    private var nextHand: String {
        switch snapshot.nextRestHand {
        case .left: String(localized: "Left hand next")
        case .right: String(localized: "Right hand next")
        case .both: String(localized: "Both hands next")
        case nil: String(localized: "Next grip")
        }
    }

    private var phaseLabel: String {
        if snapshot.phase.isPaused { return String(localized: "PAUSED") }
        return snapshot.isSetBreak ? String(localized: "SET BREAK") : String(localized: "REST")
    }
}
