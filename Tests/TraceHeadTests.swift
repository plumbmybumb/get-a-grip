// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

/// The head dot's target is the newest readings, lightly averaged — nothing older, nothing
/// invented. The easing toward it lives in the view.
final class TraceHeadTests: XCTestCase {
    private func points(_ kgs: [Double]) -> [DeviceStore.TracePoint] {
        kgs.enumerated().map { DeviceStore.TracePoint(kg: $0.element, t: 0.0125 * Double($0.offset)) }
    }

    func testTheHeadIsTheMeanOfTheNewestThreeReadings() throws {
        let s = points([0, 0, 0, 0, 9, 12, 15])
        XCTAssertEqual(try XCTUnwrap(TraceHead.newestKg(samples: s)), 12, accuracy: 1e-9)
    }

    func testFewerThanThreeReadingsAverageWhatThereIs() throws {
        XCTAssertEqual(try XCTUnwrap(TraceHead.newestKg(samples: points([4]))), 4, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(TraceHead.newestKg(samples: points([4, 6]))), 5, accuracy: 1e-9)
        XCTAssertNil(TraceHead.newestKg(samples: []))
    }
}
