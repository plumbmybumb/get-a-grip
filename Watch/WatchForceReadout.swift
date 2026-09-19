// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation

/// The live reading, at a rate a wrist can draw.
///
/// `DeviceStore.currentKg` moves with every sample — eighty times a second on a
/// Progressor — and a `Text` that observed it re-rendered that often, which on the watch
/// was felt as the whole face lagging (Nuri, 2026-09-19). Five updates a second is more
/// than a glance can read, and rounding to a tenth means a steady load publishes
/// nothing at all. The engine keeps every sample; this is only what the screen shows.
@Observable @MainActor
final class WatchForceReadout {
    private(set) var kg: Double = 0

    @ObservationIgnored private var task: Task<Void, Never>?

    /// Read the store on a timer, OFF the view's body, so the view depends on this
    /// class's `kg` alone and never on the sample stream.
    func begin(reading device: DeviceStore) {
        task?.cancel()
        task = Task { [weak self, weak device] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(200))
                guard let self, let device else { return }
                let next = (device.currentKg * 10).rounded() / 10
                if next != self.kg { self.kg = next }
            }
        }
    }

    func end() {
        task?.cancel()
        task = nil
    }
}
