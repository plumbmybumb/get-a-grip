// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// One step of the builder's inline guide.
///
/// **Never a `.popoverTip`.** A popover installs a full-screen dismiss-catcher, so the
/// first tap — even on the control the tip names — only dismisses it while the glass
/// lights up (the Schengen toolbar "+" that read as pressed-but-dead). This card draws IN
/// the layout: it intercepts nothing and can be scrolled past.
struct CoachCard: View {
    var step: Int
    var total: Int
    var title: String
    /// `message` internally because `body` is taken by `View`; the label stays `body:`.
    var message: String
    var onNext: () -> Void
    var onSkip: () -> Void

    init(step: Int, total: Int, title: String, body: String,
         onNext: @escaping () -> Void, onSkip: @escaping () -> Void) {
        self.step = step
        self.total = total
        self.title = title
        self.message = body
        self.onNext = onNext
        self.onSkip = onSkip
    }

    var body: some View {
        MaterialCard(radius: Metrics.radiusInner, surface: .flat) {
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 6) {
                    CapsLabel(String(localized: "STEP \(step) OF \(total)"))
                    Text(title)
                        .font(.system(.headline, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                    Text(message)
                        .font(.system(.footnote, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                        // Otherwise sizing to its widest sibling truncates it to one line.
                        .fixedSize(horizontal: false, vertical: true)
                }
                // One spoken sentence, not three fragmentary stops.
                .accessibilityElement(children: .combine)

                HStack(spacing: 20) {
                    coachButton(String(localized: "Next"), weight: .semibold, tint: Accent.graphite, action: onNext)
                    coachButton(String(localized: "Skip the guide"), weight: .medium, tint: Ink.tertiary, action: onSkip)
                    Spacer(minLength: 0)
                }
            }
        }
    }

    /// A bare text control must still hit like one: the ≥44pt frame and matching shape make
    /// the padding tappable.
    private func coachButton(_ label: String, weight: Font.Weight, tint: Color,
                             action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(.subheadline, weight: weight))
                .foregroundStyle(tint)
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}

/// The guide's closing note, above "Save and start training".
///
/// NOT a `CoachCard`: there is no step 6 of 5, and its "next" is the primary button
/// beneath, so a second Next would compete to end the same sentence.
struct CoachClosingCard: View {
    var body: some View {
        MaterialCard(radius: Metrics.radiusInner, surface: .flat) {
            VStack(alignment: .leading, spacing: 6) {
                Text("That's the whole routine.")
                    .font(.system(.headline, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                Text("You can change any of it later — this same screen is the editor.")
                    .font(.system(.footnote, weight: .medium))
                    .foregroundStyle(Ink.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
        }
    }
}
