// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

@MainActor
final class EffortPickerTests: XCTestCase {
    func testWholeSlotsAndOvershootsChooseTheSameRungs() {
        for width: CGFloat in [180, 300, 440] {
            for index in 0..<5 {
                XCTAssertEqual(EffortPicker.level(at: (CGFloat(index) + 0.5) * width / 5, width: width), index + 1)
            }
            XCTAssertEqual(EffortPicker.level(at: -100, width: width), 1)
            XCTAssertEqual(EffortPicker.level(at: width + 100, width: width), 5)
        }
        XCTAssertEqual(EffortPicker.level(at: 59, width: 300), 1)
        XCTAssertEqual(EffortPicker.level(at: 60, width: 300), 2)
    }

    func testInvalidGeometryAndHugeCoordinatesCannotTrap() {
        for x: CGFloat in [.nan, .infinity, -.infinity] {
            XCTAssertEqual(EffortPicker.level(at: x, width: 300), 1)
        }
        for width: CGFloat in [0, -1, .nan, .infinity] {
            XCTAssertEqual(EffortPicker.level(at: 150, width: width), 1)
        }
        XCTAssertEqual(EffortPicker.level(at: .greatestFiniteMagnitude, width: 300), 5)
        XCTAssertEqual(EffortPicker.level(at: -.greatestFiniteMagnitude, width: 300), 1)
        XCTAssertEqual(EffortPicker.level(at: 100, width: 300, count: 0), 1)
    }

    func testLadderRisesFromFortyToOneHundredPercentWithoutSelectionResizing() {
        let heights = (0..<5).map { EffortPicker.height(at: $0, tallest: 44) }
        XCTAssertEqual(heights.first!, 17.6, accuracy: 0.001)
        XCTAssertEqual(heights.last!, 44, accuracy: 0.001)
        for (a, b) in zip(heights, heights.dropFirst()) { XCTAssertLessThan(a, b) }
        XCTAssertEqual(EffortPicker.height(at: 0, count: 1, tallest: 44), 44)
    }
}
