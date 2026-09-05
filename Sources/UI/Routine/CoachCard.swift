// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// One step of the builder's inline guide.
///
/// **Never a `.popoverTip`.** A popover installs a full-screen dismiss-catcher, so the
/// first tap anywhere — including on the control the tip is telling you to use — only
/// dismisses the tip and is swallowed, while the glass still lights up under the finger.
/// That is the documented Schengen bug where the toolbar "+" read as pressed-but-dead on
/// the very first action a new user takes. This card draws IN the layout instead: it
/// intercepts nothing, it can be scrolled past, and it costs one card of height.
struct CoachCard: View {
    var step: Int
    var total: Int
    var title: String
    /// Named `message` internally because `body` is already taken by `View`; the
    /// external label stays `body:` so call sites read as prose.
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
        MaterialCard(radius: Metrics.radiusInner) {
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 6) {
                    CapsLabel(String(localized: "STEP \(step) OF \(total)"))
                    Text(title)
                        .font(.system(.headline, weight: .semibold))
                        .foregroundStyle(Ink.primary)
                    Text(message)
                        .font(.system(.footnote, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                        // Multi-line body copy inside a card that also sizes to its
                        // widest sibling truncates to one line without this.
                        .fixedSize(horizontal: false, vertical: true)
                }
                // One spoken sentence: "Step 2 of 5" and the title alone are fragments,
                // and three separate stops is three swipes to read one card.
                .accessibilityElement(children: .combine)

                HStack(spacing: 20) {
                    coachButton(String(localized: "Next"), weight: .semibold, tint: Accent.graphite, action: onNext)
                    coachButton(String(localized: "Skip the guide"), weight: .medium, tint: Ink.tertiary, action: onSkip)
                    Spacer(minLength: 0)
                }
            }
        }
    }

    /// A bare text control still has to hit like a control: the ≥44pt frame and the
    /// matching content shape are what make the padding tappable, since SwiftUI's
    /// default hit area is only the glyphs themselves.
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

/// The guide's closing note, drawn above "Save and start training".
///
/// Deliberately NOT a `CoachCard`: there is no step 6 of 5, and its "next" is the big
/// primary button directly beneath it, so a second Next here would be two buttons
/// competing to be the end of the same sentence.
struct CoachClosingCard: View {
    var body: some View {
        MaterialCard(radius: Metrics.radiusInner) {
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
