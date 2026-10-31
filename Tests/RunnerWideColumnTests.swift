// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import XCTest
@testable import Doigt

/// The wide layout's column on the windows it was measured on (docs/IPHONE_DUO_NIGHTSTAND.md
/// §2): an iPad keeps its 440 pt column at 1.4; a phone on its side gets the same layout
/// at the scale its HEIGHT holds, and no margin of our own beside a system column.
@MainActor
final class RunnerWideColumnTests: XCTestCase {
    /// The Duo's outer display on its side, bars trailing: 678 × 466, insets 0/0/34/84.
    func testDuoOuterDisplayOnItsSide() {
        let column = RunnerView.wideColumn(size: CGSize(width: 594, height: 432),
                                           insets: EdgeInsets(top: 0, leading: 0, bottom: 34, trailing: 84),
                                           compactHeight: true, typeSize: .large)
        XCTAssertEqual(column.width, 356, "60 % of the 594 pt safe width")
        XCTAssertEqual(column.leadingPadding, 20, "a bare side keeps the house margin")
        XCTAssertEqual(column.trailingPadding, 0, "the bars already are the margin")
        XCTAssertEqual(column.scale, 1, "412 pt of column holds 1.0 (396), not 1.1 (416)")
        XCTAssertEqual(column.scrollHeight, 412)
        XCTAssertEqual(column.headClearance, 92, "the head stays out of the bars' column")
    }

    /// The other landscape puts the bars on the LEADING side: the column sits flush
    /// against them, the trace's head gets the ordinary 8 pt.
    func testDuoMirroredKeepsNoMarginBesideTheBars() {
        let column = RunnerView.wideColumn(size: CGSize(width: 594, height: 432),
                                           insets: EdgeInsets(top: 0, leading: 84, bottom: 34, trailing: 0),
                                           compactHeight: true, typeSize: .large)
        XCTAssertEqual(column.leadingPadding, 0)
        XCTAssertEqual(column.trailingPadding, 20)
        XCTAssertEqual(column.headClearance, 8)
    }

    /// A 17 Pro Max on its side is REGULAR width and compact height (956 × 440, island
    /// insets 62 both sides) — the height class, not the width, picks the phone's scale.
    func testProMaxOnItsSideIsAPhoneNotAnIPad() {
        let column = RunnerView.wideColumn(size: CGSize(width: 832, height: 420),
                                           insets: EdgeInsets(top: 0, leading: 62, bottom: 20, trailing: 62),
                                           compactHeight: true, typeSize: .large)
        XCTAssertEqual(column.width, 440)
        XCTAssertEqual(column.scale, 1, "400 pt of column holds 1.0 only")
        XCTAssertEqual(column.leadingPadding, 0)
        XCTAssertEqual(column.trailingPadding, 0)
    }

    /// The iPad is unchanged: 440 wide, 1.4, the house paddings, no scroll view.
    func testIPadLandscapeIsUnchanged() {
        let column = RunnerView.wideColumn(size: CGSize(width: 1210, height: 790),
                                           insets: EdgeInsets(top: 24, leading: 0, bottom: 20, trailing: 0),
                                           compactHeight: false, typeSize: .large)
        XCTAssertEqual(column.width, 440)
        XCTAssertEqual(column.scale, 1.4)
        XCTAssertEqual(column.leadingPadding, 20)
        XCTAssertEqual(column.trailingPadding, 20)
        XCTAssertNil(column.scrollHeight)
    }

    /// The scale steps up only where the measured panel, gap and dock fit — and never
    /// above the default text size, where the type has already grown.
    func testPhoneScaleIsTheLargestStepThatFits() {
        XCTAssertEqual(RunnerView.phoneScale(columnHeight: 395, typeSize: .large), 1)
        XCTAssertEqual(RunnerView.phoneScale(columnHeight: 396, typeSize: .large), 1)
        XCTAssertEqual(RunnerView.phoneScale(columnHeight: 416, typeSize: .large), 1.1)
        XCTAssertEqual(RunnerView.phoneScale(columnHeight: 436, typeSize: .large), 1.2)
        XCTAssertEqual(RunnerView.phoneScale(columnHeight: 600, typeSize: .large), 1.2)
        XCTAssertEqual(RunnerView.phoneScale(columnHeight: 600, typeSize: .xLarge), 1)
    }
}
