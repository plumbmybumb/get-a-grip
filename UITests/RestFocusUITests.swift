// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
import UIKit

@MainActor
final class RestFocusUITests: XCTestCase {
    func testTenSecondRestShowsUpcomingGripAndHidesLiveWeight() {
        let app = launchRest(seconds: 10, extra: ["-previewRunnerTarget", "-previewWeightLb"])
        defer { app.terminate() }

        let identity = element("runner.restFocus", in: app)
        XCTAssertTrue(identity.waitForExistence(timeout: 5))
        let hand = element("runner.restFocus.hand", in: app)
        XCTAssertTrue(hand.label.localizedCaseInsensitiveContains("right"), hand.debugDescription)
        let grip = element("runner.restFocus.grip", in: app)
        XCTAssertTrue(grip.label.contains("20 mm"), grip.debugDescription)
        XCTAssertTrue(grip.label.localizedCaseInsensitiveContains("half crimp"), grip.debugDescription)
        XCTAssertFalse(grip.label.localizedCaseInsensitiveContains("new grip next"),
                       "An ordinary hand swap must not announce a changed grip")
        XCTAssertEqual(element("runner.restFocus.countdown", in: app).label, "10")
        let target = element("runner.restFocus.target", in: app)
        XCTAssertTrue(target.label.contains("8.8") && target.label.contains("17.6") && target.label.contains("lb"),
                      "Upcoming targets must retain the selected weight unit: \(target.label)")
        XCTAssertFalse(element("runner.hero", in: app).exists,
                       "The live weight must not compete with upcoming grip information during a long rest")
        assertRestSummaryAboveGraph(in: app)
        assertControlsVisible(in: app)
        screenshot(app, name: "Rest focus — next right hand and timer above the live graph")
    }

    func testRestSummaryKeepsGraphAndControlsAtOriginalSizeAndPosition() {
        let legacy = launchRest(seconds: 3, extra: ["-previewRunnerTarget"])
        XCTAssertTrue(element("runner.graph", in: legacy).waitForExistence(timeout: 5))
        let graphFrame = element("runner.graph", in: legacy).frame
        let pauseFrame = legacy.buttons["runner.pause"].frame
        let endFrame = legacy.buttons["runner.end"].frame
        XCTAssertGreaterThan(graphFrame.height, 160, "Measure the plot itself, not an empty accessibility proxy")
        screenshot(legacy, name: "Original rest layout — graph geometry baseline")
        legacy.terminate()

        let focused = launchRest(seconds: 10, extra: ["-previewRunnerTarget"])
        defer { focused.terminate() }
        XCTAssertTrue(element("runner.restFocus", in: focused).waitForExistence(timeout: 5))
        assertRestSummaryAboveGraph(in: focused)
        assertSameFrame(element("runner.graph", in: focused).frame, graphFrame, name: "Full-size live graph")
        assertSameFrame(focused.buttons["runner.pause"].frame, pauseFrame, name: "Pause")
        assertSameFrame(focused.buttons["runner.end"].frame, endFrame, name: "Hold to end")
        screenshot(focused, name: "Rest focus — same graph and control geometry")
    }

