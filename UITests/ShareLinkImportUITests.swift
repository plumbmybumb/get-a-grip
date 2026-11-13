// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// A stranger's routine code, taken all the way to the Today card.
///
/// The unit tests pin the codec; this pins the SCREEN, because that is where the failure
/// was. A code whose kilogram target was `1e30` imported cleanly — the preview folds
/// against no maxes — and only once it had been added, on a phone with a max on file for
/// that grip, did the card that speaks "peak target N percent of max" try to turn 2e30
/// into an `Int`. Every routine's card is built on every Today render, so the app then
/// died at launch, on each device the routine had synced to.
final class ShareLinkImportUITests: XCTestCase {

    /// `{"v":1,"plan":{"name":"Max day","sets":[{"grip":{"edgeMM":20,"fingers":"IMRL",
    /// "position":"halfCrimp"},"repsPerSide":3,"targetLoKg":1e30,"targetHiKg":1e30}]},
    /// "sessionsPerDay":1,"isOnDemand":false}`, raw-deflated and base64url'd.
    private let absurdTarget = "getagrip://routine#NY69CsIwFIVfRc6codotqx0UWxQdxSGQ23ihTUNuEKXk3U2Grufn"
        + "O2fFB3qvECbjoVd4MxM0BvPdWfODglAS6OcKFznUBFlHwwB9aBRG9o5i8XEe7n1Jh0U48VJQeJtpPEaeA7JCpCA3ig"
        + "-2hd4qJBMdpX65uLJObbMpJ96U_Mp1XKTQarUrb-pRlqvvaDbeQo9mEsp_"

    func testARoutineWithAnAbsurdTargetIsAddedAndTodayStillDraws() {
        // `-seedHistory` records a both-hands max for 20 mm four-finger half crimp, the
        // grip this code targets: the one condition the crash needed.
        let app = launchApp(arguments: ["-mockDevice", "-seedRoutine", "-seedHistory",
                                        "-openShareLink", absurdTarget])
        defer { app.terminate() }

        let add = app.buttons["Add to my routines"]
        XCTAssertTrue(add.waitForExistence(timeout: 20), "the code opens its preview")
        add.tap()

        let card = app.staticTexts["Max day"].firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 10), "the routine lands on Today")
        // Long enough for several renders of the deck with the new card in it.
        sleep(2)
        XCTAssertEqual(app.state, .runningForeground, "and Today survives drawing it")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'start'"))
            .firstMatch.exists, "with its Start button on screen")
    }
}
