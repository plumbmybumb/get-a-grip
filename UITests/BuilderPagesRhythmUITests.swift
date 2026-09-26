// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// The builder's one-screen Rhythm page and a set's Custom timing: each timing row is ONE
/// adjustable VoiceOver element, so the − and + are found by position inside it, which is
/// also where a thumb finds them.
@MainActor
final class BuilderPagesRhythmUITests: XCTestCase {
    private func launch(_ extra: [String] = ["-builderPage", "1"]) -> XCUIApplication {
        launchApp(arguments: ["-seedTwoRoutines", "-mockDevice", "-previewBuilderNew"] + extra)
    }

    private func row(_ title: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(
            format: "label == %@ AND value ENDSWITH %@", title, "seconds")).firstMatch
    }

    /// − | value | + from the row's trailing edge: 44 · ≥64 · 44.
    private func tap(_ part: String, of row: XCUIElement) {
        let frame = row.frame
        let x: CGFloat = switch part {
        case "plus": frame.maxX - 22
        case "value": frame.maxX - 44 - 32
        default: frame.maxX - 44 - 64 - 22
        }
        row.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: x - frame.minX, dy: frame.height / 2))
            .tap()
    }

    func testLadderStepsTheDialsOwnStopsTypesAndRepeats() throws {
        let app = launch()
        defer { app.terminate() }
        let hold = row("Hold", in: app)
        XCTAssertTrue(hold.waitForExistence(timeout: 8))
        XCTAssertEqual(hold.value as? String, "10 seconds")

        // The ladder, not ±1: 10 → 12 → 15, and back down through 7.
        tap("plus", of: hold)
        XCTAssertEqual(hold.value as? String, "12 seconds")
        tap("plus", of: hold)
        XCTAssertEqual(hold.value as? String, "15 seconds")
        tap("minus", of: hold)
        tap("minus", of: hold)
        tap("minus", of: hold)
        XCTAssertEqual(hold.value as? String, "7 seconds")

        // Typed values are not snapped, and step to their nearest neighbour.
        tap("value", of: hold)
        // The field opens EMPTY with the current value as its placeholder.
        let field = app.textFields.matching(NSPredicate(format: "placeholderValue == %@", "7")).firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 3))
        field.typeText("22")
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(hold.waitForExistence(timeout: 3))
        XCTAssertEqual(hold.value as? String, "22 seconds")
        tap("minus", of: hold)
        XCTAssertEqual(hold.value as? String, "20 seconds")

        // Held, it repeats up the ladder and stops at the top stop.
        let start = hold.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: hold.frame.width - 22, dy: hold.frame.height / 2))
        start.press(forDuration: 2.0)
        XCTAssertEqual(hold.value as? String, "60 seconds")

        // The floor: rest bottoms out at 0 and stays there.
        let rest = row("Rest between pulls", in: app)
        for _ in 0..<12 { tap("minus", of: rest) }
        XCTAssertEqual(rest.value as? String, "0 seconds")

        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Off, a set follows the Rhythm page; on, it gets its own steppers seeded from the
    /// routine; its closed row then says so; off again, it follows once more.
    func testCustomTimingOverridesOneSetAndTurnsBackOff() throws {
        let app = launch(["-builderSeedSets", "2", "-builderPage", "2", "-builderExpandSet", "1"])
        defer { app.terminate() }
        let toggle = app.switches["Custom timing"].firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 8))
        XCTAssertEqual(toggle.value as? String, "0")
        XCTAssertFalse(row("Hold", in: app).exists, "Off, the set shows no timing of its own")

        toggle.switches.firstMatch.tap()
        let hold = row("Hold", in: app)
        XCTAssertTrue(hold.waitForExistence(timeout: 3))
        XCTAssertEqual(hold.value as? String, "10 seconds", "Seeded from the routine")
        tap("plus", of: hold)
        XCTAssertEqual(hold.value as? String, "12 seconds")

        let header = app.buttons.matching(NSPredicate(
            format: "label BEGINSWITH %@", "20 mm edge, 4 fingers, half crimp.")).firstMatch
        XCTAssertTrue(header.label.contains("12 second hold"), header.label)

        toggle.switches.firstMatch.tap()
        XCTAssertTrue(hold.waitForNonExistence(timeout: 3))
        XCTAssertFalse(header.label.contains("second hold"), header.label)
    }
}