    func testLongestSetBreakAndTenThousandPullPlanFitAboveGraph() {
        let app = launchRest(seconds: 900, extra: ["-previewRunnerSetBreak", "-previewRunnerLargeCounts",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"])
        defer { app.terminate() }
        XCTAssertTrue(element("runner.restFocus", in: app).waitForExistence(timeout: 5))
        let countdown = element("runner.restFocus.countdown", in: app)
        XCTAssertEqual(countdown.label, "900")
        XCTAssertTrue(element("runner.restFocus.phase", in: app).label.contains("SET BREAK"))
        XCTAssertEqual(element("runner.restFocus.pullCount", in: app).label, "Pull 201 of 10,000")
        assertRestSummaryAboveGraph(in: app)
        for identifier in ["runner.restFocus.setCount", "runner.restFocus.pullCount"] {
            let count = element(identifier, in: app)
            XCTAssertFalse(count.frame.intersects(countdown.frame),
                           "\(identifier) must remain clear of the three-digit countdown on compact phones")
            XCTAssertGreaterThanOrEqual(count.frame.minX, app.frame.minX + 16, identifier)
            XCTAssertLessThanOrEqual(count.frame.maxX, app.frame.maxX - 16, identifier)
        }
        assertControlsVisible(in: app)
        screenshot(app, name: "Rest focus — 900-second set break and 10000 planned pulls")
    }

    func testShortRestsKeepTheExistingLayout() {
        for seconds in [3, 9] {
            let app = launchRest(seconds: seconds)
            XCTAssertTrue(element("runner.hero", in: app).waitForExistence(timeout: 5))
            XCTAssertFalse(element("runner.restFocus", in: app).exists)
            XCTAssertTrue(element("runner.counters", in: app).label.contains("REST"))
            assertControlsVisible(in: app)
            screenshot(app, name: "Short rest — compact layout")
            app.terminate()
        }
    }

    func testRestFocusLastsThroughFinalSecondsAndKeepsControlsAnchoredWhenPullResumes() {
        let app = launchRest(seconds: 10, extra: ["-previewRunnerPauseAtTwo"])
        defer { app.terminate() }
        let identity = element("runner.restFocus", in: app)
        XCTAssertTrue(identity.waitForExistence(timeout: 5))
        let graphFrame = element("runner.graph", in: app).frame
        let pauseFrame = app.buttons["runner.pause"].frame
        let endFrame = app.buttons["runner.end"].frame
        let countdown = element("runner.restFocus.countdown", in: app)
        let twoSeconds = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "2"),
                                                   object: countdown)
        XCTAssertEqual(XCTWaiter.wait(for: [twoSeconds], timeout: 20), .completed)
        XCTAssertTrue(identity.exists, "Scheduled ten-second rests keep the focus layout for their whole duration")
        assertRestSummaryAboveGraph(in: app)
        screenshot(app, name: "Rest focus — final two seconds")
        XCTAssertTrue(app.buttons["runner.pause"].label.contains("Resume"))
        app.buttons["runner.pause"].tap()
        XCTAssertTrue(identity.waitForNonExistence(timeout: 5), "The normal pull layout returns when rest finishes")
        assertControlsVisible(in: app)
        assertSameFrame(element("runner.graph", in: app).frame, graphFrame, name: "Live graph")
        assertSameFrame(app.buttons["runner.pause"].frame, pauseFrame, name: "Pause")
        assertSameFrame(app.buttons["runner.end"].frame, endFrame, name: "Hold to end")
        screenshot(app, name: "After rest — pull layout restored")
    }

