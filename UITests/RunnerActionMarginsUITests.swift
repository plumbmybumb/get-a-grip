// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

@MainActor
final class RunnerActionMarginsUITests: XCTestCase {
    func testFrenchRunnerActionsStayOnScreenAtLargeText() {
        checkFrenchRunnerActions(category: "UICTContentSizeCategoryXXXL", name: "French XXXL runner actions", allowsScrolling: false)
    }

    func testFrenchRunnerActionsStayOnScreenAtLargestSupportedText() {
        checkFrenchRunnerActions(category: "UICTContentSizeCategoryAccessibilityXXXL", name: "French AX3 runner actions", allowsScrolling: true)
    }

    private func checkFrenchRunnerActions(category: String, name: String, allowsScrolling: Bool) {
        let app = XCUIApplication()
        app.launchArguments = ["-previewRunnerRest", "-previewRunnerRestSeconds", "3", "-AppleLanguages", "(fr)",
                               "-AppleLocale", "fr_FR", "-UIPreferredContentSizeCategoryName",
                               category]
        app.launch()
        defer { app.terminate() }
        let prompt = app.descendants(matching: .any).matching(identifier: "runner.prompt").firstMatch
        XCTAssertTrue(prompt.waitForExistence(timeout: 5))
        XCTAssertEqual(prompt.label, "MAIN DROITE ENSUITE")
        if allowsScrolling {
            XCTAssertGreaterThan(prompt.frame.height, 120, "A full-size French hand prompt must wrap instead of truncating")
        }
        attachScreenshot(app, name: name + " — header")
        for identifier in ["runner.pause", "runner.tare", "runner.skipPull", "runner.skipSet", "runner.end"] {
            let button = app.buttons[identifier]
            XCTAssertTrue(button.waitForExistence(timeout: 5), identifier)
            if allowsScrolling {
                let scroll = app.scrollViews["runner.content"]
                for _ in 0..<10 where !button.isHittable || button.frame.maxY > app.frame.maxY - 30 {
                    scroll.swipeUp()
                }
            }
            XCTAssertTrue(button.isHittable, identifier)
            XCTAssertGreaterThanOrEqual(button.frame.minX, app.frame.minX + 16, identifier)
            XCTAssertLessThanOrEqual(button.frame.maxX, app.frame.maxX - 16, identifier)
            XCTAssertGreaterThanOrEqual(button.frame.minY, app.frame.minY, identifier)
            XCTAssertLessThanOrEqual(button.frame.maxY, app.frame.maxY, identifier)
            XCTAssertGreaterThanOrEqual(button.frame.height, 44, identifier)
        }
        attachScreenshot(app, name: name + " — actions")
        if allowsScrolling {
            let end = app.buttons["runner.end"]
            end.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                .press(forDuration: 0.1,
                       thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)),
                       withVelocity: .slow, thenHoldForDuration: 1.1)
            XCTAssertTrue(app.buttons["runner.end"].exists,
                          "Scrolling from the hold control must cancel the hold, not end the workout")
            for _ in 0..<10 where !end.isHittable || end.frame.maxY > app.frame.maxY - 30 {
                app.scrollViews["runner.content"].swipeUp()
            }
            end.press(forDuration: 1.1)
            XCTAssertTrue(end.waitForNonExistence(timeout: 3),
                          "A stationary deliberate hold must still end the workout")
        }
    }

    private func attachScreenshot(_ app: XCUIApplication, name: String) {
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = name
        image.lifetime = .keepAlways
        add(image)
    }
}
