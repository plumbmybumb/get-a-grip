// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The note Frez asks every app on its Dyno API to show ONCE, the first time someone
/// picks the Dyno. A letter, so it reads as one: the words are Donghyun Kim's, verbatim,
/// and stay in English in every locale — only the title, the footnote and the button
/// are ours.
///
/// It is set the way its author laid it out (Donghyun Kim, 2026-09-18, asking for "a few
/// line breaks to improve readability"): every line he wrote is its own paragraph with a
/// visible gap after it — a break that only shows when the line before happens to wrap is
/// no break at all — and the salutation carries the card as a heading with the signature
/// closing it, so a glance sees a letter, not a wall of terms.
///
/// Next is the only way out. Swiping it away would leave the selection half-made, and
/// the note is the maker's one ask for lending its calibration service; after Next the
/// flag is persisted and the sheet never appears again on this device.
struct FrezIntroSheet: View {
    var onNext: () -> Void

    private let greeting = String(localized: "Hello,")
    private let introduction = String(localized: "I’m Donghyun Kim, founder of Frez.")

    /// The body, one paragraph per line he wrote.
    private let paragraphs: [String] = [
        String(localized: "We keep our hardware margins low because we believe everyone should have access to their own data."),
        String(localized: "Frez Pro helps us develop new features and provide reliable devices."),
        String(localized: "In fact, Frez Dyno was made possible by our early Pro subscribers."),
        String(localized: "If you enjoy using Frez Dyno and would like to support the future of Frez, please consider trying Frez Pro."),
        String(localized: "I hope Frez will be with you for many years to come."),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    MaterialCard(verticalPadding: 22) {
                        VStack(alignment: .leading, spacing: 18) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(greeting)
                                    .font(.title2.weight(.semibold))
                                Text(introduction)
                            }
                            ForEach(paragraphs, id: \.self) { paragraph in
                                Text(paragraph)
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Thank you,")
                                Text("Donghyun Kim")
                                    .fontWeight(.semibold)
                            }
                            .padding(.top, 4)
                        }
                        .font(.body)
                        .lineSpacing(3)
                        .foregroundStyle(Ink.primary)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Text("Shown once, the first time you pick the Dyno.")
                        .font(.footnote)
                        .foregroundStyle(Ink.tertiary)
                        .padding(.horizontal, 4)
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 4)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .background { AppBackground() }
            .safeAreaInset(edge: .bottom) {
                PrimaryGlassButton(title: String(localized: "Next"), action: onNext)
                    .padding(.horizontal, Metrics.hPadding)
                    .padding(.bottom, 4)
                    .frame(maxWidth: Metrics.maxContentWidth)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("frez.intro.next")
            }
            .navigationTitle("A note from Frez")
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled(true)
        .presentationDetents([.large])
    }
}

/// `.sheet(item:)` wants an identity; the raw value is the only one a kind needs.
extension GaugeKind: Identifiable {
    var id: String { rawValue }
}
