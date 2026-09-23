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
    /// The gauge screen's peak, mirrored at the same rate for the same reason:
    /// `DeviceStore.peakKg` publishes on every sample that raises it.
    private(set) var peakKg: Double = 0

    @ObservationIgnored private var task: Task<Void, Never>?

    /// Once a second instead of five times: set while the face is dimmed. Always On redraws
    /// the screen once a second, so the other four reads bought nothing but wake-ups. Read
    /// by the loop on every pass, so a change takes effect within one interval.
    @ObservationIgnored var pollsSlowly = false

    /// Read the store on a timer, OFF the view's body, so the view depends on this
    /// class's `kg` alone and never on the sample stream.
    func begin(reading device: DeviceStore) {
        task?.cancel()
        task = Task { [weak self, weak device] in
            while !Task.isCancelled {
                let interval: Duration = (self?.pollsSlowly ?? false) ? .seconds(1) : .milliseconds(200)
                try? await Task.sleep(for: interval)
                guard let self, let device else { return }
                let next = (device.currentKg * 10).rounded() / 10
                if next != self.kg { self.kg = next }
                let peak = (device.peakKg * 10).rounded() / 10
                if peak != self.peakKg { self.peakKg = peak }
            }
        }
    }

    func end() {
        task?.cancel()
        task = nil
    }
}
