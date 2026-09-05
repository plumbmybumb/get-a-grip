// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import WidgetKit

/// The widget extension's entry point. Only the Live Activity for now — there is no
/// home-screen widget, and an empty one would be a tile that says nothing.
@main
struct DoigtWidgetBundle: WidgetBundle {
    var body: some Widget {
        SessionLiveActivity()
    }
}
