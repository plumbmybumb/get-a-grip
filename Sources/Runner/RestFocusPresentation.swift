// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

/// Presentation-only policy. It reads the existing schedule without changing how
/// releases, measured work or rest countdowns are driven.
enum RestFocusPresentation {
    static let minimumScheduledSeconds = 10

    static func scheduledRestSeconds(phase: RunnerPhase, slots: [RepSlot]) -> Int? {
        guard let index = restSlotIndex(phase), slots.indices.contains(index) else { return nil }
        return slots[index].restAfter
    }

    static func restSlotIndex(_ phase: RunnerPhase) -> Int? {
        switch phase {
        case .resting(let index): index
        case .paused(let inner): restSlotIndex(inner)
        default: nil
        }
    }
}

extension RunnerSnapshot {
    /// A long rest keeps one layout all the way to its end. Choosing from the
    /// remaining seconds would reshuffle the screen just as the next pull approaches.
    var showsRestFocus: Bool {
        guard RestFocusPresentation.restSlotIndex(phase) != nil,
              let scheduledRestSeconds else { return false }
        return scheduledRestSeconds >= RestFocusPresentation.minimumScheduledSeconds
    }
}
