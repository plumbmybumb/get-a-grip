// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// While typing a body weight in Settings, the field's whole CARD stays above the keyboard
/// (2026-10-31). The system scrolls only the focused field into view, so the row sat flush
/// on the keyboard with its own caption underneath it; `KeyboardReveal` now lifts the row
/// until the room below it clears the keyboard.
///
/// The caption is the last thing in the card, so its bottom clearing the keyboard's top by
/// 16 pt is the check. Run at the default size and at the two sizes where the caption wraps
/// hardest, because the room scales with Dynamic Type.
@MainActor
final class KeyboardRoomUITests: XCTestCase {
    private let caption = "Shows critical force as a share of body weight. Past tests keep their weight."

    func testBodyWeightCardClearsTheKeyboardAtDefaultSize() {
        assertCardClearsKeyboard(size: "UICTContentSizeCategoryL")
    }

    func testBodyWeightCardClearsTheKeyboardAtXXXL() {
        assertCardClearsKeyboard(size: "UICTContentSizeCategoryXXXL")
    }

    func testBodyWeightCardClearsTheKeyboardAtAccessibilityMedium() {
        assertCardClearsKeyboard(size: "UICTContentSizeCategoryAccessibilityM")
    }

    private func assertCardClearsKeyboard(size: String, file: StaticString = #filePath, line: UInt = #line) {
        let app = launchApp(arguments: ["-seedRoutine", "-mockDevice", "-tab", "3", "-bodyWeightKg", "none",
                                        "-UIPreferredContentSizeCategoryName", size])
        let field = app.textFields["bodyWeight.field"]
        reveal(field, in: app, file: file, line: line)
        field.tap()
        waitFor(field, NSPredicate(format: "hasKeyboardFocus == true"), timeout: 3, file: file, line: line)
        field.typeText("72")
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 3), "No keyboard", file: file, line: line)
        let text = app.staticTexts[caption]
        XCTAssertTrue(text.waitForExistence(timeout: 3), "No caption", file: file, line: line)
        // 32, not 16: XCUITest reports the keyboard's frame ~17 pt below the top edge the
        // keyboard actually draws (and announces in its notification). The lift runs once
        // the keyboard has finished arriving, so poll for it to settle, bounded.
        let clears = { text.frame.maxY + 32 <= keyboard.frame.minY }
        for _ in 0..<25 where !clears() {
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        XCTAssertTrue(clears(), "Caption ends at \(text.frame.maxY), keyboard starts at \(keyboard.frame.minY)",
                      file: file, line: line)
        XCTAssertGreaterThan(field.frame.minY, app.navigationBars.firstMatch.frame.maxY - 1,
                             "The lift must never push the field under the top bar", file: file, line: line)
        attachScreenshot(app, name: "Body weight card above the keyboard (\(size))")
    }
}
