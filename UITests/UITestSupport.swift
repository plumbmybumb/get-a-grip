// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest

/// The scaffolding every UI test file needs, in one place.
///
/// Launching, finding an element by identifier, scrolling something into reach,
/// attaching a screenshot and waiting on a predicate were written out again in each
/// file — seven screenshot helpers under three names, five `reveal`s. The copies drifted, and a drifting copy is not free: one of them
/// passed `-AppleLocale "fr"`, which is not a locale, so the French screenshots it took
/// were formatting numbers and dates for the POSIX default the whole time.

// MARK: - Locale

enum UITestLanguage {
    /// `-AppleLanguages` takes a language, `-AppleLocale` takes a LOCALE — the pair is
    /// what makes a launch French all the way down. Passing the language to both leaves
    /// formatting on the root locale while the UI translates, which looks right in a
    /// screenshot and proves nothing about the thing the test set out to check.
    static func locale(for language: String) -> String {
        switch language {
        case "en": "en_US"
        case "fr": "fr_FR"
        default: "\(language)_\(language.uppercased())"
        }
    }
}

// MARK: - Shared helpers

extension XCTestCase {

    /// Launch the app in `language`, with `arguments` on top of the language pair.
    ///
    /// `continueAfterFailure = false` because a UI test that keeps going after its first
    /// failure spends minutes driving a screen that is already wrong, and buries the one
    /// failure that mattered under the ten it caused.
    @discardableResult
    func launchApp(arguments: [String] = [], language: String = "en") -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = arguments + ["-AppleLanguages", "(\(language))",
                                           "-AppleLocale", UITestLanguage.locale(for: language)]
        app.launch()
        return app
    }

    /// The element carrying `identifier`, whatever kind of element it turned out to be.
    /// Most of what these tests measure is a container or a label, not a button.
    func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Scroll `element` into reach, then assert it got there.
    ///
    /// A bounded loop, never a `while`: a control that cannot be reached must fail the
    /// test, not spin until the runner's own timeout with nothing to say about why.
    ///
    /// - Parameters:
    ///   - scroller: the view to swipe. The app itself unless the content that has to
    ///     move is inside a particular scroll view — a swipe on the app can land on the
    ///     wrong one when a sheet is up.
    ///   - attempts: kept per call where a file's own bound is load-bearing.
    ///   - bidirectional: also scroll BACK when the element sits above the viewport.
    ///     What the max-measurement sheet needs, where a previous step may have left the
    ///     target off the top; elsewhere an unrealized frame reads as (0, 0) and the
    ///     backwards swipe would be the wrong way.
    ///
    /// **Existence is waited for, never asserted.** A row far down a long list does not
    /// exist in the accessibility tree until the scroll builds it — the French overview's
    /// sixth set at accessibility text is exactly that — so a precondition on existence
    /// fails before the scroll that would satisfy it. The hittable assertion at the end
    /// covers both: nothing that does not exist is hittable, and the element's own
    /// description says which it was.
    func reveal(_ element: XCUIElement, in app: XCUIApplication,
                scrolling scroller: XCUIElement? = nil,
                attempts: Int = 8, bidirectional: Bool = false,
                file: StaticString = #filePath, line: UInt = #line) {
        let surface = scroller ?? app
        _ = element.waitForExistence(timeout: 5)
        // **On screen but not yet hittable is an element still APPEARING** — the launch
        // fade, a card's stagger, a sheet settling — so wait for it rather than swipe.
        // Swiping then flung Settings' weight units, the third card and in plain view, off
        // the TOP of the page, and every later swipe only pushed it further: the suite's
        // WeightUnitsUITests failure under load, with the button at y = -230. Bounded, and
        // only for a realized frame wholly on screen, so a row below the fold pays nothing.
        if !element.isHittable, element.exists, !element.frame.isEmpty,
           app.frame.contains(element.frame) {
            _ = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"),
                                                                object: element)],
                               timeout: 3)
        }
        for _ in 0..<attempts where !element.isHittable {
            // A realized frame wholly above the viewport is reached by scrolling BACK,
            // whatever `bidirectional` says: another swipe up can only lose it further.
            // (An unrealized frame is empty, so it never reads as "above".)
            let above = element.exists && !element.frame.isEmpty && element.frame.maxY <= app.frame.minY
            if above || (bidirectional && element.exists && element.frame.minY < app.frame.midY) {
                surface.swipeDown()
            } else {
                surface.swipeUp()
            }
        }
        XCTAssertTrue(element.isHittable, element.debugDescription, file: file, line: line)
    }

    /// Keep a screenshot with the results. These are the record of what a layout claim
    /// actually looked like, so `.keepAlways` rather than "on failure".
    func attachScreenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Wait for `predicate` to hold of `element`, and fail with the element's own
    /// description if it never does.
    ///
    /// The bounded alternative to a `Thread.sleep`: a sleep long enough to be reliable on
    /// a loaded machine is time every passing run pays, and a sleep short enough not to
    /// hurt is the one that makes the suite flaky. A `block` predicate covers what no
    /// element property can express — a frame settling after an animation.
    func waitFor(_ element: XCUIElement, _ predicate: NSPredicate, timeout: TimeInterval,
                 file: StaticString = #filePath, line: UInt = #line) {
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: timeout), .completed,
                       element.debugDescription, file: file, line: line)
    }
}
