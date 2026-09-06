// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation
import UIKit

/// The app's single source of "today".
///
/// Reading `DayStamp.today()` straight from a view body only re-evaluates when SwiftUI
/// happens to invalidate that view — so an app left open across local midnight keeps
/// rendering yesterday's day: the ritual screen still says "1 of 2 today" for a session
/// trained before midnight, and the streak strip still fills yesterday's cell. The day
/// therefore gets an observable of its own, and every surface recomputes together.
///
/// `significantTimeChangeNotification` is the right hook: UIKit posts it at local
/// midnight, and also when the clock is changed manually or the device crosses into a new
/// time zone — all three change which calendar day the user is training in.
@Observable @MainActor
final class DayClock {
    private(set) var today: DayStamp
    /// The store reacts after the clock has changed, independent of notification order.
    @ObservationIgnored var onDayChanged: (@MainActor () -> Void)?

    /// Tokens live in a box that unregisters itself: Swift 6 forbids a nonisolated
    /// `deinit` from touching a non-Sendable stored property of a `@MainActor` class,
    /// and the box's own deinit has no such restriction.
    private final class ObserverTokens: @unchecked Sendable {
        var tokens: [any NSObjectProtocol] = []
        deinit {
            let center = NotificationCenter.default
            for token in tokens { center.removeObserver(token) }
        }
    }

    @ObservationIgnored private let observers = ObserverTokens()

    /// The `today:` parameter is a test seam. Crossing midnight is the single most
    /// expensive behaviour in the app to verify by waiting for it.
    init(today: DayStamp = .today()) {
        self.today = today
        let center = NotificationCenter.default
        let names: [Notification.Name] = [
            UIApplication.significantTimeChangeNotification,
            .NSSystemTimeZoneDidChange,
        ]
        observers.tokens = names.map { name in
            center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            }
        }
    }

    /// Also called on foreground: a device that was asleep across midnight may not
    /// deliver the time-change notification until the app is active again.
    func refresh() {
        let now = DayStamp.today()
        advance(to: now)
    }

    /// Tests only in practice — nothing in the app calls it. Kept out of `refresh`'s
    /// path deliberately, so no shipping code can pin the day to a value the system
    /// clock disagrees with.
    func advance(to day: DayStamp) {
        guard day != today else { return }
        today = day
        onDayChanged?()
    }
}
