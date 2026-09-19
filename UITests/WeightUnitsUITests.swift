// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

@MainActor
final class WeightUnitsUITests: XCTestCase {
    func testSettingsPoundsChoicePersistsAndManualMaxUsesPounds() {
        let app = launchApp(arguments: ["-seedRoutine", "-mockDevice", "-tab", "3"])
        dismissTour(in: app)
        let pounds = app.buttons["Pounds (lb)"]
        reveal(pounds, in: app)
        XCTAssertTrue(pounds.isHittable)
        pounds.tap()
        attachScreenshot(app, name: "iPhone weight settings in pounds")
        app.terminate()
        app.launch()
        reveal(pounds, in: app)
        XCTAssertTrue(pounds.isSelected, pounds.debugDescription)
        app.tabBars.buttons["Maxes"].tap()
        let addMax = app.buttons["maxes.add"]
        reveal(addMax, in: app)
        addMax.tap()
        let manual = app.buttons["Enter by hand"]
        reveal(manual, in: app)
        manual.tap()
        let amount = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Left hand,")).firstMatch
        XCTAssertTrue(amount.waitForExistence(timeout: 3))
        reveal(amount, in: app)
        XCTAssertTrue(amount.label.contains("lb"))
        amount.tap()
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 2))
        field.typeText("50")
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(amount.label.contains("50.0 lb"))
        attachScreenshot(app, name: "iPhone manual max in pounds")
        app.buttons["maxEdit.cancel"].tap()
        app.terminate()
    }

    func testRestLabelStaysBetweenCountersAndPoundPreviewKeepsActionsVisible() {
        let app = launchApp(arguments: ["-previewRunnerRest", "-previewRunnerRestSeconds", "3",
                                        "-previewWeightLb"])
        let counters = element("runner.counters", in: app)
        XCTAssertTrue(counters.waitForExistence(timeout: 5))
        XCTAssertTrue(counters.label.contains("REST"), counters.debugDescription)
        let hero = element("runner.hero", in: app)
        XCTAssertTrue(hero.exists)
        XCTAssertGreaterThan(counters.frame.minY, hero.frame.maxY, "The rest label belongs below the numbers on every iPhone")
        attachScreenshot(app, name: "iPhone rest label and gray border")
        app.terminate()
        let working = launchApp(arguments: ["-previewRunnerWorking", "-previewWeightLb"])
        XCTAssertTrue(working.buttons["runner.pause"].waitForExistence(timeout: 5))
        XCTAssertTrue(working.buttons["runner.pause"].isHittable)
        attachScreenshot(working, name: "iPhone 12 kg pull displayed as 26.5 lb")
        working.terminate()
    }
}
