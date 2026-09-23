// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation
#if !os(watchOS)
import UIKit
#endif

/// The app's single source of "today".
///
/// Reading `DayStamp.today()` from a view body only re-evaluates when SwiftUI happens to
/// invalidate that view, so an app left open across midnight kept rendering yesterday.
/// The day gets an observable of its own, and every surface recomputes together.
///
/// `significantTimeChangeNotification` fires at local midnight, on a manual clock change
/// and on a time-zone change. The TRAINING day turns at `DayStamp.rolloverHour`, which
/// nothing announces, so the clock books its own wake-up (`armRolloverRefresh`).
@Observable @MainActor
final class DayClock {
    private(set) var today: DayStamp
    /// The store reacts after the clock has changed, independent of notification order.
    @ObservationIgnored var onDayChanged: (@MainActor () -> Void)?
    /// The pending wake-up for the next rollover. Holds `self` weakly, so a clock that is
    /// gone simply lets it lapse; re-armed by every refresh.
    @ObservationIgnored private var rolloverRefresh: Task<Void, Never>?

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

    /// The `today:` parameter is a test seam: crossing midnight is too expensive to
    /// verify by waiting.
    init(today: DayStamp = .today()) {
        self.today = today
        let center = NotificationCenter.default
        #if os(watchOS)
        // No application object on the wrist. Foundation's day-change post is the same
        // midnight; the manual-clock-change case UIKit also folds in is covered by the
        // foreground refresh.
        let names: [Notification.Name] = [.NSCalendarDayChanged, .NSSystemTimeZoneDidChange]
        #else
        let names: [Notification.Name] = [
            UIApplication.significantTimeChangeNotification,
            .NSSystemTimeZoneDidChange,
        ]
        #endif
        observers.tokens = names.map { name in
            center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            }
        }
        armRolloverRefresh()
    }

    /// Also called on foreground: a device that was asleep across midnight — or across
    /// the 04:00 rollover — may not deliver the time-change notification, and cannot run
    /// the rollover task, until the app is active again.
    func refresh() {
        let now = DayStamp.today()
        advance(to: now)
        armRolloverRefresh()
    }

    /// Sleeps until the next `DayStamp.rolloverHour` and refreshes itself; a process
    /// suspended across it is caught by the foreground refresh instead. Continuous
    /// clock, so a phone that dozed with the app foregrounded still wakes on time.
    private func armRolloverRefresh() {
        rolloverRefresh?.cancel()
        let interval = DayStamp.nextRollover(after: .now).timeIntervalSinceNow
        rolloverRefresh = Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(1, interval)), clock: .continuous)
            guard !Task.isCancelled else { return }
            self?.refresh()
        }
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
