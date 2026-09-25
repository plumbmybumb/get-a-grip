// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// "New routine": the chooser, a protocol's preview, and the add that makes it a card.
@MainActor
final class NewRoutineUITests: XCTestCase {

    func testAProtocolIsPreviewedThenAddedAsARoutine() {
        let app = launchApp(arguments: ["-seedNoRoutines", "-mockDevice"])
        defer { app.terminate() }
        dismissTour(in: app)

        let door = app.buttons["Start from a known protocol"]
        XCTAssertTrue(door.waitForExistence(timeout: 5))
        door.tap()

        let rehab = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Finger rehab"))
            .firstMatch
        XCTAssertTrue(rehab.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Build from scratch"))
            .firstMatch.exists)
        attachScreenshot(app, name: "chooser")

        rehab.tap()
        let add = app.buttons["Add to my routines"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["After Hooper's Beta"].exists)
        attachScreenshot(app, name: "preview")

        add.tap()
        // The chooser closes with the add, and Today shows the new routine.
        XCTAssertTrue(app.staticTexts["Finger rehab"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(add.exists)
        attachScreenshot(app, name: "added")
    }

    func testBuildFromScratchOpensTheBuilderAfterTheChooserCloses() {
        let app = launchApp(arguments: ["-seedNoRoutines", "-mockDevice"])
        defer { app.terminate() }
        dismissTour(in: app)

        let door = app.buttons["Start from a known protocol"]
        XCTAssertTrue(door.waitForExistence(timeout: 5))
        door.tap()
        let scratch = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Build from scratch"))
            .firstMatch
        XCTAssertTrue(scratch.waitForExistence(timeout: 5))
        scratch.tap()

        XCTAssertTrue(app.buttons["Save"].firstMatch.waitForExistence(timeout: 5))
        attachScreenshot(app, name: "builder")
    }
}
