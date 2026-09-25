// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Holds the screen awake while this view is on screen, through the same reference-counted
/// `IdleTimerLock` the runner uses, so a gauge screen opened on top of anything else can
/// never leave the whole phone unable to sleep. The phone sits on a bench while you pull:
/// a screen that dims mid-measurement is a screen you have to let go of the edge to wake.
private struct KeepScreenAwake: ViewModifier {
    @State private var holding = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                guard !holding else { return }
                holding = true
                IdleTimerLock.acquire()
            }
            .onDisappear {
                guard holding else { return }
                holding = false
                IdleTimerLock.release()
            }
    }
}

extension View {
    func keepsScreenAwake() -> some View { modifier(KeepScreenAwake()) }
}
