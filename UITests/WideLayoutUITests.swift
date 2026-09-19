// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// The iPad's two-column runner, checked on a rotated simulator.
///
/// `simctl` cannot rotate a device and iPadOS 26 refuses programmatic orientation
/// changes ("the current windowing mode does not allow" them), so the only headless way
/// to see the wide layouts is a UI test that turns the device and asks the accessibility
/// tree where things landed. The rotation outlives the test, which is what lets a
/// `simctl io screenshot` afterwards capture the same layouts by eye.
final class WideLayoutUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom != .pad,
                      "the wide runner is a regular-width, landscape layout")
    }

    /// Landscape iPad: the controls sit in the left column, the graph takes the right.
    func testWideRunnerPutsTheControlsBesideTheGraph() throws {
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-mockDevice", "-seedTwoRoutines",
                               "-previewRunnerWorking", "-previewRunnerTarget"]
        app.launch()

        let graph = app.otherElements["runner.graph"]
        let pause = app.buttons["runner.pause"]
        XCTAssertTrue(graph.waitForExistence(timeout: 10))
        XCTAssertTrue(pause.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(app.frame.width, app.frame.height, "the device is landscape")
        XCTAssertLessThanOrEqual(pause.frame.maxX, graph.frame.minX,
                                 "wide: controls left of the graph, not under it")
    }

    /// Portrait iPad keeps the phone's stack: the controls are UNDER the graph.
    func testStackedRunnerKeepsTheControlsUnderTheGraph() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-mockDevice", "-seedTwoRoutines",
                               "-previewRunnerWorking", "-previewRunnerTarget"]
        app.launch()

        let graph = app.otherElements["runner.graph"]
        let pause = app.buttons["runner.pause"]
        XCTAssertTrue(graph.waitForExistence(timeout: 10))
        XCTAssertTrue(pause.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(pause.frame.minY, graph.frame.maxY,
                                    "stacked: controls under the graph")
    }
}
