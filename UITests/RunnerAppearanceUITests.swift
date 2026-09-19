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

        // Skipping under the mock's load still respects LET GO before the rest begins,
        // so the wait covers the release as well as the skip itself.
        //
        // The seeded routine rests 20 s, and a rest that long is the REST-FOCUS layout:
        // the panel names the next hand, grip and position, and the compact prompt and
        // counters are `.hidden()` — which takes them out of the accessibility tree as
        // well as out of the drawing. So live progress is read where the screen states
        // it, not from the elements the compact layout used to carry.
        let restFocus = element("runner.restFocus", in: app)
        XCTAssertTrue(restFocus.waitForExistence(timeout: 25), app.debugDescription)
        let hand = element("runner.restFocus.hand", in: app)
        let pullCount = element("runner.restFocus.pullCount", in: app)
        waitFor(hand, NSPredicate(format: "label == %@", "Right hand next"), timeout: 5)
        let position = pullCount.label
        XCTAssertTrue(position.contains("Pull 2 of "), position)

        // This first switch happens during an actively counting rest, matching an
        // automatic sunset appearance change rather than an app background/relaunch.
        device.appearance = .dark
        XCTAssertEqual(app.state, .runningForeground)
        XCTAssertEqual(hand.label, "Right hand next")
        XCTAssertEqual(pullCount.label, position)
        attachScreenshot(app, name: "Live rest survives light to dark")

        let pause = app.buttons["runner.pause"]
        pause.tap()
        let phase = element("runner.restFocus.phase", in: app)
        waitFor(phase, NSPredicate(format: "label CONTAINS %@", "PAUSED"), timeout: 3)
        for dark in [false, true] {
            device.appearance = dark ? .dark : .light
            XCTAssertEqual(app.state, .runningForeground)
            XCTAssertTrue(phase.label.contains("PAUSED"), phase.label)
            XCTAssertEqual(hand.label, "Right hand next")
            XCTAssertEqual(pullCount.label, position)
            XCTAssertTrue(pause.label.contains("Resume"), pause.label)
            XCTAssertTrue(app.buttons["runner.end"].isHittable)
        }

        pause.tap()
        // Clock values are intentionally hidden from VoiceOver in the measured runner,
        // so the proof that the real rest ticker resumed is reaching the next pull at
        // all: the rest has to run itself down and hand the screen back to the pull
        // layout. A session whose onDisappear called end() remains stuck here forever.
        let prompt = element("runner.prompt", in: app)
        waitFor(prompt, NSPredicate(format: "label CONTAINS %@", "RIGHT"), timeout: 30)
        XCTAssertFalse(restFocus.exists, "the pull layout replaces the rest panel")
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
        let app = launchApp(arguments: ["-seedRoutine", "-mockDevice",
                                        "-UIPreferredContentSizeCategoryName",
                                        "UICTContentSizeCategoryL"])
        dismissTour(in: app)
        let start = app.buttons["Connect and start"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        start.tap()
        XCTAssertTrue(app.buttons["runner.end"].waitForExistence(timeout: 10))
        dismissTour(in: app)
        return app
    }
}
