// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// Nightstand mode on iPhone: the LIVE runner turns with the phone and becomes the far
/// view; everything else stays portrait (docs/IPHONE_DUO_NIGHTSTAND.md).
///
/// `XCUIDevice.orientation` is the rotation seam every simulator honours, so this is
/// where the orientation lock is proved rather than in a screenshot taken by hand.
/// The rotation is put back in `tearDown` for the reason `WideLayoutUITests` gives: a
/// device left sideways is inherited by every class that runs after this one.
///
/// **How "turned" is measured.** XCUI reports frames in the coordinate space the system
/// gives the window, which is not always the one the content is drawn in — a portrait
/// app inside a landscape scene is drawn counter-rotated, and its wide buttons come back
/// TALL. So the tests never trust the window's shape alone: the UI is landscape only
/// when the window is wide AND a known-wide control is wide too.
@MainActor
final class NightstandRunnerUITests: XCTestCase {

    override func setUp() async throws {
        continueAfterFailure = false
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        try XCTSkipIf(isPad, "iPad already turns everywhere; the lock is an iPhone rule")
        // The iPhone Duo simulator (iOS 27.1) boots CLOSED and never delivers an
        // `XCUIDevice` rotation to its outer display: the cover stays portrait and the
        // app is never turned (measured 2026-10-31, all three tests on a freshly booted
        // device). `devicectl device orientation set` gets one turn in and then sticks. The far view is screenshotted there by hand instead —
        // docs/IPHONE_DUO_NIGHTSTAND.md — and proved here on an ordinary iPhone.
        let simulatorName = ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? ""
        try XCTSkipIf(simulatorName.contains("Duo"),
                      "the Duo simulator does not deliver XCUIDevice rotation to its outer display")
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDown() async throws {
        XCUIDevice.shared.orientation = .portrait
    }

    /// Poll `condition` on the main thread until it holds or `timeout` passes. Each
    /// `frame` read inside it is a fresh snapshot of the app.
    private func eventually(_ timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        return condition()
    }

    /// The whole SCREEN, not `app.screenshot()`: on a turned simulator the app's own
    /// capture came back cropped to a portrait-shaped slice of the landscape window.
    private func attachScreen(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The UI is drawn in landscape: the window is wide and `wideControl`, a control
    /// that is wider than tall in every layout, still reads wide.
    private func drawnLandscape(_ app: XCUIApplication, wideControl: XCUIElement) -> Bool {
        let window = app.windows.firstMatch.frame
        let control = wideControl.frame
        return window.width > window.height && control.width > control.height
    }

    /// On its side the runner is the far view: the face (numeral + word) leading, the
    /// dock in a column on the trailing edge — and upright again it is the stack.
    func testSidewaysRunnerIsTheFarView() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines",
                                        "-previewRunnerWorking", "-previewRunnerTarget",
                                        "-dumpRunnerLayout"])
        let pause = app.buttons["runner.pause"]
        XCTAssertTrue(pause.waitForExistence(timeout: 10))
        let face = element("runner.graph", in: app)
        let hero = element("runner.hero", in: app)

        XCUIDevice.shared.orientation = .landscapeLeft
        // The far view's own shape — which also proves the content turned, since a
        // counter-rotated stack would put the dock BELOW the graph in these coordinates.
        let far = eventually(10) {
            drawnLandscape(app, wideControl: pause) && pause.frame.minX >= face.frame.maxX
        }
        let farReport = "pause \(pause.frame), face \(face.frame)"
        XCTAssertTrue(far, "far view: the dock is a column beside the face — \(farReport)")
        let reachable = pause.isHittable && app.buttons["runner.end"].isHittable
        XCTAssertTrue(reachable, "pause and hold to end stay reachable")
        let numeral = hero.frame.height, window = app.windows.firstMatch.frame.height
        XCTAssertGreaterThan(numeral, window * 0.4, "the numeral takes the face's height")
        attachScreen("Sideways runner — the far view")

