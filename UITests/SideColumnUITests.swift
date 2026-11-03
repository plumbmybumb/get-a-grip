// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// Beside the iPhone Duo's vertical system bar, Today's routine deck runs on under the
/// column to the window edge (`SideColumns`), so ~76 pt of the NEIGHBOUR card — its dark
/// Start button included — sits beside the tab bar. A tap there brings that card forward
/// and must never start a routine nobody chose; a swipe begun on it must still turn the
/// page; and a settled card keeps its controls wherever the page has scrolled it.
/// Skipped on any window whose tab bar is not vertical — a phone has no column.
@MainActor
final class SideColumnUITests: XCTestCase {

    func testTapOnTheNeighbourBringsItForwardAndStartsNothing() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines"])
        defer { app.terminate() }
        try skipUnlessVerticalBar(app)
        let title = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH 'Daily no-hangs'")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        Thread.sleep(forTimeInterval: 3)
        let window = app.windows.firstMatch.frame
        let settledX = title.frame.minX

        // The neighbour's Start control, in the bare band between the gauge button and the
        // tab bar's pill (measured on the Duo simulator: y 298–353, x 406 to the edge).
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: window.maxX - 14, dy: 325)).tap()
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(element("runner.pause", in: app).exists,
                       "a tap on the neighbour in the column started a session")
        let pagedX = title.frame.minX
        XCTAssertLessThan(pagedX, settledX - 100, "a tap on the neighbour did not bring it forward")
        attachScreenshot(app, name: "side-column-after-tap")

        // A swipe that starts on the new neighbour still pages the deck.
        origin.withOffset(CGVector(dx: window.maxX - 14, dy: 400))
            .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: 40, dy: 400)))
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThan(title.frame.minX, pagedX - 100,
                          "a swipe begun on the neighbour did not turn the page")
        attachScreenshot(app, name: "side-column-after-swipe")
    }

    /// The guard must not catch the card it does not mean: the SETTLED card's controls work.
    func testSettledCardStillStarts() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines"])
        defer { app.terminate() }
        try skipUnlessVerticalBar(app)
        let window = app.windows.firstMatch.frame
        var start: XCUIElement?
        for _ in 0..<20 where start == nil {
            start = app.buttons.allElementsBoundByIndex.filter { $0.label == "Start without a gauge" }
                .first { $0.frame.midX < window.midX + 40 && $0.frame.minX > 0 }
            if start == nil { Thread.sleep(forTimeInterval: 0.5) }
        }
        try XCTUnwrap(start, "no Start on the settled card").tap()
        XCTAssertTrue(element("runner.pause", in: app).waitForExistence(timeout: 8),
                      "the settled card's Start did nothing")
    }

    /// The settled trend card stands mostly below History's fold, its grip chips on screen.
    /// Being cut off by the page must not cost it its controls.
    func testSettledCardBelowTheFoldKeepsItsControls() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines", "-seedHistory"])
        defer { app.terminate() }
        try skipUnlessVerticalBar(app)
        openHistory(app)
        try assertChipTakesATap(in: app)
    }

    /// The same chip with History reached from Today's 14-day strip, and with History as
    /// the launch tab: selected in code rather than from the bar.
    func testChipTakesATapFromTheStrip() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines", "-seedHistory"])
        defer { app.terminate() }
        try skipUnlessVerticalBar(app)
        let strip = app.buttons.allElementsBoundByIndex.filter { $0.label == "History" }
            .min { $0.frame.minX < $1.frame.minX }
        XCTAssertNotNil(strip, "no History button on Today's strip")
        strip?.tap()
        Thread.sleep(forTimeInterval: 3)
        try assertChipTakesATap(in: app)
    }

    func testChipTakesATapOnTheLaunchTab() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines", "-seedHistory", "-tab", "1"])
        defer { app.terminate() }
        try skipUnlessVerticalBar(app)
        Thread.sleep(forTimeInterval: 3)
        try assertChipTakesATap(in: app)
    }

    /// History scrolled so the trend deck rises through the column: under the tab bar,
    /// past the export button, and into the status band, where its neighbour dissolves
    /// rather than meeting the battery dots. The screenshot is the evidence; the asserts
    /// only say the chrome survived the scroll.
    func testHistoryDeckScrollsUnderTheColumn() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines", "-seedHistory", "-tab", "1"])
        defer { app.terminate() }
        try skipUnlessVerticalBar(app)
        Thread.sleep(forTimeInterval: 3)
        attachScreenshot(app, name: "side-column-history-rest")
        let origin = app.coordinate(withNormalizedOffset: .zero)
        // Slow, so the drag ends where it stops instead of flinging.
        origin.withOffset(CGVector(dx: 200, dy: 600))
            .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: 200, dy: 330)),
                   withVelocity: .slow, thenHoldForDuration: 0.5)
        Thread.sleep(forTimeInterval: 2)
        attachScreenshot(app, name: "side-column-history-scrolled")
        XCTAssertTrue(app.buttons["Export workout"].firstMatch.exists
                      || app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'export'")).firstMatch.exists)
    }

    private func assertChipTakesATap(in app: XCUIApplication) throws {
        for _ in 0..<20 where trendChips(in: app).count < 2 {
            Thread.sleep(forTimeInterval: 0.5)
        }
        // At the largest text size the card opens below the fold.
        let origin = app.coordinate(withNormalizedOffset: .zero)
        for _ in 0..<3 where trendChips(in: app).count < 2 {
            origin.withOffset(CGVector(dx: 200, dy: 600))
                .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: 200, dy: 360)),
                       withVelocity: .slow, thenHoldForDuration: 0.5)
            Thread.sleep(forTimeInterval: 1)
        }
        let chips = trendChips(in: app)
        XCTAssertGreaterThan(chips.count, 1, "no grip chips on History")
        let chip = chips[1]
        let label = chip.label
        XCTAssertFalse(chip.isSelected)
        chip.tap()
        Thread.sleep(forTimeInterval: 1)
        XCTAssertTrue(trendChips(in: app).first { $0.label == label }?.isSelected == true,
                      "a chip on the settled trend card ignored the tap")
    }

    private func skipUnlessVerticalBar(_ app: XCUIApplication) throws {
        // The vertical bar is not exposed as a tab bar; its tabs are buttons. Stacked one
        // above the other, they are a side bar.
        // (Not History: the 14-day strip has a button of that name too.)
        let benchmarks = app.buttons["Benchmarks"].firstMatch
        let settings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        try XCTSkipUnless(abs(benchmarks.frame.midX - settings.frame.midX) < 1
                          && settings.frame.midY > benchmarks.frame.midY,
                          "only a window with a vertical side bar has a column")
    }

    /// The vertical bar's History item, by position: Today's 14-day strip has a button of
    /// the same name.
    private func openHistory(_ app: XCUIApplication) {
        let tab = app.buttons.allElementsBoundByIndex.filter { $0.label == "History" }
            .max { $0.frame.minX < $1.frame.minX }
        XCTAssertNotNil(tab, "no History item on the bar")
        tab?.tap()
        Thread.sleep(forTimeInterval: 3)
    }

    /// The grip chips of every trend card, left to right. On screen and short (120 clears
    /// the largest text size): the session rows further down the page name their grips too.
    private func trendChips(in app: XCUIApplication) -> [XCUIElement] {
        let window = app.windows.firstMatch.frame
        return app.buttons.allElementsBoundByIndex
            .filter { $0.label.contains("mm") && $0.frame.height < 120
                && $0.frame.minY > 0 && $0.frame.maxY < window.maxY }
            .sorted { $0.frame.minX < $1.frame.minX }
    }
}
