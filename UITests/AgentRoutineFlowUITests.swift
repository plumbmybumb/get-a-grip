// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import UIKit
import XCTest

/// "Create with AI" end to end: copy the instructions, paste something that is not a
/// routine, then an AI's reply, check the preview names what it changed and the fine
/// tuning, and add it. Every step is a screenshot under build/agent-routine-shots.
@MainActor
final class AgentRoutineFlowUITests: XCTestCase {
    private var step = 0

    private let reply = """
    Copy the code block and paste it into Get a Grip.

    ```json
    {
      "format": "get-a-grip-routine/1",
      "name": "Recovery 7:3",
      "hands": "alternate",
      "holdSeconds": 7,
      "restSeconds": 3,
      "setBreakSeconds": 120,
      "sessionsPerDay": 1,
      "target": {"percentOfMax": [20, 30], "max": "peak"},
      "fineTuning": {"pauseTheClock": "below-range", "startRestWhenILetGo": false},
      "sets": [
        {"edgeMm": 40, "fingers": "4", "grip": "half-crimp", "pulls": 6},
        {"edgeMm": 20, "fingers": "front-3", "grip": "drag", "pulls": 6},
        {"edgeMm": 140, "fingers": "4", "grip": "open-hand", "pulls": 4, "target": {"percentOfMax": [60, 70], "max": 10}}
      ]
    }
    ```
    """

    func testPasteAnAIReplyPreviewItAndAddIt() {
        let app = launchApp(arguments: ["-mockDevice"])
        defer { app.terminate() }

        let open = app.buttons["today.createWithAI"]
        XCTAssertTrue(open.waitForExistence(timeout: 5))
        shot(app, "Today offers Create with AI")
        open.tap()

        let copy = app.buttons["agent.copy"]
        XCTAssertTrue(copy.waitForExistence(timeout: 3))
        shot(app, "The two steps")
        copy.tap()
        // Not read back from the pasteboard: a test runner reading another app's copy
        // raises iOS's paste-permission alert, which blocks the run.
        XCTAssertEqual(copy.label, "Copied")
        shot(app, "Instructions copied")

        // Not a routine: the sheet says so and stays.
        UIPasteboard.general.string = "Sounds good! Want me to write the routine now?"
        app.buttons["agent.paste"].tap()
        XCTAssertTrue(element("agent.failure", in: app).waitForExistence(timeout: 3))
        shot(app, "A reply with no routine in it")

        UIPasteboard.general.string = reply
        app.buttons["agent.paste"].tap()

        let add = app.buttons["Add to my routines"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["CHANGED TO FIT THE APP"].exists)
        XCTAssertTrue(app.staticTexts["Set 3 · Edge: 140 → 100"].exists)
        shot(app, "Preview with what changed")
        reveal(app.staticTexts["FINE TUNING"], in: app, attempts: 6)
        XCTAssertTrue(app.staticTexts["The clock pauses only below the target range"].exists)
        shot(app, "Preview fine tuning")
        add.tap()

        let card = app.staticTexts["Recovery 7:3"].firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        shot(app, "The routine on Today")
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        step += 1
        let label = String(format: "%02d %@", step, name)
        attachScreenshot(app, name: label)
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("build/agent-routine-shots")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent(label.replacingOccurrences(of: " ", with: "-") + ".png")
        try? app.screenshot().pngRepresentation.write(to: file)
    }
}
