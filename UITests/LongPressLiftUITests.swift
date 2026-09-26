// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// Holds a finger on Today's routine card so the context-menu lift can be recorded.
///
/// The lift is a UIKit animation driven by a real touch, so `simctl` cannot trigger it
/// and no screenshot flag can freeze it. The test itself only checks that the hold opens
/// the menu; the point of it is what happens around it. To see the lift frame by frame:
/// build for testing under the `DoigtUITests` scheme (`./build.sh uitest`), start
/// `xcrun simctl io <udid> recordVideo --codec h264 lift.mov`, run
/// `-only-testing:DoigtUITests/LongPressLiftUITests` with `test-without-building`,
/// stop the recording with SIGINT, then `ffmpeg -i lift.mov -vf fps=30 f%04d.png`.
/// That is how the deck's clipped lift was caught and confirmed fixed (2026-09-19).
final class LongPressLiftUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad,
                      "the phone deck is the surface under test")
    }

    func testLongPressLiftsTheRoutineCard() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-mockDevice", "-seedTwoRoutines", "-dumpInteractions"]
        app.launch()

        let start = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'start'")).firstMatch
        if !start.waitForExistence(timeout: 15) {
            print("ACCESSIBILITY TREE\n" + app.debugDescription)
            XCTFail("no start button on Today")
        }
        sleep(4)
        // The card's title row, from the pinned simulator's geometry: the card sits at
        // (20, 243) 354x329 and its title row is ~38 pt below the card's top edge.
        let point = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 120, dy: 281))
        point.press(forDuration: 2.5)
        XCTAssertTrue(app.buttons["Edit routine"].waitForExistence(timeout: 3),
                      "a long press on the card opens its menu")
        sleep(1)
        // Dismiss the menu by tapping the screen's top-left, off the card.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        sleep(1)
    }
}
