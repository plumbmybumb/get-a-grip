// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Rest changes the information above the trace, never the trace itself. The
/// coarse snapshot already points to the next hand, grip and target during rest.
///
/// **The countdown is not here.** It is the ambient numeral in the open graph
/// (`RunnerView.ambientCountdown`), readable from the wall; carrying it a second
/// time in the panel said the same number twice a hand's width apart (Nuri,
/// 2026-09-19). The room it took goes to the grip you are about to pull.
struct RunnerRestFocusSummary: View {
    let snapshot: RunnerSnapshot
    let showsGlyph: Bool
    /// The wide runner draws the numeral larger, like the rest of its identity block.
    var scale: CGFloat = 1
    /// False when the panel's own counters row below the summary already carries set
    /// and pull.
    var showsCounts = true
    /// The routine pills, drawn above the two counts when given (accessibility sizes,
    /// where the summary replaces the whole panel).
    var progressRow: AnyView? = nil
    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 10 * scale) {
            if showsGlyph, let grip = snapshot.grip {
                RunnerGripGlyph(grip: grip, emphasized: snapshot.gripChangesNext)
                    .scaleEffect(snapshot.gripChangesNext && !reduceMotion ? 1 : 1.2)
                    .padding(.vertical, 3)
                    .accessibilityHidden(true)
            }
            VStack(spacing: 6) {
                Text(nextHand)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Ink.secondary)
                    .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
                    .minimumScaleFactor(typeSize.isAccessibilitySize ? 1 : 0.75)
                    .accessibilityIdentifier("runner.restFocus.hand")
                if let grip = snapshot.grip {
                    Text(grip.line)
                        .font(.title3.weight(.semibold))
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
                        .font(.body)
                        .foregroundStyle(Ink.secondary)
                        .monospacedDigit()
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("runner.restFocus.target")
                }
            }
            VStack(spacing: 8) {
                // One headline per surface: the hand word is the instruction, and the
                // giant countdown and the wash already say "rest" at screen scale, so the
                // phase word steps down to a badge in the instrument voice. PAUSED keeps
                // its amber as a FILL with dark ink — amber text measures 1.7:1 on the
                // light field and cannot carry a word this small.
                CapsLabel(phaseLabel, size: 13,
                          tint: snapshot.phase.isPaused ? Color(hex: "1B1F25") : Ink.secondary)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(snapshot.phase.isPaused ? StatusTint.armed
                                                                       : Ink.tertiary.opacity(0.16)))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("runner.restFocus.phase")
                if let progressRow {
                    progressRow
                }
                if showsCounts {
                    HStack(alignment: .top, spacing: 16) {
                        setCount
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

    private var setCount: some View {
        count(label: String(localized: "Set"), current: snapshot.setNumber ?? 1,
              total: snapshot.setCount,
              spoken: String(localized: "Set \(snapshot.setNumber ?? 1) of \(snapshot.setCount)"),
              identifier: "runner.restFocus.setCount")
    }

    private var pullCount: some View {
        let position = snapshot.pullPosition
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
