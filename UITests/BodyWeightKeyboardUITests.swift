// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// The body-weight keypad always has a way out (Thomas, 2026-10-31: "I can't get the
/// keyboard out so I can't Save or exit the settings screen").
///
/// The decimal pad has no Return key, and the only exit used to be a conditional keyboard
/// toolbar — host machinery the field cannot guarantee. The field now carries its own Done
/// while editing, so both hosts are checked for THAT button, not the toolbar: typing, Done,
/// focus gone, value committed, and the screen's way out reachable again.
///
/// `-bodyWeightKg none` puts a non-number in the launch-argument defaults domain, which
/// outranks the stored value, so every launch opens with no weight on file — the state
/// the critical force test asks in, and the one a new user meets in Settings.
@MainActor
final class BodyWeightKeyboardUITests: XCTestCase {
    func testSettingsFieldHasItsOwnDoneThatCommitsAndFreesTheTabs() {
        let app = launchApp(arguments: ["-seedRoutine", "-mockDevice", "-tab", "3",
                                        "-bodyWeightKg", "none"])
        let field = app.textFields["bodyWeight.field"]
        reveal(field, in: app)
        typeAndDismiss(into: field, in: app, screenshot: "Settings body weight while typing")
        XCTAssertTrue(app.tabBars.buttons["Today"].isHittable,
                      "With the keyboard gone the tabs are the way out of Settings")
        attachScreenshot(app, name: "Settings body weight committed")
    }

    func testCriticalForceFieldHasItsOwnDoneThatCommitsAndFreesCancel() {
        let app = launchApp(arguments: ["-seedRoutine", "-mockDevice", "-tab", "2",
                                        "-previewCriticalForce", "-bodyWeightKg", "none"])
        let field = app.textFields["bodyWeight.field"]
        reveal(field, in: app)
        typeAndDismiss(into: field, in: app, screenshot: "Critical force body weight while typing",
                       committedWhen: NSPredicate(format: "exists == false"))
        XCTAssertTrue(app.buttons["cf.cancel"].isHittable)
        attachScreenshot(app, name: "Critical force body weight committed")
    }

    /// `committedWhen` is how the host shows a commit: Settings keeps the field and shows the
    /// number; the critical force test asks only until a weight is on file, so its row goes.
    private func typeAndDismiss(into field: XCUIElement, in app: XCUIApplication, screenshot: String,
                                committedWhen committed: NSPredicate = NSPredicate(format: "value == '72'"),
                                file: StaticString = #filePath, line: UInt = #line) {
        field.tap()
        waitFor(field, NSPredicate(format: "hasKeyboardFocus == true"), timeout: 3, file: file, line: line)
        field.typeText("72")
        let done = app.buttons["bodyWeight.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 2), "No Done in the row while typing", file: file, line: line)
        XCTAssertTrue(done.isHittable, done.debugDescription, file: file, line: line)
        XCTAssertGreaterThanOrEqual(done.frame.height, 44, "Done is a 44 pt target", file: file, line: line)
        attachScreenshot(app, name: screenshot)
        done.tap()
        // Losing focus commits, and the Done that only exists while editing goes with it.
        waitFor(field, committed, timeout: 3, file: file, line: line)
        XCTAssertFalse(app.keyboards.firstMatch.exists && field.exists
                       && (field.value(forKey: "hasKeyboardFocus") as? Bool ?? false),
                       "The field still holds the keyboard", file: file, line: line)
        waitFor(done, NSPredicate(format: "exists == false"), timeout: 3, file: file, line: line)
    }
}
