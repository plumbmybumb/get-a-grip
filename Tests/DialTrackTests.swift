// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import XCTest
@testable import Doigt

/// `renderingMark` is the dial's drawing decision, lifted out of the view body so the
/// contract can be asserted without a SwiftUI tree. The rule it pins: an UNANSWERED
/// scale draws nothing at all — no highlighted detent, no hollow off-ladder mark.
///
/// That matters because the obvious implementation of "unset" is a sentinel below the
/// ladder, and `offLadderX` parks such a value on the FIRST stop — so an untouched
/// finger-strain dial would draw a mark sitting on "Nothing", which is an answer.
@MainActor
final class DialTrackTests: XCTestCase {
    private let ladder: [Double] = [1, 2, 3, 4, 5]

    private func dial(_ value: Double, isUnset: Bool = false) -> DialTrack {
        DialTrack(value: .constant(value), values: ladder,
                  format: { "\($0)" }, spokenUnit: "", isUnset: isUnset)
    }

    func testAnAnsweredDialHighlightsTheDetentItSitsOn() {
        XCTAssertEqual(dial(3).renderingMark, .detent(2))
    }

    /// The pre-existing behaviour, unchanged: a value between two stops keeps the hollow
    /// mark rather than lighting a detent it is not on.
    func testAValueBetweenStopsStillDrawsTheOffLadderMark() {
        XCTAssertEqual(dial(2.5).renderingMark, .offLadder)
    }

    func testAnUnsetDialDrawsNoCurrentDetent() {
        XCTAssertNil(dial(1, isUnset: true).renderingMark,
                     "an untouched dial parked on its placeholder must not light stop one")
    }

    /// The sentinel trap stated above, asserted directly.
    func testAnUnsetDialDrawsNoOffLadderMarkEither() {
        XCTAssertNil(dial(0, isUnset: true).renderingMark)
        XCTAssertNil(dial(2.5, isUnset: true).renderingMark)
    }
}