        XCUIDevice.shared.orientation = .portrait
        let upright = eventually(10) {
            let window = app.windows.firstMatch.frame
            return window.height > window.width && pause.frame.minY >= face.frame.maxY
        }
        let uprightReport = "pause \(pause.frame), face \(face.frame)"
        attachScreen("Upright again — the stack")
        XCTAssertTrue(upright, "upright: the stack again — \(uprightReport)")
    }

    /// The far view holds its shape through a REST (the numeral is the rest countdown,
    /// the word names the next hand) and with NO GAUGE (timer-only: no kilograms, no
    /// trace, the same face) — the two other states a tented phone spends time in.
    func testFarViewHoldsThroughRestAndTimerOnly() throws {
        for (fixture, name) in [(["-previewRunnerRest"], "rest"),
                                (["-previewRunnerWorking", "-previewRunnerTimer"], "timer-only")] {
            XCUIDevice.shared.orientation = .portrait
            let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines"] + fixture)
            let pause = app.buttons["runner.pause"]
            XCTAssertTrue(pause.waitForExistence(timeout: 10))
            let face = element("runner.graph", in: app)
            XCUIDevice.shared.orientation = .landscapeLeft
            let far = eventually(10) {
                drawnLandscape(app, wideControl: pause) && pause.frame.minX >= face.frame.maxX
            }
            let report = "pause \(pause.frame), face \(face.frame)"
            XCTAssertTrue(far, "\(name): the dock is a column beside the face — \(report)")
            let endReachable = app.buttons["runner.end"].isHittable
            XCTAssertTrue(endReachable, "\(name): hold to end stays reachable")
            attachScreen("Sideways runner — \(name)")
            app.terminate()
        }
    }

    /// Today never turns; the runner does, even when the phone was ALREADY on its side
    /// when it opened (the tent pose: Start tapped on a sideways Today); finishing hands
    /// portrait back BEFORE the summary, so neither the summary nor the Today it returns
    /// to is ever drawn sideways.
    func testOnlyTheLiveRunnerTurns() throws {
        let app = launchApp(arguments: ["-seedRoutine", "-mockDevice",
                                        "-UIPreferredContentSizeCategoryName",
                                        "UICTContentSizeCategoryL"])
        let start = app.buttons["routine.start"].firstMatch
        XCTAssertTrue(start.waitForExistence(timeout: 10))

        XCUIDevice.shared.orientation = .landscapeLeft
        // Give a wrong rotation the time it would take, then insist it did not happen.
        let todayTurned = eventually(3) { drawnLandscape(app, wideControl: start) }
        XCTAssertFalse(todayTurned, "Today is never drawn in landscape")
        attachScreen("Today with the phone on its side")

        start.tap()
        let end = app.buttons["runner.end"]
        XCTAssertTrue(end.waitForExistence(timeout: 10))
        let runnerTurned = eventually(10) { drawnLandscape(app, wideControl: end) }
        XCTAssertTrue(runnerTurned, "the runner turns to the phone it opened on")
        attachScreen("Runner opened on its side")

        // Real work first, so the summary offers Save rather than only Discard: the first
        // pull (LEFT) or the rest after it — the mock can be past LEFT by the first poll.
        waitFor(element("runner.prompt", in: app),
                NSPredicate(format: "label == %@ OR label CONTAINS %@", "LEFT", "NEXT"),
                timeout: 40)
        end.press(forDuration: 1.1)
        let save = app.buttons["Save and finish"]
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        let summaryTurned = eventually(3) { drawnLandscape(app, wideControl: save) }
        XCTAssertFalse(summaryTurned, "the summary is portrait")
        attachScreen("Summary is portrait")

        reveal(save, in: app)
        save.tap()
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        let todayTurnedAfter = eventually(2) { drawnLandscape(app, wideControl: start) }
        XCTAssertFalse(todayTurnedAfter, "back on Today, still portrait")
        attachScreen("Today after the sideways session")
    }
}
