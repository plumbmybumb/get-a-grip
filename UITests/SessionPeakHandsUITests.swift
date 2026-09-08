// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

@MainActor
final class SessionPeakHandsUITests: XCTestCase {
    func testPeakReviewNamesEveryHandAndSelectionsStayIndependent() {
        let app = launch(language: "en", largeText: false)
        defer { app.terminate() }
        openPeaks(in: app)
        let left = peak("left", in: app)
        let right = peak("right", in: app)
        let both = peak("both", in: app)
        for (row, hand, kg) in [(left, "Left hand", "35.0"),
                                (right, "Right hand", "30.0"),
                                (both, "Both hands", "45.0")] {
            XCTAssertTrue(row.waitForExistence(timeout: 3))
            XCTAssertTrue(row.label.contains(hand))
            XCTAssertTrue(row.label.contains(kg))
            XCTAssertEqual(row.value as? String, "Use as max")
        }
        reveal(left, in: app)
        left.tap()
        XCTAssertEqual(left.value as? String, "Selected")
        XCTAssertEqual(right.value as? String, "Use as max")
        XCTAssertEqual(both.value as? String, "Use as max")
        attachScreenshot(app, name: "Independent hand peak selections")
    }

    func testFrenchHandLabelsRemainReadableAtAccessibilitySize() {
        let app = launch(language: "fr", largeText: true)
        defer { app.terminate() }
        openPeaks(in: app)
        for (side, hand) in [("both", "Les deux mains"),
                             ("left", "Main gauche"),
                             ("right", "Main droite")] {
            let row = peak(side, in: app)
            XCTAssertTrue(row.waitForExistence(timeout: 3))
            reveal(row, in: app)
            XCTAssertTrue(row.isHittable)
            XCTAssertTrue(row.label.contains(hand))
            XCTAssertGreaterThanOrEqual(row.frame.minX, 0)
            XCTAssertLessThanOrEqual(row.frame.maxX, app.frame.maxX)
            attachScreenshot(app, name: "French accessibility peak \(side)")
        }
    }

    private func launch(language: String, largeText: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-previewSummary", "-previewHandMaxes", "-mockDevice",
                               "-AppleLanguages", "(\(language))", "-AppleLocale", language]
        if largeText {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName",
                                    "UICTContentSizeCategoryAccessibilityXXXL"]
        }
        app.launch()
        return app
    }

    private func openPeaks(in app: XCUIApplication) {
        let review = app.buttons["summary.peaks"]
        XCTAssertTrue(review.waitForExistence(timeout: 5))
        XCTAssertFalse(peak("left", in: app).exists, "Peak review starts collapsed")
        reveal(review, in: app)
        review.tap()
    }

    private func peak(_ side: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons["summary.peak.20|IMRL|halfCrimp|\(side)"]
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 where !element.isHittable { app.swipeUp() }
    }

    private func attachScreenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
