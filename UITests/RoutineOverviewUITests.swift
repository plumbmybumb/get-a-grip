// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

@MainActor
final class RoutineOverviewUITests: XCTestCase {
    func testMixedRoutineOverviewShowsTheSavedPlanInOrder() {
        let app = launchSeededApp()
        defer { app.terminate() }
        openOverview(in: app)
        let scroll = app.scrollViews["routine.overview.scroll"]
        XCTAssertTrue(scroll.waitForExistence(timeout: 3))
        let duration = element("routine.overview.duration", in: app)
        XCTAssertTrue(duration.waitForExistence(timeout: 3))
        // Six lead-ins, 36 ten-second pulls, 30 twenty-second rests and five
        // one-minute set breaks: 1,290 seconds, not just the hold time.
        XCTAssertTrue(spokenText(duration).contains("21 min 30 s"), spokenText(duration))
        let totals = element("routine.overview.totals", in: app)
        XCTAssertTrue(spokenText(totals).contains("6 sets · 36 pulls total"), spokenText(totals))
        XCTAssertTrue(app.staticTexts["Lead-in before each set"].exists)
        XCTAssertTrue(app.staticTexts["5 s"].exists)
        capture(app, name: "Routine overview duration and hand order")

        let first = element("routine.overview.set.0", in: app)
        reveal(first, scrolling: scroll)
        let firstText = spokenText(first).lowercased()
        XCTAssertTrue(firstText.contains("20 mm"), firstText)
        XCTAssertTrue(firstText.contains("4 fingers"), firstText)
        XCTAssertTrue(firstText.contains("half crimp"), firstText)
        XCTAssertTrue(firstText.contains("6 per side · 12 pulls total"), firstText)
        XCTAssertTrue(firstText.contains("10 s"), firstText)
        XCTAssertTrue(firstText.contains("20 s"), firstText)
        XCTAssertTrue(firstText.contains("18–22 % of max"), firstText)

        let last = element("routine.overview.set.5", in: app)
        reveal(last, scrolling: scroll)
        let lastText = spokenText(last).lowercased()
        XCTAssertTrue(lastText.contains("10 mm"), lastText)
        XCTAssertTrue(lastText.contains("full crimp"), lastText)
        XCTAssertTrue(lastText.contains("1 per side · 2 pulls total"), lastText)
        XCTAssertFalse(element("routine.overview.set.6", in: app).exists)
        XCTAssertEqual(app.textFields.count, 0, "The overview is read-only")
        XCTAssertFalse(app.buttons["Increase Pulls per side"].exists)
        capture(app, name: "Routine overview mixed-grip plan")
    }

    func testOverviewClosesToTodayAndEditOpensTheExistingBuilder() {
        let app = launchSeededApp()
        defer { app.terminate() }
        openOverview(in: app)
        let close = app.buttons["routine.overview.close"]
        XCTAssertTrue(close.waitForExistence(timeout: 3))
        close.tap()
        XCTAssertTrue(close.waitForNonExistence(timeout: 3))
        XCTAssertTrue(app.tabBars.buttons["Today"].isSelected)
        XCTAssertTrue(visibleOverviewButton(in: app).isHittable)

        openOverview(in: app)
        app.buttons["routine.overview.edit"].tap()
        XCTAssertTrue(app.navigationBars["Edit routine"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["routine.overview.close"].exists)
        let name = app.textFields["Daily no-hangs"]
        XCTAssertTrue(name.waitForExistence(timeout: 3))
        XCTAssertEqual(name.value as? String, "Daily no-hangs",
                       "Edit must preserve the selected routine rather than create another")
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.buttons["routine.overview.open"].firstMatch.waitForExistence(timeout: 3))
    }

