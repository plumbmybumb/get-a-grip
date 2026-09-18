// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class ReviewRequestPolicyTests: XCTestCase {
    func testAsksFromTheFifthSavedSessionOnwardsAndNeverTwice() {
        XCTAssertEqual(ReviewRequestPolicy.sessionsBeforeAsking, 5)
        XCTAssertFalse(ReviewRequestPolicy.shouldAsk(hangSessionsLogged: 0, alreadyAsked: false))
        XCTAssertFalse(ReviewRequestPolicy.shouldAsk(hangSessionsLogged: 4, alreadyAsked: false))
        XCTAssertTrue(ReviewRequestPolicy.shouldAsk(hangSessionsLogged: 5, alreadyAsked: false))
        // Somebody updating with a long history is asked on their next session, once.
        XCTAssertTrue(ReviewRequestPolicy.shouldAsk(hangSessionsLogged: 40, alreadyAsked: false))
        XCTAssertFalse(ReviewRequestPolicy.shouldAsk(hangSessionsLogged: 5, alreadyAsked: true))
        XCTAssertFalse(ReviewRequestPolicy.shouldAsk(hangSessionsLogged: 400, alreadyAsked: true))
    }

    @MainActor
    func testTheOnceFlagPersistsAcrossLaunches() {
        let suite = "ReviewRequestPolicyTests." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = SettingsStore(defaults: defaults)
        XCTAssertFalse(first.reviewRequested, "a fresh install has not asked")
        first.reviewRequested = true
        XCTAssertTrue(SettingsStore(defaults: defaults).reviewRequested, "the once-only flag survives a relaunch")
    }
}
