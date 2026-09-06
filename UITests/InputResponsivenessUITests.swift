// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

@MainActor
final class InputResponsivenessUITests: XCTestCase {
    func testBuilderStepperRespondsToTapAndHoldWithoutEditingDuringScroll() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice"]
        app.launch()
        defer { app.terminate() }
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        let edit = app.buttons["Edit routine"].firstMatch
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
    }
}
