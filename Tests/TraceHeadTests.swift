// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import XCTest
@testable import Doigt

/// The point being written never steps: `TraceHead` glides toward a pending point and,
/// when the buffer runs dry, holds where the glide had arrived. The 120 ms running average
/// it replaced stepped the head twice per packet on a real radio (2026-09-19).
final class TraceHeadTests: XCTestCase {
    private let period = 0.0125
    private func ramp(count: Int, slope: Double = 20) -> [DeviceStore.TracePoint] {
        (0..<count).map { DeviceStore.TracePoint(kg: slope * period * Double($0), t: period * Double($0)) }
    }

    func testTheHeadGlidesTowardThePendingPoint() {
        let s = ramp(count: 10)
        let kg = TraceHead.edgeKg(samples: s, lastDue: 4, now: 4.5 * period, smoothed: { s[$0].kg })
        XCTAssertEqual(try XCTUnwrap(kg), 20 * period * 4.5, accuracy: 1e-9)
    }

    func testADryBufferHoldsWhereTheGlideArrived() throws {
        let s = ramp(count: 10)
        let justBefore = try XCTUnwrap(TraceHead.edgeKg(samples: s, lastDue: 8, now: 9 * period - 1e-6, smoothed: { s[$0].kg }))
        let justAfter = try XCTUnwrap(TraceHead.edgeKg(samples: s, lastDue: 9, now: 9 * period + 1e-6, smoothed: { s[$0].kg }))
        XCTAssertEqual(justBefore, justAfter, accuracy: 1e-3, "no step at the moment the last point comes due")
        XCTAssertEqual(justAfter, s[9].kg)
        let later = try XCTUnwrap(TraceHead.edgeKg(samples: s, lastDue: 9, now: 9 * period + 0.3, smoothed: { s[$0].kg }))
        XCTAssertEqual(later, s[9].kg, "held, never extrapolated or averaged")
    }

    func testAStoppedStreamHasNoSyntheticHead() {
        let s = ramp(count: 10)
        XCTAssertNil(TraceHead.edgeKg(samples: s, lastDue: 9, now: 9 * period + 0.6, smoothed: { s[$0].kg }),
                     "half a second of silence and the trace slides away")
    }
}
