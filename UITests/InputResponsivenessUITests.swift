// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

@MainActor
final class InputResponsivenessUITests: XCTestCase {
    func testBuilderStepperRespondsToTapAndHoldWithoutEditingDuringScroll() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice"]
        app.launch()
        defer { app.terminate() }
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        let overview = app.buttons["routine.overview.open"].firstMatch
        XCTAssertTrue(overview.waitForExistence(timeout: 5))
        overview.tap()
        let edit = app.buttons["routine.overview.edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.tap()
        let increase = app.buttons["Increase Pulls per side"].firstMatch
        for _ in 0..<7 where !increase.isHittable {
            let row = app.buttons.matching(NSPredicate(
                format: "label BEGINSWITH %@", "20 mm edge, 4 fingers, half crimp.")).firstMatch
            if row.isHittable && !increase.exists { row.tap() }
            else { app.swipeUp() }
        }
        XCTAssertTrue(increase.isHittable)
        let number = app.buttons.matching(NSPredicate(
            format: "label BEGINSWITH %@", "Pulls per side, ")).firstMatch
        func amount() throws -> Int {
            let value = number.label.dropFirst("Pulls per side, ".count).prefix(while: \.isNumber)
            return try XCTUnwrap(Int(value))
        }
        let original = try amount()
        increase.tap()
        XCTAssertEqual(try amount(), original + 1)
        increase.press(forDuration: 0.8)
        let afterHold = try amount()
        XCTAssertGreaterThan(afterHold, original + 1)
        let start = increase.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.05,
                    thenDragTo: start.withOffset(CGVector(dx: 0, dy: -80)))
        XCTAssertEqual(try amount(), afterHold)

        // Typed entry is the escape hatch for long sets; the real builder must not
        // silently clamp it to the old 20-pull limit.
        for count in [36, 100] {
            // The preceding scroll gesture can leave the number partly outside
            // the viewport. An accessibility match is not proof that its whole
            // touch target is visible; bring it clear of the screen edges first.
            let visibleArea = app.frame.insetBy(dx: 0, dy: 150)
            for _ in 0..<5 where !visibleArea.contains(number.frame) {
                if number.frame.midY > visibleArea.maxY { app.swipeUp() }
                else { app.swipeDown() }
            }
            XCTAssertTrue(visibleArea.contains(number.frame))
            XCTAssertTrue(number.isHittable)
            let previous = try amount()
            number.tap()
            let field = app.textFields.matching(NSPredicate(
                format: "placeholderValue == %@", String(previous))).firstMatch
            XCTAssertTrue(field.waitForExistence(timeout: 2))
            field.typeText(String(count))
            app.buttons["Done"].firstMatch.tap()
            XCTAssertEqual(try amount(), count)
        }
        increase.tap()
        XCTAssertEqual(try amount(), 100)
    }
}
