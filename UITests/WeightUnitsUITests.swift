// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

@MainActor
final class WeightUnitsUITests: XCTestCase {
    func testSettingsPoundsChoicePersistsAndManualMaxUsesPounds() {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice", "-tab", "3", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        let pounds = app.buttons["Pounds (lb)"]
        reveal(pounds, in: app)
        XCTAssertTrue(pounds.isHittable)
        pounds.tap()
        screenshot(app, name: "iPhone weight settings in pounds")
        app.terminate()
        app.launch()
        reveal(pounds, in: app)
        XCTAssertTrue(pounds.isSelected, pounds.debugDescription)
        app.tabBars.buttons["Maxes"].tap()
        app.buttons["Manage maxes"].tap()
        app.buttons["Add a max"].tap()
        let amount = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Max on this grip,")).firstMatch
        XCTAssertTrue(amount.waitForExistence(timeout: 3))
        reveal(amount, in: app)
        XCTAssertTrue(amount.label.contains("lb"))
        amount.tap()
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 2))
        field.typeText("50")
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(amount.label.contains("50.0 lb"))
        screenshot(app, name: "iPhone manual max in pounds")
        app.buttons["Cancel"].tap()
        app.terminate()
    }

    func testRestLabelStaysBetweenCountersAndPoundPreviewKeepsActionsVisible() {
        let app = XCUIApplication()
        app.launchArguments = ["-previewRunnerRest", "-previewWeightLb", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let counters = app.descendants(matching: .any).matching(identifier: "runner.counters").firstMatch
        XCTAssertTrue(counters.waitForExistence(timeout: 5))
        XCTAssertTrue(counters.label.contains("REST"), counters.debugDescription)
        screenshot(app, name: "iPhone rest label and gray border")
        app.terminate()
        app.launchArguments = ["-previewRunnerWorking", "-previewWeightLb", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.buttons["runner.pause"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["runner.pause"].isHittable)
        screenshot(app, name: "iPhone 12 kg pull displayed as 26.5 lb")
        app.terminate()
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 where !element.isHittable { app.swipeUp() }
    }
    private func screenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
