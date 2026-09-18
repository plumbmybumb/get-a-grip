// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// When the app asks for an App Store rating: ONCE per device, after the fifth saved
/// session — someone who has come back five times knows what the app is.
///
/// Apple's rules, applied (Human Interface Guidelines › Ratings and reviews; StoreKit ›
/// `RequestReviewAction`; App Store Review Guidelines 1.1.7 and 3.2.2):
/// - **Only the system prompt.** Custom rating UI is disallowed, and the prompt's words
///   are Apple's. The one place the ask can say WHY — free, open source — is the
///   permanent "Rate on the App Store" link in Settings, which Apple allows on a
///   settings screen.
/// - **After demonstrated engagement, at a natural stopping point.** Never on first
///   launch or during onboarding, never mid-task, and never in response to a tap,
///   because the API may show nothing. So: Today, once the fifth session has been
///   saved and the runner has closed — `TodayView.askForReviewIfDue`, a beat after
///   the cover is gone, reading the store's save counter to know a session was saved.
/// - **Never repeated by this app, never traded for anything.** The system already caps
///   prompts at three per 365 days per device and honours the global opt-out; this
///   app asks once (`SettingsStore.reviewRequested`) and gates nothing on it.
enum ReviewRequestPolicy {
    /// The fifth session, not the first: enough visits to have an opinion, few enough
    /// that a daily ritual reaches it in the first week.
    static let sessionsBeforeAsking = 5

    static func shouldAsk(hangSessionsLogged: Int, alreadyAsked: Bool) -> Bool {
        !alreadyAsked && hangSessionsLogged >= sessionsBeforeAsking
    }
}
