// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The width rules behind the unfolded iPhone Duo layouts (`docs/IPHONE_DUO.md`,
/// "Unfolded layouts", 2026-10-31). Widths are CONTENT widths: the window minus the
/// house 20 pt margins.
final class UnfoldedLayoutTests: XCTestCase {

    func testTheUnfoldedDuoGetsTwoCardsAPair() {
        // 669 on the simulator, 626 by the press figure.
        XCTAssertEqual(CardColumns.count(for: 669 - 40, accessibilitySize: false), 2)
        XCTAssertEqual(CardColumns.count(for: 626 - 40, accessibilitySize: false), 2)
    }

    func testAnIPadStillGetsTwoWideColumnsNeverThree() {
        XCTAssertEqual(CardColumns.count(for: Metrics.maxContentWidthGrid, accessibilitySize: false), 2)
        XCTAssertEqual(CardColumns.count(for: 1032 - 40, accessibilitySize: false), 2)
    }

    func testPhoneWidthsAndASplitViewHalfStayOneColumn() {
        XCTAssertEqual(CardColumns.count(for: 313 - 40, accessibilitySize: false), 1)
        XCTAssertEqual(CardColumns.count(for: 466 - 85 - 40, accessibilitySize: false), 1)
        XCTAssertEqual(CardColumns.count(for: Metrics.cardMinWidth * 2 + Metrics.spacing - 1,
                                         accessibilitySize: false), 1)
    }

    /// Big type in a 303 pt card wraps every line; the phone's 440 floor comes back, so
    /// the Duo goes to one card per row and an iPad keeps its pair.
    func testAccessibilitySizesNeedPhoneWidthCards() {
        XCTAssertEqual(CardColumns.count(for: 669 - 40, accessibilitySize: true), 1)
        XCTAssertEqual(CardColumns.count(for: Metrics.maxContentWidthGrid, accessibilitySize: true), 2)
    }

    func testHistoryGoesTwoPaneOnTheUnfoldedDuoInPortrait() {
        XCTAssertLessThanOrEqual(HistoryView.twoPanePortraitWidth, 626)
        XCTAssertGreaterThan(HistoryView.twoPanePortraitWidth, 466, "the closed outer display stays one list")
    }
}