    func testConsistencyOpensHistoryAndLogStillOpensItsOwnSheet() {
        let app = launchSeededApp()
        defer { app.terminate() }
        let history = app.buttons["consistency.history"]
        revealOnToday(history, in: app)
        history.tap()
        XCTAssertTrue(app.navigationBars["History"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.tabBars.buttons["History"].isSelected)
        XCTAssertFalse(app.navigationBars["Log a session"].exists)

        app.tabBars.buttons["Today"].tap()
        let log = app.buttons["consistency.log"]
        revealOnToday(log, in: app)
        log.tap()
        XCTAssertTrue(app.navigationBars["Log a session"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.scrollViews["sessionLog.form"].exists)
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Log a session"].waitForNonExistence(timeout: 3))
        XCTAssertTrue(app.tabBars.buttons["Today"].isSelected)
    }

    func testFrenchOverviewKeepsThePlanAndActionsReachableAtAccessibilityText() {
        let app = launchSeededApp(language: "fr", locale: "fr_FR",
                                  category: "UICTContentSizeCategoryAccessibilityM")
        defer { app.terminate() }
        openOverview(in: app)
        let edit = app.buttons["routine.overview.edit"]
        let close = app.buttons["routine.overview.close"]
        XCTAssertTrue(edit.isHittable)
        XCTAssertTrue(close.isHittable)
        assertHorizontalFit(edit, in: app)
        assertHorizontalFit(close, in: app)

        let duration = element("routine.overview.duration", in: app)
        XCTAssertTrue(duration.isHittable)
        assertHorizontalFit(duration, in: app)
        let totals = element("routine.overview.totals", in: app)
        XCTAssertTrue(spokenText(totals).contains("36"), spokenText(totals))
        capture(app, name: "French AX1 routine overview — header and native actions")

        let scroll = app.scrollViews["routine.overview.scroll"]
        let first = element("routine.overview.set.0", in: app)
        reveal(first, scrolling: scroll)
        revealWholeRow(first, scrolling: scroll)
        assertHorizontalFit(first, in: app)
        for text in first.staticTexts.allElementsBoundByIndex {
            assertHorizontalFit(text, in: app)
        }
        XCTAssertTrue(edit.isHittable, "Edit must stay reachable while the plan scrolls")
        capture(app, name: "French AX1 routine overview — full first set")

        let last = element("routine.overview.set.5", in: app)
        reveal(last, scrolling: scroll)
        XCTAssertTrue(spokenText(last).contains("10 mm"), spokenText(last))
        XCTAssertTrue(close.isHittable)
        close.tap()
        XCTAssertTrue(close.waitForNonExistence(timeout: 3))
        XCTAssertTrue(app.buttons["routine.overview.open"].firstMatch.exists)
    }

    private func launchSeededApp(language: String = "en", locale: String = "en_US",
                                 category: String? = nil) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-seedTwoRoutines", "-seedHistory", "-mockDevice",
                               "-AppleLanguages", "(\(language))", "-AppleLocale", locale,
                               "-tour.seen.intro", "1", "-tour.seen.builder", "1"]
        if let category {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", category]
        }
        app.launch()
        let skip = app.buttons["Skip"]
        if skip.waitForExistence(timeout: 2) { skip.tap() }
        XCTAssertTrue(app.buttons["routine.overview.open"].firstMatch.waitForExistence(timeout: 5))
        return app
    }

    private func openOverview(in app: XCUIApplication) {
        let open = visibleOverviewButton(in: app)
        revealOnToday(open, in: app)
        open.tap()
        XCTAssertTrue(app.buttons["routine.overview.edit"].waitForExistence(timeout: 3))
    }

    private func visibleOverviewButton(in app: XCUIApplication) -> XCUIElement {
        let candidates = app.buttons.matching(identifier: "routine.overview.open")
        XCTAssertTrue(candidates.firstMatch.waitForExistence(timeout: 5))
        let window = app.frame
        // The carousel exposes adjacent pages to accessibility, and its query order
        // can differ at larger text sizes. Choose the page inside the window before
        // attempting vertical scrolling; a swipe up cannot reveal a side page.
        let visible = candidates.allElementsBoundByIndex.first { button in
            let frame = button.frame
            return frame.width > 0 && frame.minX >= window.minX && frame.maxX <= window.maxX
        }
        XCTAssertNotNil(visible, "The current carousel page must expose its overview action")
        return visible ?? candidates.firstMatch
    }

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func spokenText(_ element: XCUIElement) -> String {
        ([element.label, element.value as? String ?? ""]
            + element.staticTexts.allElementsBoundByIndex.map(\.label))
            .joined(separator: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    }

    private func reveal(_ element: XCUIElement, scrolling scroll: XCUIElement) {
        for _ in 0..<8 where !element.isHittable { scroll.swipeUp() }
        XCTAssertTrue(element.isHittable, element.debugDescription)
    }

    private func revealWholeRow(_ row: XCUIElement, scrolling scroll: XCUIElement) {
        // A row can become hittable with only its heading visible. Move its bottom
        // into the viewport so the screenshot also verifies the multiline details.
        for _ in 0..<5 where row.frame.maxY > scroll.frame.maxY - 16 {
            let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
            let distance = min(row.frame.maxY - scroll.frame.maxY + 24, scroll.frame.height * 0.4)
            start.press(forDuration: 0.01,
                        thenDragTo: start.withOffset(CGVector(dx: 0, dy: -distance)))
        }
        XCTAssertGreaterThanOrEqual(row.frame.minY, scroll.frame.minY)
        XCTAssertLessThanOrEqual(row.frame.maxY, scroll.frame.maxY)
    }

    private func assertHorizontalFit(_ element: XCUIElement, in app: XCUIApplication) {
        XCTAssertGreaterThan(element.frame.width, 0, element.label)
        XCTAssertGreaterThanOrEqual(element.frame.minX, app.frame.minX, element.label)
        XCTAssertLessThanOrEqual(element.frame.maxX, app.frame.maxX, element.label)
    }

    private func revealOnToday(_ element: XCUIElement, in app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 5))
        for _ in 0..<5 where !element.isHittable { app.swipeUp() }
        XCTAssertTrue(element.isHittable)
    }

    private func capture(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