    func testChangedGripStaysOrangeThroughFinalRestSecondsAndClearsWhenPullResumes() {
        let app = launchRest(seconds: 10, extra: ["-previewRunnerSetBreak", "-previewRunnerPauseAtTwo"])
        defer { app.terminate() }
        let identity = element("runner.restFocus", in: app)
        XCTAssertTrue(identity.waitForExistence(timeout: 5))
        screenshot(app, name: "Grip change — next hand and full grip above the live graph")
        let graphFrame = element("runner.graph", in: app).frame
        let pauseFrame = app.buttons["runner.pause"].frame
        let endFrame = app.buttons["runner.end"].frame
        let countdown = element("runner.restFocus.countdown", in: app)
        let twoSeconds = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "2"),
                                                   object: countdown)
        XCTAssertEqual(XCTWaiter.wait(for: [twoSeconds], timeout: 20), .completed)
        XCTAssertTrue(identity.exists)
        let grip = element("runner.restFocus.grip", in: app)
        XCTAssertTrue(grip.label.localizedCaseInsensitiveContains("new grip next"), grip.debugDescription)
        XCTAssertTrue(grip.label.contains("15 mm"), "The change must describe the upcoming grip")
        XCTAssertTrue(element("runner.restFocus.hand", in: app).label.localizedCaseInsensitiveContains("left"))
        XCTAssertTrue(element("runner.restFocus.phase", in: app).label.contains("PAUSED"))
        assertRestSummaryAboveGraph(in: app)
        XCTAssertGreaterThan(orangeFractionAtGraphEdge(in: app), 0.08,
                             "The orange graph outline must survive the initial animation and remain at two seconds")
        screenshot(app, name: "Grip change — enlarged orange glyph and outline at final two seconds")

        app.buttons["runner.pause"].tap()
        XCTAssertTrue(identity.waitForNonExistence(timeout: 5))
        assertControlsVisible(in: app)
        assertSameFrame(element("runner.graph", in: app).frame, graphFrame, name: "Live graph after grip change")
        assertSameFrame(app.buttons["runner.pause"].frame, pauseFrame, name: "Pause")
        assertSameFrame(app.buttons["runner.end"].frame, endFrame, name: "Hold to end")
        XCTAssertLessThan(orangeFractionAtGraphEdge(in: app), 0.01,
                          "The previous grip-change outline must clear when the next pull is ready")
        screenshot(app, name: "Grip change — emphasis clears when the next pull is ready")
    }

    func testShortChangedGripRestKeepsCompactCueAndOrangeGraphOutline() {
        let app = launchRest(seconds: 3, extra: ["-previewRunnerSetBreak"])
        defer { app.terminate() }
        XCTAssertTrue(element("runner.hero", in: app).waitForExistence(timeout: 5))
        XCTAssertFalse(element("runner.restFocus", in: app).exists,
                       "A short grip-change rest must keep the compact workout layout")
        XCTAssertTrue(element("runner.prompt", in: app).label.localizedCaseInsensitiveContains("left"))
        assertControlsVisible(in: app)
        XCTAssertGreaterThan(orangeFractionAtGraphEdge(in: app), 0.08,
                             "The original compact grip-change cue must retain its orange outline")
        screenshot(app, name: "Three-second grip change — original compact cue")
    }

    func testOldPreviewPreferenceCannotDisablePermanentRestLayout() {
        let app = launchRest(seconds: 10, extra: ["-restFocusPreviewEnabled", "NO"])
        defer { app.terminate() }
        XCTAssertTrue(element("runner.restFocus", in: app).waitForExistence(timeout: 5))
        XCTAssertFalse(element("runner.hero", in: app).exists)
    }

    func testSettingsDoesNotOfferTheRemovedPreviewSwitch() {
        let app = XCUIApplication()
        app.launchArguments = ["-seedRoutine", "-mockDevice", "-tab", "3",
                               "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        defer { app.terminate() }
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        for _ in 0..<8 {
            XCTAssertFalse(app.switches["settings.restFocusPreview"].exists)
            app.swipeUp()
        }
        XCTAssertFalse(app.staticTexts["Rest layout preview"].exists)
    }

    func testPausedRestAndLostConnectionRemainExplicit() {
        let paused = launchRest(seconds: 10, extra: ["-previewRunnerPaused"])
        XCTAssertTrue(element("runner.restFocus", in: paused).waitForExistence(timeout: 5))
        XCTAssertTrue(element("runner.restFocus.phase", in: paused).label.contains("PAUSED"))
        XCTAssertTrue(paused.buttons["runner.pause"].label.contains("Resume"))
        XCTAssertEqual(element("runner.restFocus.countdown", in: paused).label, "10")
        assertRestSummaryAboveGraph(in: paused)
        screenshot(paused, name: "Rest focus — paused")
        paused.terminate()

        let disconnected = launchRest(seconds: 10, extra: ["-previewRunnerSignalLost"])
        defer { disconnected.terminate() }
        XCTAssertTrue(element("runner.restFocus", in: disconnected).waitForExistence(timeout: 5))
        XCTAssertTrue(element("runner.signalWarning", in: disconnected).waitForExistence(timeout: 5))
        XCTAssertEqual(element("runner.restFocus.countdown", in: disconnected).label, "10",
                       "A lost gauge does not hide the independent rest countdown above the graph")
        assertRestSummaryAboveGraph(in: disconnected)
        let warningFrame = element("runner.signalWarning", in: disconnected).frame
        XCTAssertGreaterThanOrEqual(warningFrame.minY, element("runner.graph", in: disconnected).frame.minY,
                                    "Gauge recovery guidance stays in the graph, clear of the rest instruction")
        XCTAssertTrue(disconnected.buttons["Connect"].isHittable)
        screenshot(disconnected, name: "Rest focus — lost connection")
    }

    func testTimerOnlyLongRestKeepsTheTimingInterface() {
        let app = launchRest(seconds: 20, extra: ["-previewRunnerTimer"])
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["runner.pause"].waitForExistence(timeout: 5))
        XCTAssertFalse(element("runner.restFocus", in: app).exists)
        XCTAssertFalse(element("runner.graph", in: app).exists)
        XCTAssertTrue(app.buttons["runner.end"].isHittable)
        XCTAssertFalse(app.buttons["runner.tare"].exists)
        screenshot(app, name: "Timer-only rest — existing timing interface")
    }

    func testReleaseAndWorkingPhasesKeepTheNormalInterface() {
        for phase in ["-previewRunnerRelease", "-previewRunnerWorking"] {
            let app = launchRest(seconds: 10, phase: phase)
            XCTAssertTrue(app.buttons["runner.pause"].waitForExistence(timeout: 5))
            XCTAssertFalse(element("runner.restFocus", in: app).exists,
                           "Focus must never hide live pulling or LET GO information")
            if phase == "-previewRunnerRelease" {
                XCTAssertTrue(element("runner.prompt", in: app).label.contains("LET GO"))
            }
            assertControlsVisible(in: app)
            screenshot(app, name: phase == "-previewRunnerRelease" ? "Release — LET GO remains visible" : "Working — original measurements")
            app.terminate()
        }
    }

    func testDifferentGripDuringSetBreakIsReadableInFrenchAtLargeText() {
        for (category, scrolls) in [("UICTContentSizeCategoryXXXL", false),
                                    ("UICTContentSizeCategoryAccessibilityXXXL", true)] {
            let app = launchRest(seconds: 10, language: "fr", extra: ["-previewRunnerSetBreak",
                "-UIPreferredContentSizeCategoryName", category])
            XCTAssertTrue(element("runner.restFocus", in: app).waitForExistence(timeout: 5))
            let grip = element("runner.restFocus.grip", in: app)
            XCTAssertTrue(grip.label.contains("15 mm"), "The second set's new grip must replace the first set's 20 mm grip")
            XCTAssertTrue(element("runner.restFocus.hand", in: app).label.localizedCaseInsensitiveContains("gauche"))
            for identifier in ["runner.restFocus.hand", "runner.restFocus.grip"] {
                let label = element(identifier, in: app)
                XCTAssertGreaterThanOrEqual(label.frame.minX, app.frame.minX + 16, identifier)
                XCTAssertLessThanOrEqual(label.frame.maxX, app.frame.maxX - 16, identifier)
                XCTAssertFalse(label.label.contains("…"), identifier)
            }
            if !scrolls { assertRestSummaryAboveGraph(in: app) }
            screenshot(app, name: "Rest focus — French \(category), upcoming set")
            assertControlsVisible(in: app, allowsScrolling: scrolls)
            screenshot(app, name: "Rest focus — French \(category), controls")
            app.terminate()
        }
    }

    private func launchRest(seconds: Int, phase: String = "-previewRunnerRest",
                            language: String = "en", extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [phase, "-previewRunnerRestSeconds", String(seconds),
                               "-AppleLanguages", "(\(language))", "-AppleLocale", language == "fr" ? "fr_FR" : "en_US"] + extra
        app.launch()
        return app
    }

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func assertRestSummaryAboveGraph(in app: XCUIApplication,
                                             file: StaticString = #filePath, line: UInt = #line) {
        let graph = element("runner.graph", in: app)
        XCTAssertTrue(graph.waitForExistence(timeout: 3), file: file, line: line)
        let graphFrame = graph.frame
        XCTAssertGreaterThan(graphFrame.height, 0, file: file, line: line)
        let identifiers = ["runner.restFocus", "runner.restFocus.hand", "runner.restFocus.grip",
                           "runner.restFocus.phase", "runner.restFocus.countdown",
                           "runner.restFocus.setCount", "runner.restFocus.pullCount"]
        for identifier in identifiers {
            let content = element(identifier, in: app)
            XCTAssertTrue(content.exists, identifier, file: file, line: line)
            XCTAssertGreaterThan(content.frame.height, 0, identifier, file: file, line: line)
            XCTAssertLessThanOrEqual(content.frame.maxY, graphFrame.minY,
                                    "\(identifier) must fit entirely above the graph", file: file, line: line)
        }
        let target = element("runner.restFocus.target", in: app)
        if target.exists {
            XCTAssertLessThanOrEqual(target.frame.maxY, graphFrame.minY,
                                    "The upcoming target must not cover the graph", file: file, line: line)
        }
    }

    private func assertControlsVisible(in app: XCUIApplication, allowsScrolling: Bool = false,
                                       file: StaticString = #filePath, line: UInt = #line) {
        for identifier in ["runner.pause", "runner.tare", "runner.skipPull", "runner.skipSet", "runner.end"] {
            let button = app.buttons[identifier]
            XCTAssertTrue(button.waitForExistence(timeout: 3), identifier, file: file, line: line)
            if allowsScrolling {
                for _ in 0..<12 where !button.isHittable || button.frame.maxY > app.frame.maxY - 30 {
                    app.scrollViews["runner.content"].swipeUp()
                }
            }
            XCTAssertTrue(button.isHittable, identifier, file: file, line: line)
            XCTAssertGreaterThanOrEqual(button.frame.height, 44, identifier, file: file, line: line)
            XCTAssertGreaterThanOrEqual(button.frame.minX, app.frame.minX + 16, identifier, file: file, line: line)
            XCTAssertLessThanOrEqual(button.frame.maxX, app.frame.maxX - 16, identifier, file: file, line: line)
            XCTAssertLessThanOrEqual(button.frame.maxY, app.frame.maxY, identifier, file: file, line: line)
        }
    }

    private func assertSameFrame(_ actual: CGRect, _ previous: CGRect, name: String,
                                 file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(actual.minX, previous.minX, accuracy: 2, name, file: file, line: line)
        XCTAssertEqual(actual.minY, previous.minY, accuracy: 2, name, file: file, line: line)
        XCTAssertEqual(actual.width, previous.width, accuracy: 2, name, file: file, line: line)
        XCTAssertEqual(actual.height, previous.height, accuracy: 2, name, file: file, line: line)
    }

    /// Inspect the rendered straight left edge, away from rounded corners and trace
    /// content. Color is intentionally checked in pixels: the outline is decorative,
    /// so an accessibility flag would only prove state, not that the cue was visible.
    private func orangeFractionAtGraphEdge(in app: XCUIApplication,
                                            file: StaticString = #filePath, line: UInt = #line) -> Double {
        let graph = element("runner.graph", in: app).frame
        let appFrame = app.frame
        let region = CGRect(x: graph.minX, y: graph.minY + 36,
                            width: 6, height: max(0, graph.height - 72))
        guard region.height > 0, let image = app.screenshot().image.cgImage else {
            XCTFail("A graph screenshot is required to inspect its outline", file: file, line: line)
            return 0
        }
        let scaleX = CGFloat(image.width) / appFrame.width
        let scaleY = CGFloat(image.height) / appFrame.height
        let pixels = CGRect(x: (region.minX - appFrame.minX) * scaleX,
                            y: (region.minY - appFrame.minY) * scaleY,
                            width: region.width * scaleX, height: region.height * scaleY).integral
        guard let crop = image.cropping(to: pixels),
              let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) else {
            XCTFail("Could not crop the graph outline", file: file, line: line)
            return 0
        }
        let pixelCount = crop.width * crop.height
        var bytes = [UInt8](repeating: 0, count: pixelCount * 4)
        let orangeCount: Int? = bytes.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: crop.width, height: crop.height,
                                          bitsPerComponent: 8, bytesPerRow: crop.width * 4,
                                          space: colorSpace,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                                            | CGBitmapInfo.byteOrder32Big.rawValue) else { return nil }
            context.draw(crop, in: CGRect(x: 0, y: 0, width: crop.width, height: crop.height))
            let channels = buffer.bindMemory(to: UInt8.self)
            var count = 0
            for pixel in 0..<pixelCount {
                let red = Int(channels[pixel * 4])
                let green = Int(channels[pixel * 4 + 1])
                let blue = Int(channels[pixel * 4 + 2])
                if red >= 160, green >= 70, green <= 210, blue <= 130,
                   red >= green + 25, green >= blue + 20 { count += 1 }
            }
            return count
        }
        guard let orangeCount else {
            XCTFail("Could not inspect graph outline pixels", file: file, line: line)
            return 0
        }
        return Double(orangeCount) / Double(pixelCount)
    }

    private func screenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
