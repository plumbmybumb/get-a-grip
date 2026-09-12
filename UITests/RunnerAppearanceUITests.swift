// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// Exercise the real Today presentation and live mock stream. The screenshot runner
/// fixtures intentionally stop their clock and cannot catch a session being ended
/// when appearance changes. XCUIDevice.appearance is a public XCTest API (iOS 15+),
/// so the app stays foreground and no private selector or production hook is needed.
@MainActor
final class RunnerAppearanceUITests: XCTestCase {
    func testAppearanceChangesKeepLiveProgressAndPausedRestCanResume() throws {
        let device = XCUIDevice.shared
        let originalAppearance = device.appearance
        device.appearance = .light
        let app = startLiveMockSession()
        defer {
            app.terminate()
            device.appearance = originalAppearance
        }

        let skip = app.buttons["runner.skipPull"]
        waitFor(skip, NSPredicate(format: "enabled == YES"), timeout: 10)
        skip.tap()
        let prompt = element("runner.prompt", in: app)
        // Skipping under the mock's load still respects LET GO before rest begins.
        waitFor(prompt, NSPredicate(format: "label == %@", "RIGHT HAND NEXT"), timeout: 15)
        let counters = element("runner.counters", in: app)
        let position = positionText(counters.label)
        XCTAssertTrue(position.contains("pull 2 of"), counters.label)
        let upcomingGrip = counters.label

        // This first switch happens during an actively counting rest, matching an
        // automatic sunset appearance change rather than an app background/relaunch.
        device.appearance = .dark
        XCTAssertEqual(app.state, .runningForeground)
        XCTAssertEqual(prompt.label, "RIGHT HAND NEXT")
        XCTAssertEqual(counters.label, upcomingGrip)
        attachScreenshot(app, name: "Live rest survives light to dark")

        let pause = app.buttons["runner.pause"]
        pause.tap()
        waitFor(prompt, NSPredicate(format: "label == %@", "PAUSED"), timeout: 3)
        let pausedState = counters.label
        for dark in [false, true] {
            device.appearance = dark ? .dark : .light
            XCTAssertEqual(app.state, .runningForeground)
            XCTAssertEqual(prompt.label, "PAUSED")
            XCTAssertEqual(counters.label, pausedState)
            XCTAssertTrue(pause.label.contains("Resume"), pause.label)
            XCTAssertTrue(app.buttons["runner.end"].isHittable)
        }

        pause.tap()
        waitFor(prompt, NSPredicate(format: "label == %@", "RIGHT HAND NEXT"), timeout: 3)
        XCTAssertEqual(positionText(counters.label), position)
        // Clock values are intentionally hidden from VoiceOver in the measured
        // runner. Reaching the upcoming pull proves the real rest ticker resumed;
        // a session whose onDisappear called end() remains stuck here forever.
        waitFor(prompt,
                NSPredicate(format: "label CONTAINS %@ AND label != %@", "RIGHT", "RIGHT HAND NEXT"),
                timeout: 25)
        XCTAssertEqual(positionText(counters.label), position)
        XCTAssertTrue(app.buttons["runner.end"].isHittable)
        attachScreenshot(app, name: "Live pull resumes after appearance changes")
    }

    func testAppearanceChangesKeepUnsavedLiveSessionSummarySelections() throws {
        let device = XCUIDevice.shared
        let originalAppearance = device.appearance
        device.appearance = .light
        let app = startLiveMockSession()
        defer {
            app.terminate()
            device.appearance = originalAppearance
        }

        let prompt = element("runner.prompt", in: app)
        waitFor(prompt, NSPredicate(format: "label == %@", "LEFT"), timeout: 40)
        // A deliberate hold also gives the live mock time to accrue real work. This
        // is an interrupted real session, not an injected summary data structure.
        app.buttons["runner.end"].press(forDuration: 1.1)
        let save = app.buttons["Save and finish"]
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        let effort = element("effort.overall", in: app)
        XCTAssertTrue(effort.waitForExistence(timeout: 5))
        effort.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)).tap()
        XCTAssertEqual(effort.value as? String, "Hard")
        let sets = app.buttons["summary.sets"]
        XCTAssertTrue(sets.isHittable)
        sets.tap()
        XCTAssertEqual(sets.value as? String, "Expanded")

        for dark in [true, false] {
            device.appearance = dark ? .dark : .light
            XCTAssertEqual(app.state, .runningForeground)
            XCTAssertTrue(save.isHittable)
            XCTAssertEqual(effort.value as? String, "Hard")
            XCTAssertEqual(sets.value as? String, "Expanded")
            XCTAssertFalse(app.buttons["runner.end"].exists)
            attachScreenshot(app, name: "Unsaved summary retains selections in \(dark ? "dark" : "light") mode")
        }
    }

    private func startLiveMockSession() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice", "-AppleLanguages", "(en)",
                               "-AppleLocale", "en_US", "-UIPreferredContentSizeCategoryName",
                               "UICTContentSizeCategoryL"]
        app.launch()
        dismissTour(in: app)
        let start = app.buttons["Connect and start"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        start.tap()
        XCTAssertTrue(app.buttons["runner.end"].waitForExistence(timeout: 10))
        dismissTour(in: app)
        return app
    }

    private func dismissTour(in app: XCUIApplication) {
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
    }

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func positionText(_ counters: String) -> String {
        counters.components(separatedBy: ", ").prefix(2).joined(separator: ", ")
    }

    private func waitFor(_ element: XCUIElement, _ predicate: NSPredicate, timeout: TimeInterval,
                         file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed,
                       element.debugDescription, file: file, line: line)
    }

    private func attachScreenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
