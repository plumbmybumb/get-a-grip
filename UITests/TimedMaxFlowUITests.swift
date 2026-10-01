// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// Timed maxes end to end, against the demo gauge: measure a 10 s max on Benchmarks, then
/// prescribe a set as a percentage OF it in the builder. Every step is attached as a
/// screenshot, so the run doubles as the walkthrough of the feature.
@MainActor
final class TimedMaxFlowUITests: XCTestCase {
    private let grip = "20|IMRL|halfCrimp"
    private var step = 0

    func testMeasureATenSecondMaxThenTargetASetAgainstIt() {
        let app = launchApp(arguments: ["-seedRoutine", "-seedHistory", "-mockDevice",
                                        "-tab", "2", "-weightUnit", "kg"])
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["maxes.card.\(grip)"].waitForExistence(timeout: 5))
        shot(app, "Benchmarks before")

        // CHOOSE — the kind of max is settled before the gauge screen opens.
        tap(app.buttons["maxes.card.\(grip)"], in: app)
        tap(app.buttons["maxes.measure.\(grip)"], in: app)
        let peak = app.buttons["max.chooser.peak"]
        XCTAssertTrue(peak.waitForExistence(timeout: 3))
        XCTAssertTrue(peak.isSelected, "Nothing is waiting on a timed max yet: Peak")
        shot(app, "Chooser opens on Peak")
        app.buttons["max.chooser.other"].tap()
        XCTAssertTrue(element("max.chooser.dial", in: app).waitForExistence(timeout: 3))
        shot(app, "Other shows the dial")
        app.buttons["max.chooser.10"].tap()
        shot(app, "Chooser on 10 s")
        app.buttons["max.mode.hands"].tap()

        // MEASURE — the screen states the length; it no longer changes it.
        let length = element("max.measure.length", in: app)
        XCTAssertTrue(length.waitForExistence(timeout: 5))
        XCTAssertEqual(length.value as? String, "10 second max")
        shot(app, "Measuring a 10 s max")

        // The demo gauge pulls 11.5 s of every 30: catch the countdown mid-pull.
        let hero = element("max.measure.hero", in: app)
        expectation(for: NSPredicate(format: "label BEGINSWITH %@", "Pulling"), evaluatedWith: hero)
        waitForExpectations(timeout: 40)
        Thread.sleep(forTimeInterval: 4)
        shot(app, "Mid-pull countdown")

        let left = app.buttons["max.measure.left"]
        expectation(for: NSPredicate(format: "value CONTAINS %@", "best of"), evaluatedWith: left)
        waitForExpectations(timeout: 30)
        shot(app, "Pull logged as a 10 s average")

        tap(app.buttons["max.measure.save"], in: app)
        let save = app.buttons["max.review.save"]
        XCTAssertTrue(save.waitForExistence(timeout: 3))
        shot(app, "Review of 10 s averages")
        save.tap()
        let done = app.buttons["max.receipt.done"]
        if done.waitForExistence(timeout: 3) {
            shot(app, "Save receipt")
            done.tap()
        }
        let timed = element("maxes.timed.\(grip).10.left", in: app)
        XCTAssertTrue(timed.waitForExistence(timeout: 5))
        shot(app, "Benchmarks with a 10 s max")

        // PRESCRIBE — the routine's first set at 80–100 % of that 10 s max.
        app.tabBars.buttons["Today"].tap()
        tap(app.buttons["routine.overview.open"].firstMatch, in: app)
        tap(app.buttons["routine.overview.edit"], in: app)
        let sets = app.buttons["Sets"].firstMatch
        XCTAssertTrue(sets.waitForExistence(timeout: 5))
        sets.tap()
        let firstSet = app.buttons.matching(NSPredicate(
            format: "label BEGINSWITH %@", "20 mm edge, 4 fingers, half crimp")).firstMatch
        tap(firstSet, in: app)
        let target = app.buttons["Target load"].firstMatch
        tap(target, in: app)
        tap(app.buttons["80–100 %"].firstMatch, in: app)
        let basis = app.buttons["10 s max"].firstMatch
        reveal(basis, in: app)
        shot(app, "Target editor offers Peak or 10 s max")
        basis.tap()
        XCTAssertTrue(basis.isSelected)
        shot(app, "Set targets 80-100 % of the 10 s max")
        tap(target, in: app)
        shot(app, "Closed row names the max")

        tap(app.buttons["Save"].firstMatch, in: app)
        Thread.sleep(forTimeInterval: 1.5)
        shot(app, "After save")

        // The routine now waits on a 10 s max the right hand lacks: Measure opens on it.
        let close = app.buttons["routine.overview.close"]
        if close.waitForExistence(timeout: 2) { close.tap() }
        app.tabBars.buttons["Benchmarks"].tap()
        if !app.buttons["maxes.measure.\(grip)"].exists { tap(app.buttons["maxes.card.\(grip)"], in: app) }
        tap(app.buttons["maxes.measure.\(grip)"], in: app)
        let tenAgain = app.buttons["max.chooser.10"]
        XCTAssertTrue(tenAgain.waitForExistence(timeout: 3))
        XCTAssertTrue(tenAgain.isSelected, "A routine waiting on a 10 s max opens the chooser on 10 s")
        shot(app, "Chooser opens on the length the routine needs")
        app.buttons["max.mode.hands"].tap()
        let right = app.buttons["max.measure.right"]
        XCTAssertTrue(right.waitForExistence(timeout: 5))
        XCTAssertTrue(right.isSelected, "The left has its 10 s max; the visit starts on the right")
        shot(app, "Measure starts on the hand that is missing it")
    }

    // MARK: - Helpers

    /// Attached to the result AND written beside the build, numbered in flow order.
    private func shot(_ app: XCUIApplication, _ name: String) {
        step += 1
        let label = String(format: "%02d %@", step, name)
        attachScreenshot(app, name: label)
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("build/timed-max-shots")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent(label.replacingOccurrences(of: " ", with: "-") + ".png")
        try? app.screenshot().pngRepresentation.write(to: file)
    }

    private func tap(_ element: XCUIElement, in app: XCUIApplication) {
        reveal(element, in: app, attempts: 10, bidirectional: true)
        Thread.sleep(forTimeInterval: 0.6)
        element.tap()
    }
}
