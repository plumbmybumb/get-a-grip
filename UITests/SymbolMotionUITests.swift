// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// Taps through every tab, then the Today gauge button, pausing long enough for each
/// custom symbol's tap effect to finish, so the motion can be recorded frame by frame.
///
/// Symbol effects are driven by a real selection change, so no launch flag can trigger
/// them. The test only asserts that each tab was reachable; the point is the recording:
/// start `xcrun simctl io <udid> recordVideo --codec h264 motion.mov`, run
/// `./build.sh uitest SymbolMotionUITests`, stop the recording with SIGINT, then
/// `ffmpeg -i motion.mov -vf fps=30 f%04d.png`. See docs/CUSTOM_SYMBOLS.md.
final class SymbolMotionUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testEveryTabAnimatesOnArrival() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-mockDevice", "-seedTwoRoutines"]
        app.launch()

        let bar = app.tabBars.firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 15), "the tab bar is on screen")
        sleep(3)

        for tab in ["History", "Benchmarks", "Settings", "Today"] {
            let button = bar.buttons[tab]
            XCTAssertTrue(button.waitForExistence(timeout: 5), "\(tab) tab exists")
            button.tap()
            sleep(2)
        }

        let gauge = app.buttons["today.gauge"]
        XCTAssertTrue(gauge.waitForExistence(timeout: 5), "the gauge button exists")
        gauge.tap()
        sleep(2)
    }
}
