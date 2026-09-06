// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest

/// Run on a fresh simulator: no production data or acceptance records are reset.
@MainActor
final class LegalAgreementUITests: XCTestCase {
    func testDeclineLeavesHistoryAccessibleAndExplicitAcceptanceStartsSession() {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice"]
        app.launch()
        let start = app.buttons["Connect and start"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        start.tap()
        let agree = app.switches["legal.agree"]
        XCTAssertTrue(agree.waitForExistence(timeout: 5))
        let next = app.buttons["legal.continue"]
        XCTAssertFalse(next.isEnabled)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "iOS agreement English"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.buttons["Not now"].tap()
        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.navigationBars["History"].waitForExistence(timeout: 3))
        app.tabBars.buttons["Today"].tap()
        start.tap()
        XCTAssertTrue(agree.waitForExistence(timeout: 3))
        if !agree.isHittable { app.swipeUp() }
        agree.tap()
        if !next.isHittable { app.swipeUp() }
        XCTAssertTrue(next.isEnabled)
        next.tap()
        XCTAssertTrue(agree.waitForNonExistence(timeout: 5))
        app.terminate()
        app.launch()
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        start.tap()
        XCTAssertFalse(agree.waitForExistence(timeout: 2))
        app.terminate()
    }
}
