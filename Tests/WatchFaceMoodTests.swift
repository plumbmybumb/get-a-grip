// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class WatchFaceMoodTests: XCTestCase {

    private func mood(_ phase: RunnerPhase, dropped: Bool = false, over: Bool = false,
                      linkDown: Bool = false, gripChangesNext: Bool = false,
                      newGrip: Bool = false) -> WatchFaceMood {
        WatchFaceMood.resolve(phase: phase, isDropped: dropped, isOverTarget: over,
                              linkIsDown: linkDown, gripChangesNext: gripChangesNext,
                              newGrip: newGrip)
    }

    func testTheLadderInNurisWords() {
        XCTAssertEqual(mood(.armed(slot: 0)), .pull, "blue: the load is yours to take")
        XCTAssertEqual(mood(.working(slot: 0)), .holding, "green: the clock is running")
        XCTAssertEqual(mood(.working(slot: 0), dropped: true), .regrip, "red: more load")
        XCTAssertEqual(mood(.resting(slot: 0)), .rest, "gray: nothing asked of you")
        XCTAssertEqual(mood(.resting(slot: 0), gripChangesNext: true), .newGrip,
                       "orange: the next pull is a different grip")
    }

    func testLessIsAmberAndNeverRed() {
        XCTAssertEqual(mood(.working(slot: 0), over: true), .easeOff)
        XCTAssertEqual(mood(.releasing(slot: 0)), .letGo)
        XCTAssertEqual(mood(.working(slot: 0), dropped: true, over: true), .regrip,
                       "dropped wins: the engine cannot be both, and RE-GRIP is the word on screen")
        for less in [WatchFaceMood.easeOff, .letGo] {
            for dimmed in [false, true] {
                XCTAssertNotEqual(WatchFacePalette.colours(for: less, dimmed: dimmed),
                                  WatchFacePalette.colours(for: .regrip, dimmed: dimmed),
                                  "\(less) asks for LESS load and RE-GRIP for more — one colour would fix one by worsening the other")
            }
        }
    }

    func testTheCountInBeforeANewGripIsOrangeAndThePullItselfIsBlue() {
        XCTAssertEqual(mood(.leadIn(slot: 0), newGrip: true), .newGrip)
        XCTAssertEqual(mood(.leadIn(slot: 0)), .rest)
        XCTAssertEqual(mood(.armed(slot: 0), newGrip: true), .pull,
                       "once the load is asked for, the instruction is pull; the grip word is on screen")
    }

    func testAPauseIsGrayWhateverItInterrupted() {
        for inner: RunnerPhase in [.armed(slot: 0), .working(slot: 0), .resting(slot: 0), .leadIn(slot: 0)] {
            XCTAssertEqual(mood(.paused(before: inner), gripChangesNext: true, newGrip: true), .paused)
        }
        XCTAssertEqual(WatchFacePalette.colours(for: .paused, dimmed: false),
                       WatchFacePalette.colours(for: .rest, dimmed: false),
                       "same gray as a rest; PAUSED is the word that differs")
    }

    func testALostLinkIsRedExceptBeforeTheFirstCountInAndAfterTheLastPull() {
        for phase: RunnerPhase in [.leadIn(slot: 0), .armed(slot: 0), .working(slot: 0),
                                   .releasing(slot: 0), .resting(slot: 0), .paused(before: .working(slot: 0))] {
            XCTAssertEqual(mood(phase, linkDown: true), .linkDown, "\(phase)")
        }
        XCTAssertEqual(mood(.idle, linkDown: true), .rest, "CONNECTING is not an alarm")
        XCTAssertEqual(mood(.finished, linkDown: true), .rest)
        XCTAssertEqual(WatchFacePalette.colours(for: .linkDown, dimmed: false),
                       WatchFacePalette.colours(for: .regrip, dimmed: false),
                       "attention here, the phone's rule")
    }

    /// Contrast is MEASURED, never eyeballed — the house rule, applied to a palette that
    /// is nothing but fills under text.
    func testEveryFillClearsWCAGSmallTextContrastAgainstItsInk() {
        for mood in WatchFaceMood.allCases {
            for dimmed in [false, true] {
                let palette = WatchFacePalette.colours(for: mood, dimmed: dimmed)
                XCTAssertNotNil(WatchFacePalette.relativeLuminance(hex: palette.fillHex),
                                "\(mood) \(dimmed ? "dimmed" : "lit"): not a colour")
                XCTAssertGreaterThanOrEqual(palette.contrastRatio, 4.5,
                                            "\(mood) \(dimmed ? "dimmed" : "lit") #\(palette.fillHex) under \(palette.inkIsWhite ? "white" : "black") ink")
            }
        }
    }

    func testDimmedFillsAreDarkerThanLitOnesAndAlwaysUnderWhiteInk() {
        for mood in WatchFaceMood.allCases {
            let lit = WatchFacePalette.colours(for: mood, dimmed: false)
            let dim = WatchFacePalette.colours(for: mood, dimmed: true)
            XCTAssertTrue(dim.inkIsWhite, "\(mood): reduced luminance whitens every ink")
            XCTAssertLessThan(WatchFacePalette.relativeLuminance(hex: dim.fillHex)!,
                              WatchFacePalette.relativeLuminance(hex: lit.fillHex)!,
                              "\(mood): Apple's rule for large colour areas in Always On is a DIMMED colour")
        }
    }

    func testTheSixColoursAreSixColours() {
        let lit = Set(WatchFaceMood.allCases.map { WatchFacePalette.colours(for: $0, dimmed: false).fillHex })
        XCTAssertEqual(lit.count, 6, "gray, orange, blue, green, red, amber — nine moods, six fills")
    }

    func testAClockRollsOnlyWithTheWristUpAndTheBatteryUnrationed() {
        XCTAssertTrue(NumeralRoll.rolls(luminanceReduced: false, lowPower: false))
        XCTAssertFalse(NumeralRoll.rolls(luminanceReduced: true, lowPower: false),
                       "one redraw a second catches a roll mid-flight as a smear")
        XCTAssertFalse(NumeralRoll.rolls(luminanceReduced: false, lowPower: true))
        XCTAssertFalse(NumeralRoll.rolls(luminanceReduced: true, lowPower: true))
    }
}
