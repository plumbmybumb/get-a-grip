// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import XCTest
@testable import Doigt

/// The typed-number contract, lifted out of the view body when the field became its own
/// leaf so a keypress stops rebuilding the dial above it.
///
/// Every rule here is one this app has already paid for once, and the reason they are
/// pinned now is that they moved: parsing used to live inside `ValueRow.commit()`, where
/// nothing could reach it.
@MainActor
final class ValueFieldTests: XCTestCase {

    /// An UNTOUCHED field must report nothing at all. Tapping a number to change it and
    /// then changing your mind has to leave the value alone — a commit that read an empty
    /// string as zero would silently wipe the row.
    func testAnUntouchedFieldParsesToNothing() {
        XCTAssertNil(ValueField.parse("", decimals: 0))
        XCTAssertNil(ValueField.parse("   ", decimals: 0))
    }

    /// Not a number is not an edit — the same rule, for a field somebody pasted into.
    func testNonsenseParsesToNothingRatherThanZero() {
        XCTAssertNil(ValueField.parse("abc", decimals: 1))
        XCTAssertNil(ValueField.parse("-", decimals: 1))
    }

    /// The same phone reads "2,5" in French and "2.5" in English, and a keypad does not
    /// care which one you were taught.
    func testACommaReadsAsADecimalPoint() {
        XCTAssertEqual(ValueField.parse("2,5", decimals: 1), 2.5)
        XCTAssertEqual(ValueField.parse("2.5", decimals: 1), 2.5)
    }

    func testSurroundingWhitespaceIsIgnored() {
        XCTAssertEqual(ValueField.parse("  22 ", decimals: 0), 22)
    }

    /// **Typing is NOT snapped to the control's step.** The dial lands on the ladder so a
    /// round number is easy to reach by dragging; the field is the escape hatch for
    /// everything else, and one that turns a typed 7 into 5 is not an escape hatch.
    /// 22 mm is the standing example — an edge no ladder in the app offers.
    func testATypedValueOffTheLadderSurvivesIntact() {
        XCTAssertEqual(ValueField.parse("22", decimals: 0), 22)
        XCTAssertEqual(ValueField.parse("7", decimals: 0), 7)
    }

    /// Only the DISPLAY precision is enforced: whole numbers for seconds and millimetres,
    /// one decimal for kilograms.
    func testOnlyDisplayPrecisionIsEnforced() {
        XCTAssertEqual(ValueField.parse("12.6", decimals: 0), 13)
        XCTAssertEqual(ValueField.parse("12.64", decimals: 1), 12.6)
        XCTAssertEqual(ValueField.rounded(12.649, decimals: 1), 12.6)
        XCTAssertEqual(ValueField.rounded(12.65, decimals: 1), 12.7)
    }
}
