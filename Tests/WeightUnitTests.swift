// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import XCTest
@testable import Doigt

final class WeightUnitTests: XCTestCase {
    func testPoundDefinitionAndSignedReadings() {
        XCTAssertEqual(WeightUnit.lb.toKg(1), 0.45359237, accuracy: 1e-12)
        XCTAssertEqual(WeightUnit.lb.fromKg(45.359237), 100, accuracy: 1e-10)
        XCTAssertEqual(WeightUnit.lb.fromKg(-0.45359237), -1, accuracy: 1e-12)
        XCTAssertEqual(WeightUnit.kg.fromKg(10.125), 10.125)
    }

    func testRepeatedPreferenceChangesDoNotRoundCanonicalMeasurements() {
        let original = 19.23456789
        var value = original
        for _ in 0..<1_000 {
            _ = WeightUnit.lb.number(value)
            _ = WeightUnit.kg.number(value)
            value = WeightUnit.lb.toKg(WeightUnit.lb.fromKg(value))
        }
        XCTAssertEqual(value, original, accuracy: 1e-10)
    }

    func testEnglishAndFrenchFormattingAndUnitWords() {
        XCTAssertEqual(WeightUnit.lb.text(4.5359237, locale: Locale(identifier: "en_US")), "10.0 lb")
        XCTAssertEqual(WeightUnit.lb.text(4.5359237, locale: Locale(identifier: "fr_FR")), "10,0 lb")
    }

    @MainActor
    func testWeightEntryBindingConvertsOnlyWhenEdited() {
        var stored = 12.3456789
        let source = Binding(get: { stored }, set: { stored = $0 })
        let pounds = WeightUnit.lb.binding(source)
        _ = pounds.wrappedValue
        XCTAssertEqual(stored, 12.3456789)
        pounds.wrappedValue = 50
        XCTAssertEqual(stored, 22.6796185, accuracy: 1e-10)
        XCTAssertEqual(pounds.wrappedValue, 50, accuracy: 1e-10)
        XCTAssertEqual(WeightUnit.kg.binding(source).wrappedValue, stored)
    }
    @MainActor
    func testPreferencePersistsWithKilogramsAsTheExistingDefault() {
        let suite = "WeightUnitTests." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = SettingsStore(defaults: defaults)
        XCTAssertEqual(first.weightUnit, .kg)
        first.weightUnit = .lb
        XCTAssertEqual(SettingsStore(defaults: defaults).weightUnit, .lb)
        defaults.set("future-unit", forKey: "weightUnit")
        XCTAssertEqual(SettingsStore(defaults: defaults).weightUnit, .kg)
    }

    func testLocalizedUnitArgumentsPreserveUserTextAndFrenchNonbreakingSpace() throws {
        let path = try XCTUnwrap(Bundle.main.path(forResource: "fr", ofType: "lproj"))
        let french = try XCTUnwrap(Bundle(path: path))
        let unit = WeightUnit.lb
        let name = "My 20 kg routine"
        let cue = String(localized: "New grip next: \(name), target \(unit.number(4.5359237)) to \(unit.number(9.0718474)) \(unit.symbol)", bundle: french)
        XCTAssertTrue(cue.contains(name), "User text is never rewritten to change unit words")
        XCTAssertTrue(cue.contains("lb"))
        let tare = String(localized: "There's \(unit.number(4.5359237)) \(unit.symbol) on the gauge. Zero it?", bundle: french)
        XCTAssertTrue(tare.contains("\u{00A0}lb"), "French nonbreaking spacing must keep the selected unit")
        XCTAssertFalse(tare.contains(" kg"))
    }

    func testPoundSliderDetentsUseRoundStepsWhileTypedLimitsStayExact() {
        let threshold = WeightUnit.lb.sliderRangeFromKg(0.5...10)
        XCTAssertEqual(threshold, 1.5...22)
        XCTAssertEqual(WeightUnit.lb.sliderRangeFromKg(0...100), 0...220)
        for stop in stride(from: threshold.lowerBound, through: threshold.upperBound, by: 0.5) {
            XCTAssertEqual(stop * 2, (stop * 2).rounded())
            XCTAssertTrue((0.5...10).contains(WeightUnit.lb.toKg(stop)))
        }
        let exact = WeightUnit.lb.rangeFromKg(0.5...10)
        XCTAssertTrue(exact.contains(1.2), "Typing retains values between the true limit and the first slider stop")
        XCTAssertFalse(threshold.contains(1.2))
    }

}
