// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// A past workout opens from History (support request, Gleb, 1.4.0: "I can't open each one
/// to see the insights"), and the live summary it borrows its pieces from still looks the
/// way it did.
///
/// `-seedSessionKinds` puts tonight's four shapes at the top of the log, newest first:
/// a limit climb (row 0), hangs logged by hand (1), a timer-only run (2) and a measured run
/// that saved a max (3).
@MainActor
final class SessionDetailUITests: XCTestCase {

    /// The finish summary with both disclosures open — the screenshots the extraction is
    /// pixel-diffed against, before and after.
    func testLiveSummaryWithSetsAndPeaksOpen() {
        let app = launchApp(arguments: ["-previewSummary", "-mockDevice"])
        defer { app.terminate() }
        let sets = app.buttons["summary.sets"]
        reveal(sets, in: app)
        sets.tap()
        let peaks = app.buttons["summary.peaks"]
        reveal(peaks, in: app, bidirectional: true)
        peaks.tap()
        // Let the disclosure animations settle before the picture.
        let row = app.buttons["summary.peak.20|IMRL|halfCrimp|both"]
        XCTAssertTrue(row.waitForExistence(timeout: 3))
        Thread.sleep(forTimeInterval: 1)
        attachScreenshot(app, name: "summary-expanded-top")
        // The set rows sit below the fold; the end of the scroll is a stable picture.
        app.swipeUp()
        app.swipeUp()
        Thread.sleep(forTimeInterval: 2)
        attachScreenshot(app, name: "summary-expanded-bottom")
    }

    /// Tap a measured session: the summary's numbers, the max it saved, every pull — and
    /// Delete pops back to History with the house Undo, no dialog.
    func testMeasuredSessionOpensAndDeletesWithUndo() {
        let app = launchHistory()
        defer { app.terminate() }
        let rows = app.buttons.matching(identifier: "history.session")
        let row = rows.element(boundBy: 3)
        reveal(row, in: app)
        XCTAssertTrue(row.label.contains("Daily no-hangs"), row.label)
        XCTAssertTrue(row.label.contains("peak 33.4 kilograms"), row.label)
        row.tap()

        let export = app.buttons["sessionDetail.export"]
        XCTAssertTrue(export.waitForExistence(timeout: 5))
        XCTAssertEqual(export.label, "Export workout")
        let stats = labelled(beginningWith: "34 of 36 pulls completed", in: app)
        XCTAssertTrue(stats.waitForExistence(timeout: 3))
        XCTAssertTrue(stats.label.contains("peak 33.4 kilograms"), stats.label)
        XCTAssertTrue(labelled(containing: "Left hand · 20mm", in: app).exists)
        // Nothing to decide on a frozen session.
        XCTAssertFalse(app.buttons["Save and finish"].exists)
        let skipped = labelled(containing: "Skipped", in: app)
        reveal(skipped, in: app)
        XCTAssertTrue(skipped.label.contains("held 0.0 of 10 seconds"), skipped.label)
        attachScreenshot(app, name: "detail-measured-sets")

        app.buttons["sessionDetail.delete"].tap()
        let undo = app.buttons["Undo. Session deleted"]
        XCTAssertTrue(undo.waitForExistence(timeout: 5))
        // Back on History, without the deleted row — the only one with a 33.4 kg peak.
        let deletedRow = rows.matching(NSPredicate(format: "label CONTAINS %@", "peak 33.4 kilograms")).firstMatch
        waitFor(deletedRow, NSPredicate(format: "exists == false"), timeout: 5)
        undo.tap()
        waitFor(deletedRow, NSPredicate(format: "exists == true"), timeout: 5)
    }

    /// A climb has no pulls: the kind, the day and what was logged — no stats, no sets.
    func testClimbShowsOnlyWhatWasLogged() {
        let app = launchHistory()
        defer { app.terminate() }
        let row = app.buttons.matching(identifier: "history.session").element(boundBy: 0)
        reveal(row, in: app)
        row.tap()
        let delete = app.buttons["sessionDetail.delete"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        XCTAssertEqual(delete.label, "Delete workout")
        XCTAssertFalse(app.buttons["summary.sets"].exists)
        XCTAssertFalse(labelled(containing: "pulls completed", in: app).exists)
        let duration = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "Duration")).firstMatch
        XCTAssertTrue(duration.exists)
        XCTAssertEqual(duration.value as? String, "1 hour, 45 minutes")
        attachScreenshot(app, name: "detail-climb")
    }

    /// The per-pull lines stack at the largest text rather than truncating a number.
    func testTimerOnlySetsAtAccessibilitySize() {
        let app = launchHistory(largeText: true)
        defer { app.terminate() }
        let row = app.buttons.matching(identifier: "history.session").element(boundBy: 2)
        reveal(row, in: app)
        row.tap()
        XCTAssertTrue(app.buttons["sessionDetail.export"].waitForExistence(timeout: 5))
        let stopped = labelled(containing: "Stopped", in: app)
        reveal(stopped, in: app)
        // Timer-only: no peak is spoken, because none was measured.
        XCTAssertFalse(stopped.label.contains("peak"), stopped.label)
        XCTAssertLessThanOrEqual(stopped.frame.maxX, app.frame.maxX)
        attachScreenshot(app, name: "detail-timer-only-sets-ax5")
    }

    private func labelled(containing text: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func labelled(beginningWith text: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", text)).firstMatch
    }

    private func launchHistory(largeText: Bool = false) -> XCUIApplication {
        var arguments = ["-seedTwoRoutines", "-seedHistory", "-seedSessionKinds", "-tab", "1", "-mockDevice"]
        if largeText {
            arguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        }
        return launchApp(arguments: arguments)
    }
}
