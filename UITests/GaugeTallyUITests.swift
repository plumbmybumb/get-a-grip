// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// The live gauge's stopwatch and estimated pull count, driven against the demo gauge.
final class GaugeTallyUITests: XCTestCase {
    func testTheTimerRunsAndPullsAreCountedUntilReset() {
        let app = launchApp(arguments: ["-previewGauge", "-mockDevice"])
        defer { app.terminate() }

        let timer = app.buttons["gauge.timer"]
        XCTAssertTrue(timer.waitForExistence(timeout: 10))
        let tally = element("gauge.tally", in: app)
        XCTAssertTrue(tally.label.hasSuffix("Pulls counted: 0"), tally.label)

        timer.tap()
        waitFor(timer, NSPredicate(format: "label == 'Pause timer'"), timeout: 3)
        // The demo gauge pulls on a ten-second cycle.
        waitFor(tally, NSPredicate(format: "NOT (label ENDSWITH 'Pulls counted: 0')"), timeout: 30)
        attachScreenshot(app, name: "Gauge — timer running, pulls counted")

        timer.tap()
        waitFor(timer, NSPredicate(format: "label == 'Start timer'"), timeout: 3)
        let paused = tally.label
        XCTAssertFalse(paused.hasPrefix("Timer 0 seconds"), paused)

        app.buttons["gauge.timerReset"].tap()
        waitFor(tally, NSPredicate(format: "label ENDSWITH 'Pulls counted: 0'"), timeout: 3)
        XCTAssertTrue(tally.label.hasPrefix("Timer 0 seconds"), tally.label)
        // Reset also zeroes the peak and average, and a live gauge earns a new peak at
        // once — so the button stays available while the stream reads.
        attachScreenshot(app, name: "Gauge — reset")
    }
}
