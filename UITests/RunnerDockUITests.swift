// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// **The dock** — the five runner actions on one glass surface (2026-09-19) — and the
/// stacked order it belongs to: panel, then graph, then dock.
///
/// Why this is worth a test of its own: an accessibility identifier on the dock's bare
/// container made SwiftUI COMBINE its children into a single element, and every button
/// inside it vanished from the tree at once. Sixteen tests could not find Pause, and the
/// fix — `.accessibilityElement(children: .contain)` — is one modifier away from being
/// lost again by anybody rearranging that row. Nothing else asserts that the dock
/// CONTAINS the actions rather than merely sitting where they used to be.
@MainActor
final class RunnerDockUITests: XCTestCase {

    func testTheDockContainsEveryRunnerActionAndEachStaysTappable() {
        let app = launchApp(arguments: ["-mockDevice", "-previewRunnerWorking"])
        defer { app.terminate() }

        let dock = element("runner.dock", in: app)
        XCTAssertTrue(dock.waitForExistence(timeout: 10), app.debugDescription)
        for identifier in ["runner.pause", "runner.skipPull", "runner.skipSet", "runner.end"] {
            // Queried INSIDE the dock: `app.buttons[…]` would keep passing if the
            // buttons escaped the surface they are supposed to share.
            let button = dock.buttons[identifier]
            XCTAssertTrue(button.waitForExistence(timeout: 5), identifier)
            XCTAssertTrue(button.isHittable, identifier)
            // The house floor, on the one screen where a mistap costs a workout.
            XCTAssertGreaterThanOrEqual(button.frame.height, 44, identifier)
            XCTAssertTrue(dock.frame.contains(button.frame), "\(identifier) is outside the dock")
        }
        attachScreenshot(app, name: "The runner dock during a pull")
    }

    /// Top to bottom on a phone: the identity panel, the open graph, the dock. The panel
    /// floats OVER the trace, so "above" is a claim about the stacked layout's order,
    /// not about which view draws the background.
    func testThePanelSitsAboveTheGraphAndTheDockBelowIt() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad,
                      "the wide layout puts the panel and dock in a column beside the graph")
        let app = launchApp(arguments: ["-mockDevice", "-previewRunnerWorking"])
        defer { app.terminate() }

        let panel = element("runner.panel", in: app)
        let graph = element("runner.graph", in: app)
        let dock = element("runner.dock", in: app)
        XCTAssertTrue(panel.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(graph.waitForExistence(timeout: 5))
        XCTAssertTrue(dock.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(graph.frame.height, 0, "the graph is the room between them")
        XCTAssertLessThanOrEqual(panel.frame.maxY, graph.frame.minY,
                                 "stacked: the panel is above the graph")
        XCTAssertGreaterThanOrEqual(dock.frame.minY, graph.frame.maxY,
                                    "stacked: the dock is under the graph")
        attachScreenshot(app, name: "Stacked runner — panel, graph, dock")
    }
}
