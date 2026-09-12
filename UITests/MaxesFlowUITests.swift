// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

@MainActor
final class MaxesFlowUITests: XCTestCase {
    private let sharedGrip = "20|IMRL|halfCrimp"
    private let splitGrip = "20|IMR|halfCrimp"

    func testMeasureAgainOpensKnownGripAndSavesEachMeasuredHand() {
        let app = launch()
        defer { app.terminate() }
        XCTAssertFalse(app.buttons["Manage maxes"].exists)
        XCTAssertTrue(app.buttons["maxes.add"].exists)
        screenshot(app, name: "Maxes with direct measurement and edit actions")

        openMeasurement(for: sharedGrip, in: app)
        XCTAssertFalse(app.buttons["maxEdit.save"].exists)
        XCTAssertTrue(app.buttons["max.measure.left"].isSelected)
        XCTAssertEqual(app.buttons["max.measure.left"].value as? String, "Not measured")
        XCTAssertEqual(app.buttons["max.measure.right"].value as? String, "Not measured")
        screenshot(app, name: "Known grip opens directly on the gauge")

        let leftValue = capture("left", in: app)
        XCTAssertEqual(app.buttons["max.measure.right"].value as? String, "Not measured",
                       "A left pull cannot create a right-hand measurement")
        let rightValue = capture("right", in: app)
        XCTAssertEqual(app.buttons["max.measure.left"].value as? String, leftValue,
                       "Measuring right must retain the completed left-hand peak")
        XCTAssertTrue(rightValue.contains("ready to save"))
        reveal(app.buttons["max.measure.left"], in: app)
        screenshot(app, name: "Separate left and right peaks ready to save")

        let save = app.buttons["max.measure.save"]
        XCTAssertEqual(save.label, "Save maxes")
        tap(save, in: app)
        XCTAssertTrue(app.buttons["maxes.edit.\(sharedGrip)"].waitForExistence(timeout: 5))
        XCTAssertEqual(current(sharedGrip, side: "left", in: app).value as? String,
                       "\(number(in: leftValue)) kg")
        XCTAssertEqual(current(sharedGrip, side: "right", in: app).value as? String,
                       "\(number(in: rightValue)) kg")
        XCTAssertEqual(current(sharedGrip, side: "both", in: app).value as? String, "30.5 kg")
        openEdit(for: sharedGrip, in: app)
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("\(number(in: leftValue)) kg"))
        XCTAssertTrue(valueButton("Right hand", in: app).label.contains("\(number(in: rightValue)) kg"))
        XCTAssertTrue(app.buttons["maxEdit.shared"].exists,
                      "Separate hand measurements retain the previous shared record")
        XCTAssertFalse(app.buttons["maxEdit.save"].isEnabled)
        screenshot(app, name: "Saved measurements reopen as exact per-hand values")
    }

    func testCancellingCapturedMaxPreservesSharedFallbackWithoutCreatingHandRecords() {
        let app = launch()
        defer { app.terminate() }
        let previousShared = current(sharedGrip, side: "both", in: app).value as? String
        XCTAssertEqual(previousShared, "30.5 kg")
        openEdit(for: sharedGrip, in: app)
        assertNoExactHandMaxes(in: app)
        tap(app.buttons["maxEdit.cancel"], in: app)

        openMeasurement(for: sharedGrip, in: app)
        _ = capture("left", in: app)
        tap(app.buttons["max.measure.cancel"], in: app)
        XCTAssertTrue(app.buttons["maxes.edit.\(sharedGrip)"].waitForExistence(timeout: 5))
        XCTAssertEqual(current(sharedGrip, side: "both", in: app).value as? String, previousShared)
        XCTAssertFalse(current(sharedGrip, side: "left", in: app).exists)
        XCTAssertFalse(current(sharedGrip, side: "right", in: app).exists)

        openEdit(for: sharedGrip, in: app)
        assertNoExactHandMaxes(in: app)
        screenshot(app, name: "Cancelled capture leaves existing maxes intact")
    }

    func testEditPrefillsExactHandsCancelsDraftAndSavesOnlyChangedHand() {
        let app = launch()
        defer { app.terminate() }
        openEdit(for: splitGrip, in: app)
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("20.5 kg"))
        XCTAssertTrue(valueButton("Right hand", in: app).label.contains("21.8 kg"))
        XCTAssertFalse(app.buttons["maxEdit.save"].isEnabled)
        XCTAssertFalse(app.buttons["maxEdit.shared"].exists)

        enter("33.3", for: "Left hand", in: app)
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("33.3 kg"))
        XCTAssertTrue(valueButton("Right hand", in: app).label.contains("21.8 kg"))
        tap(app.buttons["maxEdit.cancel"], in: app)

        openEdit(for: splitGrip, in: app)
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("20.5 kg"))
        XCTAssertTrue(valueButton("Right hand", in: app).label.contains("21.8 kg"))
        enter("22.2", for: "Left hand", in: app)
        screenshot(app, name: "Manual edit changes one exact hand")
        tap(app.buttons["maxEdit.save"], in: app)
        XCTAssertTrue(app.buttons["maxes.edit.\(splitGrip)"].waitForExistence(timeout: 5))

        openEdit(for: splitGrip, in: app)
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("22.2 kg"))
        XCTAssertTrue(valueButton("Right hand", in: app).label.contains("21.8 kg"),
                      "Saving left must retain the exact right-hand value")
        XCTAssertFalse(app.buttons["maxEdit.save"].isEnabled)
        XCTAssertTrue(app.buttons["maxEdit.history"].exists)
    }

    func testAddingManualMaxClosesBothSheetsAfterSaving() {
        let app = launch()
        defer { app.terminate() }
        tap(app.buttons["maxes.add"], in: app)
        XCTAssertTrue(app.navigationBars["New max"].waitForExistence(timeout: 3))
        tap(app.buttons["Enter by hand"], in: app)
        XCTAssertTrue(app.buttons["maxEdit.save"].waitForExistence(timeout: 3))
        enter("32.1", for: "Left hand", in: app)
        tap(app.buttons["maxEdit.save"], in: app)
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["New max"].exists)
        XCTAssertFalse(app.navigationBars["Edit maxes"].exists)
    }

    func testFrenchMaxesAndMeasurementRemainReachableWithLargeText() {
        let app = launch(language: "fr", largeText: true)
        defer { app.terminate() }
        screenshot(app, name: "French Maxes at accessibility text size")
        openMeasurement(for: splitGrip, in: app)
        for (side, name) in [("left", "Main gauche"), ("right", "Main droite")] {
            let hand = app.buttons["max.measure.\(side)"]
            XCTAssertTrue(hand.waitForExistence(timeout: 3))
            reveal(hand, in: app)
            XCTAssertTrue(hand.isHittable)
            XCTAssertEqual(hand.label, name)
            XCTAssertGreaterThanOrEqual(hand.frame.minX, app.frame.minX)
            XCTAssertLessThanOrEqual(hand.frame.maxX, app.frame.maxX)
        }
        screenshot(app, name: "French separate hand measurement at accessibility text size")
        connectIfNeeded(in: app)
        let start = app.buttons["max.measure.start"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        reveal(start, in: app)
        XCTAssertTrue(start.isHittable)
        screenshot(app, name: "French large text gauge controls remain reachable")
        tap(app.buttons["max.measure.cancel"], in: app)

        openEdit(for: splitGrip, in: app)
        for name in ["Main gauche", "Main droite"] {
            let value = valueButton(name, in: app)
            XCTAssertTrue(value.waitForExistence(timeout: 3))
            reveal(value, in: app)
            XCTAssertTrue(value.isHittable)
        }
        screenshot(app, name: "French per-hand manual edit at accessibility text size")
        XCTAssertTrue(app.buttons["maxEdit.cancel"].isHittable)
    }

    private func launch(language: String = "en", largeText: Bool = false) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        // seedHistory also resets and seeds maxes: shared 30.5 kg on the four-finger
        // grip, exact left 20.5 / right 21.8 kg on the front-three grip.
        app.launchArguments = ["-seedRoutine", "-seedHistory", "-mockDevice", "-tab", "2",
                               "-AppleLanguages", "(\(language))", "-AppleLocale",
                               language == "fr" ? "fr_FR" : "en_US", "-weightUnit", "kg"]
        if largeText {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName",
                                    "UICTContentSizeCategoryAccessibilityXXXL"]
        }
        app.launch()
        let skip = app.buttons[language == "fr" ? "Passer" : "Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        XCTAssertTrue(app.buttons["maxes.measure.\(splitGrip)"].waitForExistence(timeout: 5))
        return app
    }

    private func openMeasurement(for grip: String, in app: XCUIApplication) {
        tap(app.buttons["maxes.measure.\(grip)"], in: app)
        XCTAssertTrue(app.buttons["max.measure.left"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["max.measure.right"].exists)
        XCTAssertFalse(app.buttons["maxEdit.save"].exists)
    }

    private func openEdit(for grip: String, in app: XCUIApplication) {
        tap(app.buttons["maxes.edit.\(grip)"], in: app)
        XCTAssertTrue(app.buttons["maxEdit.save"].waitForExistence(timeout: 5))
    }

    @discardableResult
    private func capture(_ side: String, in app: XCUIApplication) -> String {
        let hand = app.buttons["max.measure.\(side)"]
        tap(hand, in: app)
        connectIfNeeded(in: app)
        tap(app.buttons["max.measure.start"], in: app)
        XCTAssertTrue(app.buttons["max.measure.finish"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["max.measure.left"].isEnabled)
        XCTAssertFalse(app.buttons["max.measure.right"].isEnabled,
                       "An active pull cannot change hands midway through its samples")
        // The clean mock pulls for ten seconds, then releases. Await the actual
        // captured result rather than sleeping or inventing a force value in the test.
        XCTAssertTrue(app.buttons["max.measure.save"].waitForExistence(timeout: 20))
        let value = hand.value as? String ?? ""
        XCTAssertTrue(value.contains("ready to save"), value)
        XCTAssertTrue(hand.isEnabled)
        return value
    }

    private func connectIfNeeded(in app: XCUIApplication) {
        let connect = app.buttons["Connect"]
        if connect.exists { tap(connect, in: app) }
        // The French localized title shares the same connection action.
        let frenchConnect = app.buttons["Connecter"]
        if frenchConnect.exists { tap(frenchConnect, in: app) }
    }

    private func assertNoExactHandMaxes(in app: XCUIApplication) {
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("0.0 kg"))
        XCTAssertTrue(valueButton("Right hand", in: app).label.contains("0.0 kg"))
        XCTAssertTrue(app.buttons["maxEdit.shared"].exists)
        XCTAssertFalse(app.buttons["maxEdit.save"].isEnabled,
                       "A shared fallback must never prefill new exact-hand records")
    }

    private func valueButton(_ hand: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(hand), ")).firstMatch
    }

    private func current(_ grip: String, side: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(identifier: "maxes.current.\(grip).\(side)").firstMatch
    }

    private func enter(_ text: String, for hand: String, in app: XCUIApplication) {
        tap(valueButton(hand, in: app), in: app)
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 3))
        field.typeText(text)
        tap(app.buttons["Done"].firstMatch, in: app)
    }

    private func number(in capturedValue: String) -> String {
        String(capturedValue.prefix { $0 != " " })
    }

    private func tap(_ element: XCUIElement, in app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        reveal(element, in: app)
        XCTAssertTrue(element.isHittable, element.debugDescription)
        element.tap()
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<10 where !element.isHittable {
            if element.exists, element.frame.minY < app.frame.midY {
                app.swipeDown()
            } else {
                app.swipeUp()
            }
        }
    }

    private func screenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
