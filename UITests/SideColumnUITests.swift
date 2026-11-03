// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// Beside the iPhone Duo's vertical system bar, Today's routine deck runs on under the
/// column to the window edge (`SideColumns`), so ~76 pt of the NEIGHBOUR card — its dark
/// Start button included — sits beside the tab bar. That neighbour is a picture: a tap on
/// it must not start a routine nobody chose, and a swipe begun on it must still turn the
/// page. Skipped on any window whose tab bar is not vertical — a phone has no column.
@MainActor
final class SideColumnUITests: XCTestCase {

    func testNeighbourInTheColumnTakesNoTapsButStillSwipes() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines"])
        defer { app.terminate() }
        // The vertical bar is not exposed as a tab bar; its tabs are buttons. Stacked one
        // above the other, they are a side bar.
        // (Not History: the 14-day strip has a button of that name too.)
        let benchmarks = app.buttons["Benchmarks"].firstMatch
        let settings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        try XCTSkipUnless(abs(benchmarks.frame.midX - settings.frame.midX) < 1
                          && settings.frame.midY > benchmarks.frame.midY,
                          "only a window with a vertical side bar has a column")
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
        XCTAssertFalse(element("runner.content", in: app).exists,
                       "a tap on the neighbour in the column started a session")
        XCTAssertTrue(settings.isHittable, "something covered Today after a tap on the neighbour")
        XCTAssertEqual(title.frame.minX, settledX, accuracy: 1,
                       "a tap on the neighbour moved the deck")

        // A swipe that starts on the neighbour still pages the deck.
        origin.withOffset(CGVector(dx: window.maxX - 14, dy: 400))
            .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: 40, dy: 400)))
        Thread.sleep(forTimeInterval: 2)
        XCTAssertLessThan(title.frame.minX, settledX - 100,
                          "a swipe begun on the neighbour did not turn the page")
        attachScreenshot(app, name: "side-column-after-swipe")
    }

    /// History scrolled so the trend deck rises through the column: under the tab bar,
    /// past the export button, and into the status band, where its neighbour dissolves
    /// rather than meeting the battery dots. The screenshot is the evidence; the asserts
    /// only say the chrome survived the scroll.
    func testHistoryDeckScrollsUnderTheColumn() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines", "-seedHistory", "-tab", "1"])
        defer { app.terminate() }
        let benchmarks = app.buttons["Benchmarks"].firstMatch
        let settings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        try XCTSkipUnless(abs(benchmarks.frame.midX - settings.frame.midX) < 1
                          && settings.frame.midY > benchmarks.frame.midY,
                          "only a window with a vertical side bar has a column")
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
}
