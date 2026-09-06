// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

@MainActor
final class EffortPickerTests: XCTestCase {
    func testBarCentersAndOutsideDragsMapToTheSameFiveLevels() {
        for width: CGFloat in [180, 300, 440] {
            let bar = min(EffortPicker.barWidth, width / 5)
            for index in 0..<5 {
                XCTAssertEqual(EffortPicker.level(at: bar / 2 + CGFloat(index) * (width - bar) / 4, width: width), index + 1)
            }
            XCTAssertEqual(EffortPicker.level(at: -100, width: width), 1)
            XCTAssertEqual(EffortPicker.level(at: width + 100, width: width), 5)
        }
    }
}
