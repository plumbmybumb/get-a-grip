// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The note Frez asks every app on its Dyno API to show ONCE, the first time someone
/// picks the Dyno. A letter, so it reads as one: the words are Donghyun Kim's, verbatim,
/// and stay in English in every locale — only the title and the button are ours.
///
/// Next is the only way out. Swiping it away would leave the selection half-made, and
/// the note is the maker's one ask for lending its calibration service; after Next the
/// flag is persisted and the sheet never appears again on this device.
struct FrezIntroSheet: View {
    var onNext: () -> Void

    private let paragraphs: [String] = [
        String(localized: "Hello, I’m Donghyun Kim, founder of Frez."),
        String(localized: "We keep our hardware margins low because we believe everyone should have access to their own data. Frez Pro helps us develop new features and provide reliable devices. In fact, Frez Dyno was made possible by our early Pro subscribers."),
        String(localized: "If you enjoy using Frez Dyno and would like to support the future of Frez, please consider trying Frez Pro."),
        String(localized: "I hope Frez will be with you for many years to come."),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                MaterialCard {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(paragraphs, id: \.self) { paragraph in
                            Text(paragraph)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Thank you,")
                            Text("Donghyun Kim")
                        }
                    }
                    .font(.system(.body))
                    .foregroundStyle(Ink.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
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
