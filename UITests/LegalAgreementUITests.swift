// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest

/// Run on a fresh simulator: no production data or acceptance records are reset.
@MainActor
final class LegalAgreementUITests: XCTestCase {
    func testMaxesManagementLivesInMaxesTab() {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice", "-tab", "2"]
        app.launch()
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        let manage = app.buttons["Manage maxes"]
        XCTAssertTrue(manage.waitForExistence(timeout: 5))
        manage.tap()
        XCTAssertTrue(app.navigationBars["Manage maxes"].waitForExistence(timeout: 3))
        let add = app.buttons["Add a max"]
        XCTAssertTrue(add.isHittable)
        add.tap()
        XCTAssertTrue(app.navigationBars["New max"].waitForExistence(timeout: 3))
        app.buttons["Cancel"].tap()
        app.navigationBars["Manage maxes"].buttons.firstMatch.tap()
        XCTAssertTrue(manage.waitForExistence(timeout: 3))
        app.tabBars.buttons["Settings"].tap()
        XCTAssertFalse(app.buttons["Maxes. What you can pull on each grip."].exists)
        app.terminate()
    }

    func testCompactSessionLogKeepsBothRatingsAndSaveVisible() {
        let app = XCUIApplication()
        app.launchArguments = ["-mockDevice", "-previewSummary", "-previewLog"]
        app.launch()
        let save = app.buttons["Save"]
        XCTAssertTrue(save.waitForExistence(timeout: 10))
        XCTAssertFalse(save.isEnabled)
        let volume = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Volume")).firstMatch
        XCTAssertTrue(volume.isHittable)
        volume.tap()
        for identifier in ["effort.overall", "effort.fingers"] {
            let control = app.scrollViews["sessionLog.form"].descendants(matching: .any)[identifier].firstMatch
            XCTAssertTrue(control.isHittable)
            control.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)).tap()
            XCTAssertTrue(app.buttons[identifier + ".clear"].isHittable)
        }
        XCTAssertTrue(save.isEnabled)
        XCTAssertTrue(save.isHittable)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "Compact log with both ratings"; shot.lifetime = .keepAlways
        add(shot)
        app.buttons["About session types"].tap()
        XCTAssertTrue(save.isHittable)
        app.buttons["About session types"].tap()
        app.buttons["Cancel"].tap()
        XCTAssertFalse(save.exists)
        app.terminate()
    }

    func testSummaryEffortTapDragClearKeepsFinishVisible() {
        let app = XCUIApplication()
        app.launchArguments = ["-mockDevice", "-previewSummary"]
        app.launch()
        let effort = app.descendants(matching: .any)["effort.overall"].firstMatch
        XCTAssertTrue(effort.waitForExistence(timeout: 10))
        XCTAssertEqual(effort.value as? String, "Not rated")
        effort.coordinate(withNormalizedOffset: CGVector(dx: 0.04, dy: 0.5)).tap()
        XCTAssertEqual(effort.value as? String, "Easy")
        effort.coordinate(withNormalizedOffset: CGVector(dx: 0.04, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: effort.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.5)))
        XCTAssertEqual(effort.value as? String, "All I had")
        app.buttons["effort.overall.clear"].tap()
        XCTAssertEqual(effort.value as? String, "Not rated")
        XCTAssertTrue(app.buttons["Save and finish"].isHittable)
        XCTAssertTrue(app.buttons["Discard this session"].isHittable)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Summary with capsule effort picker"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.terminate()
    }

    func testDeclineLeavesHistoryAccessibleAndExplicitAcceptanceStartsSession() {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice"]
        app.launch()
        // A fresh install can offer the routine tour before the agreement flow.
        let tourSkip = app.buttons["Skip"]
        if tourSkip.waitForExistence(timeout: 3) { tourSkip.tap() }
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

    func testCustomTargetAcceptsExactPercentages() {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice"]
        app.launch()
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        let edit = app.buttons["Edit routine"].firstMatch
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.tap()
        let target = app.buttons["Target load"].firstMatch
        for _ in 0..<7 where !target.isHittable {
            let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "20 mm edge, 4 fingers, half crimp.")).firstMatch
            if row.exists && row.isHittable && !target.exists { row.tap() }
            else { app.swipeUp() }
        }
        XCTAssertTrue(target.isHittable)
        target.tap()
        let custom = app.buttons["Custom"]
        for _ in 0..<3 where !custom.isHittable { app.swipeUp() }
        custom.tap()
        let percent = app.buttons["% of max"]
        if percent.exists { percent.tap() }
        func enter(_ label: String, _ value: String) {
            let number = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", label + ", ")).firstMatch
            for _ in 0..<4 where !number.isHittable { app.swipeUp() }
            XCTAssertTrue(number.isHittable)
            number.tap()
            let field = app.textFields.matching(NSPredicate(format: "hasKeyboardFocus == YES")).firstMatch
            // Only the active number field is empty; the routine name remains populated.
            let input = field.exists ? field : app.textFields.matching(NSPredicate(format: "value == ''")).firstMatch
            input.typeText(value)
            app.buttons["Done"].firstMatch.tap()
        }
        enter("Lower bound", "17")
        enter("Upper bound", "19")
        XCTAssertTrue(app.buttons["Lower bound, 17 %. Double tap to type a value."].exists)
        XCTAssertTrue(app.buttons["Upper bound, 19 %. Double tap to type a value."].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Exact target percentages"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.terminate()
    }

    func testMaxMeasurementControlsRemainReachableWithLargeText() {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityM"]
        app.launch()
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        app.tabBars.buttons["Maxes"].tap()
        let addMax = app.buttons["Measure for 20 mm edge, 4 fingers, half crimp"]
        XCTAssertTrue(addMax.waitForExistence(timeout: 5))
        addMax.tap()
        let measure = app.buttons["Measure on the gauge"]
        XCTAssertTrue(measure.waitForExistence(timeout: 5))
        for _ in 0..<4 where !measure.isHittable { app.swipeUp() }
        measure.tap()
        let agree = app.switches["legal.agree"]
        if agree.waitForExistence(timeout: 2) {
            for _ in 0..<4 where !agree.isHittable { app.swipeUp() }
            agree.tap()
            let next = app.buttons["legal.continue"]
            for _ in 0..<4 where !next.isHittable { app.swipeUp() }
            next.tap()
        }
        XCTAssertTrue(app.navigationBars["Measure a max"].waitForExistence(timeout: 5))
        let connect = app.buttons["Connect"]
        if connect.waitForExistence(timeout: 2) {
            for _ in 0..<4 where !connect.isHittable { app.swipeUp() }
            connect.tap()
        }
        let start = app.buttons["Start"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        for _ in 0..<4 where !start.isHittable { app.swipeUp() }
        XCTAssertTrue(start.isHittable)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Max controls with large text"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.terminate()
    }

}
