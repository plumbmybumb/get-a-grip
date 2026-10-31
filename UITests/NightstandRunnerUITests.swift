// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// Nightstand mode on iPhone: the LIVE runner turns with the phone and becomes the wide
/// layout — the iPad's, at a phone's scale: the trace is the screen, the panel and the
/// dock share one column on the leading side; everything else stays portrait
/// (docs/IPHONE_DUO_NIGHTSTAND.md).
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
        // device). `devicectl device orientation set` gets one turn in and then sticks.
        // The Duo is screenshotted by hand instead — docs/IPHONE_DUO_NIGHTSTAND.md — and
        // the layout is proved here on an ordinary iPhone.
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

    /// The wide shape: the panel and the dock in ONE column on the leading side, panel
    /// above dock, the open graph trailing them. The dock's accessibility frame is its
    /// buttons, inset by the glass's 8 pt padding — so "same column" is containment
    /// within that inset, not equal edges.
    private func columnBesideGraph(panel: XCUIElement, dock: XCUIElement,
                                   graph: XCUIElement) -> Bool {
        let p = panel.frame, d = dock.frame, g = graph.frame
        return d.minX >= p.minX - 1 && d.maxX <= p.maxX + 1 && d.width >= p.width - Self.dockInset * 2 - 1
            && p.maxY <= d.minY && d.maxX <= g.minX
    }

    /// The dock glass's padding around its buttons (`RunnerView.dockSpacing`).
    private static let dockInset: CGFloat = 8

    /// The laid-out runner of a phone on its side, asserted whole: one leading column,
    /// panel top and dock bottom, nothing clipped by the window, every dock action
    /// hittable, and the open graph running to the trailing safe edge.
    private func assertWideColumn(_ app: XCUIApplication, panel: XCUIElement, name: String,
                                  file: StaticString = #filePath, line: UInt = #line) {
        let window = app.windows.firstMatch.frame
        let dock = element("runner.dock", in: app)
        let column = element("runner.column", in: app)
        let graph = element("runner.graph", in: app)
        let report = "window \(window), column \(column.frame), panel \(panel.frame), "
            + "dock \(dock.frame), graph \(graph.frame)"
        XCTAssertTrue(columnBesideGraph(panel: panel, dock: dock, graph: graph),
                      "\(name): panel over dock in a column beside the graph — \(report)",
                      file: file, line: line)
        XCTAssertLessThan(dock.frame.midX, window.midX,
                          "\(name): the column is on the LEADING side — \(report)", file: file, line: line)
        // Fits at the default text size: inside the column (no scrolling to reach the
        // dock) and inside the window (nothing clipped at either edge).
        XCTAssertGreaterThanOrEqual(panel.frame.minY, column.frame.minY - 1,
                                    "\(name): panel top-aligned in the column — \(report)", file: file, line: line)
        XCTAssertLessThanOrEqual(dock.frame.maxY, column.frame.maxY + 1,
                                 "\(name): dock inside the column, no scroll — \(report)", file: file, line: line)
        XCTAssertLessThanOrEqual(column.frame.maxY - dock.frame.maxY, Self.dockInset + 2,
                                 "\(name): dock bottom-aligned — \(report)", file: file, line: line)
        XCTAssertTrue(window.contains(panel.frame) && window.contains(dock.frame),
                      "\(name): nothing clipped — \(report)", file: file, line: line)
        for id in ["runner.pause", "runner.skipPull", "runner.skipSet", "runner.end"] {
            XCTAssertTrue(app.buttons[id].isHittable, "\(name): \(id) hittable", file: file, line: line)
        }
        // The graph keeps the room right of the column: its trailing edge is the
        // screen's trailing safe edge, where the curve's newest seconds run in the clear.
        XCTAssertGreaterThan(graph.frame.width, window.width * 0.3,
                             "\(name): the open graph keeps a third of the width — \(report)",
                             file: file, line: line)
    }

    /// On its side the runner is the wide layout — and upright again it is the stack.
    func testSidewaysRunnerIsTheWideLayout() throws {
        let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines",
                                        "-previewRunnerWorking", "-previewRunnerTarget",
                                        "-exposeRunnerTrace", "-dumpRunnerLayout",
                                        "-UIPreferredContentSizeCategoryName",
                                        "UICTContentSizeCategoryL"])
        let pause = app.buttons["runner.pause"]
        XCTAssertTrue(pause.waitForExistence(timeout: 10))
        let panel = element("runner.panel", in: app)
        let dock = element("runner.dock", in: app)
        let graph = element("runner.graph", in: app)

        XCUIDevice.shared.orientation = .landscapeLeft
        // The column's own shape — which also proves the content turned, since a
        // counter-rotated stack would put the dock BELOW the graph in these coordinates.
        let wide = eventually(10) {
            drawnLandscape(app, wideControl: pause)
                && columnBesideGraph(panel: panel, dock: dock, graph: graph)
        }
        XCTAssertTrue(wide, "wide: panel and dock in a column beside the graph — "
                      + "panel \(panel.frame), dock \(dock.frame), graph \(graph.frame)")
        assertWideColumn(app, panel: panel, name: "working")

        // The trace is the SCREEN: its canvas runs edge to edge, under the column and
        // the system's side columns alike.
        let trace = element("runner.trace", in: app)
        XCTAssertTrue(trace.waitForExistence(timeout: 5))
        let window = app.windows.firstMatch.frame
        XCTAssertLessThanOrEqual(trace.frame.minX, window.minX + 1, "trace reaches the leading edge — \(trace.frame)")
        XCTAssertGreaterThanOrEqual(trace.frame.maxX, window.maxX - 1, "trace reaches the trailing edge — \(trace.frame)")
        XCTAssertLessThanOrEqual(trace.frame.minX, panel.frame.minX, "the curve's history runs under the glass")
        attachScreen("Sideways runner — the wide layout")

        XCUIDevice.shared.orientation = .portrait
        let upright = eventually(10) {
            let window = app.windows.firstMatch.frame
            return window.height > window.width && pause.frame.minY >= graph.frame.maxY
        }
        let uprightReport = "pause \(pause.frame), graph \(graph.frame)"
        attachScreen("Upright again — the stack")
        XCTAssertTrue(upright, "upright: the stack again — \(uprightReport)")
    }

    /// The wide layout holds its shape through a REST (the rest summary inside the same
    /// panel, the dock unmoved) and with NO GAUGE (timer-only: the identity and counts
    /// in the column, the dial where the graph was) — the two other states a tented
    /// phone spends time in.
    func testWideLayoutHoldsThroughRestAndTimerOnly() throws {
        for (fixture, name) in [(["-previewRunnerRest"], "rest"),
                                (["-previewRunnerWorking", "-previewRunnerTimer"], "timer-only")] {
            XCUIDevice.shared.orientation = .portrait
            let app = launchApp(arguments: ["-mockDevice", "-seedTwoRoutines",
                                            "-UIPreferredContentSizeCategoryName",
                                            "UICTContentSizeCategoryL"] + fixture)
            let pause = app.buttons["runner.pause"]
            XCTAssertTrue(pause.waitForExistence(timeout: 10))
            let panel = element("runner.panel", in: app)
            let dock = element("runner.dock", in: app)
            let graph = element("runner.graph", in: app)
            XCUIDevice.shared.orientation = .landscapeLeft
            let wide = eventually(10) {
                guard drawnLandscape(app, wideControl: pause) else { return false }
                if name == "rest" {
                    return columnBesideGraph(panel: panel, dock: dock, graph: graph)
                }
                // Timer-only has no glass panel and no `runner.graph` (the dial stands in
                // for the trace): the dock sits on the leading side.
                return dock.frame.midX < app.windows.firstMatch.frame.midX
            }
            XCTAssertTrue(wide, "\(name): the column on the leading side — dock \(dock.frame)")
            if name == "rest" {
                assertWideColumn(app, panel: panel, name: name)
            } else {
                let window = app.windows.firstMatch.frame
                XCTAssertTrue(window.contains(dock.frame), "\(name): dock not clipped — \(dock.frame)")
                XCTAssertTrue(app.buttons["runner.end"].isHittable, "\(name): hold to end stays reachable")
            }
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
