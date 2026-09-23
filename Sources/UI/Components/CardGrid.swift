// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Cards in as many columns as fit at the phone's width — two on an iPad in landscape,
/// one in a Slide Over column — top-aligned, on the house gap.
///
/// The regular-width answer to a phone column floating in a tablet (Nuri, 2026-09-19).
/// Cards were measured at the phone's 440, so that is the floor; the ceiling keeps a lone
/// card from becoming a banner. Regular width only.
struct CardGrid<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: Metrics.cardMinWidth, maximum: 640),
                                     spacing: Metrics.spacing, alignment: .top)],
                  alignment: .center, spacing: Metrics.spacing) {
            content
        }
    }
}
