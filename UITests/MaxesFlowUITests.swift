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
        attachScreenshot(app, name: "Maxes with direct measurement and edit actions")

        openMeasurement(for: sharedGrip, in: app)
        XCTAssertFalse(app.buttons["maxEdit.save"].exists)
        XCTAssertTrue(app.buttons["max.measure.left"].isSelected)
        XCTAssertEqual(app.buttons["max.measure.left"].value as? String, "Not measured")
        XCTAssertEqual(app.buttons["max.measure.right"].value as? String, "Not measured")
        attachScreenshot(app, name: "Known grip opens directly on the gauge")

        let leftValue = capture("left", in: app)
        XCTAssertEqual(app.buttons["max.measure.right"].value as? String, "Not measured",
                       "A left pull cannot create a right-hand measurement")
        let rightValue = capture("right", in: app)
        XCTAssertEqual(app.buttons["max.measure.left"].value as? String, leftValue,
                       "Measuring right must retain the completed left-hand peak")
        XCTAssertTrue(rightValue.contains("best of"))
        revealInSheet(app.buttons["max.measure.left"], in: app)
        attachScreenshot(app, name: "Separate left and right peaks ready to save")

        openReview(in: app)
        XCTAssertTrue(app.staticTexts["max.review.saving.left"].label.contains(number(in: leftValue)))
        XCTAssertTrue(app.staticTexts["max.review.saving.right"].label.contains(number(in: rightValue)))
        let save = app.buttons["max.review.save"]
        XCTAssertEqual(save.label, "Save maxes")
        attachScreenshot(app, name: "Review keeps each hand's hardest pull")
        tap(save, in: app)
        dismissReceiptIfPresent(in: app)
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
        attachScreenshot(app, name: "Saved measurements reopen as exact per-hand values")
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
        attachScreenshot(app, name: "Cancelled capture leaves existing maxes intact")
    }

    func testEditPrefillsExactHandsCancelsDraftAndCommitsFocusedFieldOnSave() {
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
        typeWithoutFinishing("23.4", for: "Right hand", in: app)
        attachScreenshot(app, name: "Save while the right-hand field still has focus")
        tap(app.buttons["maxEdit.save"], in: app)
        dismissReceiptIfPresent(in: app)
        XCTAssertTrue(app.buttons["maxes.edit.\(splitGrip)"].waitForExistence(timeout: 5))

        openEdit(for: splitGrip, in: app)
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("22.2 kg"))
        XCTAssertTrue(valueButton("Right hand", in: app).label.contains("23.4 kg"),
                      "Toolbar Save must commit the focused field together with the completed left edit")
        XCTAssertFalse(app.buttons["maxEdit.save"].isEnabled)
        XCTAssertTrue(app.buttons["maxEdit.history"].exists)
    }

    func testAddingManualMaxClosesBothSheetsAfterSaving() {
        let app = launch()
        defer { app.terminate() }
        tap(app.buttons["maxes.add"], in: app)
        tap(app.buttons["maxes.add.max"], in: app)   // + is a menu: a max or critical force
        XCTAssertTrue(app.navigationBars["New max"].waitForExistence(timeout: 3))
        tap(app.buttons["Enter by hand"], in: app)
        XCTAssertTrue(app.buttons["maxEdit.save"].waitForExistence(timeout: 3))
        enter("32.1", for: "Left hand", in: app)
        tap(app.buttons["maxEdit.save"], in: app)
        dismissReceiptIfPresent(in: app)
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["New max"].exists)
        XCTAssertFalse(app.navigationBars["Edit maxes"].exists)
    }

    func testExplicitUnchangedRetestAddsHistoryForOnlyTheSelectedHand() {
        let app = launch()
        defer { app.terminate() }
        openEdit(for: splitGrip, in: app)
        let leftRetest = app.buttons["maxEdit.retest.left"]
        let rightRetest = app.buttons["maxEdit.retest.right"]
        XCTAssertTrue(leftRetest.waitForExistence(timeout: 3))
        XCTAssertTrue(rightRetest.exists)
        XCTAssertFalse(leftRetest.isSelected)
        XCTAssertFalse(rightRetest.isSelected)
        XCTAssertFalse(app.buttons["maxEdit.save"].isEnabled)
        tap(leftRetest, in: app)
        XCTAssertTrue(leftRetest.isSelected)
        XCTAssertFalse(rightRetest.isSelected)
        XCTAssertTrue(app.buttons["maxEdit.save"].isEnabled)
        tap(app.buttons["maxEdit.cancel"], in: app)

        openEdit(for: splitGrip, in: app)
        XCTAssertFalse(leftRetest.isSelected, "Cancelling must discard explicit retest selections")
        XCTAssertFalse(app.buttons["maxEdit.save"].isEnabled)
        tap(leftRetest, in: app)
        attachScreenshot(app, name: "Explicit unchanged left-hand retest")
        tap(app.buttons["maxEdit.save"], in: app)
        dismissReceiptIfPresent(in: app)
        XCTAssertTrue(app.buttons["maxes.edit.\(splitGrip)"].waitForExistence(timeout: 5))
        XCTAssertEqual(current(splitGrip, side: "left", in: app).value as? String, "20.5 kg")
        XCTAssertEqual(current(splitGrip, side: "right", in: app).value as? String, "21.8 kg")

        openEdit(for: splitGrip, in: app)
        tap(app.buttons["maxEdit.history"], in: app)
        let leftHistory = historyRow("left", in: app)
        let rightHistory = historyRow("right", in: app)
        XCTAssertTrue(leftHistory.waitForExistence(timeout: 3))
        XCTAssertTrue(leftHistory.label.contains("20.5 kilograms, recorded "))
        XCTAssertTrue(leftHistory.label.contains("2 earlier maxes"),
                      "An explicitly repeated value must append a new dated record")
        XCTAssertTrue(rightHistory.label.contains("21.8 kilograms, measured "))
        XCTAssertTrue(rightHistory.label.contains("1 earlier max"),
                      "The untouched hand must retain its existing record and provenance")
        attachScreenshot(app, name: "Unchanged retest appends only the selected hand's history")
    }

    func testRecentGripChoicePrefillsItsExactHandValuesWithoutWriting() {
        let app = launch()
        defer { app.terminate() }
        tap(app.buttons["maxes.add"], in: app)
        tap(app.buttons["maxes.add.max"], in: app)   // + is a menu: a max or critical force
        let recent = app.descendants(matching: .any).matching(identifier: "newMax.recentGrips").firstMatch
        XCTAssertTrue(recent.waitForExistence(timeout: 3))
        let choice = app.buttons["newMax.grip.\(splitGrip)"]
        for _ in 0..<5 where !choice.isHittable { recent.swipeLeft() }
        tap(choice, in: app)
        XCTAssertTrue(choice.isSelected)
        attachScreenshot(app, name: "Choose a recent routine grip before entering maxes")
        tap(app.buttons["Enter by hand"], in: app)
        XCTAssertTrue(app.buttons["maxEdit.save"].waitForExistence(timeout: 3))
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("20.5 kg"))
        XCTAssertTrue(valueButton("Right hand", in: app).label.contains("21.8 kg"))
        XCTAssertFalse(app.buttons["maxEdit.shared"].exists,
                       "The chosen grip must replace every component of the initial seed")
        XCTAssertFalse(app.buttons["maxEdit.save"].isEnabled)
        tap(app.buttons["maxEdit.cancel"], in: app)
        let newMax = app.navigationBars["New max"]
        XCTAssertTrue(newMax.waitForExistence(timeout: 3))
        tap(newMax.buttons["Cancel"], in: app)
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(current(splitGrip, side: "left", in: app).value as? String, "20.5 kg")
        XCTAssertEqual(current(splitGrip, side: "right", in: app).value as? String, "21.8 kg")
        openEdit(for: splitGrip, in: app)
        tap(app.buttons["maxEdit.history"], in: app)
        XCTAssertTrue(historyRow("left", in: app).label.contains("1 earlier max"))
        XCTAssertTrue(historyRow("right", in: app).label.contains("1 earlier max"))
    }

    func testMeasurementCorrectionCanBeCancelledThenSavedWithHonestProvenanceAndTargetReceipt() {
        let app = launch(withTargetRoutines: true)
        defer { app.terminate() }
        openMeasurement(for: sharedGrip, in: app)
        let measured = capture("left", in: app)
        openReview(in: app)
        let saving = app.staticTexts["max.review.saving.left"]
        XCTAssertEqual(saving.label, "Saves \(number(in: measured)) kg.")
        tap(app.buttons["max.measure.adjust"], in: app)
        XCTAssertTrue(app.buttons["max.adjust.apply"].waitForExistence(timeout: 3))
        XCTAssertFalse(valueButton("Right hand", in: app).exists,
                       "Adjusting a captured left peak cannot fabricate a right-hand value")
        typeWithoutFinishing("27.4", for: "Left hand", in: app)
        tap(app.buttons["max.adjust.cancel"], in: app)
        XCTAssertEqual(saving.label, "Saves \(number(in: measured)) kg.",
                       "Cancelling correction must keep the original captured value")
        XCTAssertFalse(app.staticTexts["max.review.saving.right"].exists)

        tap(app.buttons["max.measure.adjust"], in: app)
        XCTAssertTrue(valueButton("Left hand", in: app).label.contains("\(number(in: measured)) kg"))
        typeWithoutFinishing("24.5", for: "Left hand", in: app)
        attachScreenshot(app, name: "Correct captured left-hand value before saving")
        tap(app.buttons["max.adjust.apply"], in: app)
        XCTAssertEqual(saving.label, "Saves 24.5 kg, adjusted by hand.")
        XCTAssertFalse(app.staticTexts["max.review.saving.right"].exists)
        tap(app.buttons["max.review.save"], in: app)

        XCTAssertTrue(app.buttons["max.receipt.done"].waitForExistence(timeout: 5))
        let dailyChange = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH %@ AND label CONTAINS %@",
            "max.receipt.percent.", "Daily no-hangs")).firstMatch
        XCTAssertTrue(dailyChange.waitForExistence(timeout: 3))
        XCTAssertTrue(dailyChange.label.contains("Left"))
        XCTAssertTrue(dailyChange.label.contains("18–22 %"))
        XCTAssertTrue(dailyChange.label.contains("now 4.5–5.5 kg"))
        XCTAssertTrue(dailyChange.label.contains("was 5.5–6.5"))
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@",
                                                        "max.receipt.scale.")).count, 0,
                       "One-hand correction cannot offer to scale a shared typed kg band")
        XCTAssertFalse(app.buttons["max.receipt.leave"].exists)
        revealInSheet(dailyChange, in: app)
        attachScreenshot(app, name: "Saved correction explains the percentage targets that changed")
        tap(app.buttons["max.receipt.done"], in: app)

        XCTAssertTrue(app.buttons["maxes.edit.\(sharedGrip)"].waitForExistence(timeout: 5))
        XCTAssertEqual(current(sharedGrip, side: "left", in: app).value as? String, "24.5 kg")
        XCTAssertFalse(current(sharedGrip, side: "right", in: app).exists)
        XCTAssertEqual(current(sharedGrip, side: "both", in: app).value as? String, "30.5 kg")
        openEdit(for: sharedGrip, in: app)
        tap(app.buttons["maxEdit.history"], in: app)
        let saved = historyRow("left", in: app)
        XCTAssertTrue(saved.waitForExistence(timeout: 3))
        XCTAssertTrue(saved.label.contains("24.5 kilograms, recorded "))
        XCTAssertFalse(saved.label.contains("measured "),
                       "A corrected value must be saved as manual, not as the raw gauge measurement")
    }

    func testFrenchMaxesAndMeasurementRemainReachableWithLargeText() {
        let app = launch(language: "fr", largeText: true)
        defer { app.terminate() }
        attachScreenshot(app, name: "French Maxes at accessibility text size")
        openMeasurement(for: splitGrip, in: app)
        for (side, name) in [("left", "Main gauche"), ("right", "Main droite")] {
            let hand = app.buttons["max.measure.\(side)"]
            XCTAssertTrue(hand.waitForExistence(timeout: 3))
            revealInSheet(hand, in: app)
            XCTAssertTrue(hand.isHittable)
            XCTAssertEqual(hand.label, name)
            XCTAssertGreaterThanOrEqual(hand.frame.minX, app.frame.minX)
            XCTAssertLessThanOrEqual(hand.frame.maxX, app.frame.maxX)
        }
        attachScreenshot(app, name: "French separate hand measurement at accessibility text size")
        connectIfNeeded(in: app)
        let tare = app.buttons["max.measure.tare"]
        XCTAssertTrue(tare.waitForExistence(timeout: 5), "The visit connects on its own")
        revealInSheet(tare, in: app)
        XCTAssertTrue(tare.isHittable)
        revealInSheet(app.buttons["max.measure.save"], in: app)
        XCTAssertTrue(app.buttons["max.measure.save"].isHittable)
        attachScreenshot(app, name: "French large text gauge controls remain reachable")
        tap(app.buttons["max.measure.cancel"], in: app)

        openEdit(for: splitGrip, in: app)
        for name in ["Main gauche", "Main droite"] {
            let value = valueButton(name, in: app)
            XCTAssertTrue(value.waitForExistence(timeout: 3))
            revealInSheet(value, in: app)
            XCTAssertTrue(value.isHittable)
        }
        attachScreenshot(app, name: "French per-hand manual edit at accessibility text size")
        XCTAssertTrue(app.buttons["maxEdit.cancel"].isHittable)
    }

    private func launch(language: String = "en", largeText: Bool = false,
                        withTargetRoutines: Bool = false) -> XCUIApplication {
        // seedHistory also resets and seeds maxes: shared 30.5 kg on the four-finger
        // grip, exact left 20.5 / right 21.8 kg on the front-three grip.
        var arguments = [withTargetRoutines ? "-seedTwoRoutines" : "-seedRoutine",
                         "-seedHistory", "-mockDevice", "-tab", "2", "-weightUnit", "kg"]
        if largeText {
            arguments += ["-UIPreferredContentSizeCategoryName",
                          "UICTContentSizeCategoryAccessibilityXXXL"]
        }
        let app = launchApp(arguments: arguments, language: language)
        dismissTour(in: app)
        XCTAssertTrue(app.buttons["maxes.measure.\(splitGrip)"].waitForExistence(timeout: 5))
        return app
    }

    private func openMeasurement(for grip: String, in app: XCUIApplication) {
        tap(app.buttons["maxes.measure.\(grip)"], in: app)
        // The one question before a visit. Matched by title: a dialog action does not
        // reliably carry its identifier, and the French run asks in French.
        // On a Benchmarks card the question also offers a critical force test, so the max
        // choices are labelled "Max, …" there.
        let oneHand = app.buttons.matching(NSPredicate(format: "label IN %@",
                                                       ["One hand at a time", "Une main à la fois",
                                                        "Max, one hand at a time"])).firstMatch
        XCTAssertTrue(oneHand.waitForExistence(timeout: 3))
        oneHand.tap()
        XCTAssertTrue(app.buttons["max.measure.left"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["max.measure.right"].exists)
        XCTAssertFalse(app.buttons["maxEdit.save"].exists)
    }

    private func openEdit(for grip: String, in app: XCUIApplication) {
        tap(app.buttons["maxes.edit.\(grip)"], in: app)
        XCTAssertTrue(app.buttons["maxEdit.save"].waitForExistence(timeout: 5))
    }

    /// Select a hand and wait for the visit to log a pull on it. No Start: the screen
    /// reads from the moment it opens, and the clean mock pulls for ten seconds of every
    /// thirty. Awaits the real logged result rather than sleeping or inventing a force.
    @discardableResult
    private func capture(_ side: String, in app: XCUIApplication) -> String {
        let hand = app.buttons["max.measure.\(side)"]
        let selectable = NSPredicate(format: "isEnabled == true")
        expectation(for: selectable, evaluatedWith: hand)
        waitForExpectations(timeout: 45)
        if !hand.isSelected { tap(hand, in: app) }
        XCTAssertTrue(hand.isSelected)
        connectIfNeeded(in: app)
        expectation(for: NSPredicate(format: "value CONTAINS %@", "best of"), evaluatedWith: hand)
        waitForExpectations(timeout: 50)
        let value = hand.value as? String ?? ""
        XCTAssertTrue(value.contains("best of 1 pull"), value)
        return value
    }

    private func openReview(in app: XCUIApplication) {
        tap(app.buttons["max.measure.save"], in: app)
        XCTAssertTrue(app.buttons["max.review.save"].waitForExistence(timeout: 3))
    }

    private func connectIfNeeded(in app: XCUIApplication) {
        let connect = app.buttons["max.measure.connect"]
        if connect.exists, connect.isEnabled { tap(connect, in: app) }
    }

    private func dismissReceiptIfPresent(in app: XCUIApplication) {
        let done = app.buttons["max.receipt.done"]
        if done.waitForExistence(timeout: 2) { tap(done, in: app) }
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
        element("maxes.current.\(grip).\(side)", in: app)
    }

    private func historyRow(_ side: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", ", \(side) hand. Max ")).firstMatch
    }

    private func enter(_ text: String, for hand: String, in app: XCUIApplication) {
        typeWithoutFinishing(text, for: hand, in: app)
        tap(app.buttons["Done"].firstMatch, in: app)
    }

    /// Leave the field active so toolbar actions exercise the pending-value commit path.
    private func typeWithoutFinishing(_ text: String, for hand: String, in app: XCUIApplication) {
        tap(valueButton(hand, in: app), in: app)
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 3))
        field.typeText(text)
        XCTAssertEqual(field.value as? String, text)
    }

    private func number(in capturedValue: String) -> String {
        String(capturedValue.prefix { $0 != " " })
    }

    private func tap(_ element: XCUIElement, in app: XCUIApplication) {
        revealInSheet(element, in: app)
        // A tap on a page still coasting from the reveal's swipe is swallowed by the scroll
        // view. At French accessibility sizes the Benchmarks page is long enough (critical
        // force rows on a seeded card) that the Measure and Edit taps were landing mid-coast.
        Thread.sleep(forTimeInterval: 0.6)
        element.tap()
    }

    /// The shared `reveal`, with this file's own two departures from its defaults: the
    /// measurement and edit sheets scroll BOTH ways — a previous step can leave the next
    /// control off the TOP — and they are the deepest screens in the suite, so the bound
    /// stays at 10. Named rather than overloaded, so no call site can pick up the plain
    /// one-directional default by accident.
    private func revealInSheet(_ element: XCUIElement, in app: XCUIApplication,
                               file: StaticString = #filePath, line: UInt = #line) {
        reveal(element, in: app, attempts: 10, bidirectional: true, file: file, line: line)
    }
}
