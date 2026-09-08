// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Display preference only. Storage, protocols, targets and exported contracts stay in kg.
enum WeightUnit: String, CaseIterable, Codable, Hashable, Sendable {
    case kg, lb

    static let kilogramsPerPound = 0.45359237
    var symbol: String { rawValue }
    var name: String { self == .kg ? String(localized: "Kilograms") : String(localized: "Pounds") }
    var spokenName: String { self == .kg ? String(localized: "kilograms") : String(localized: "pounds") }
    func fromKg(_ kilograms: Double) -> Double { self == .kg ? kilograms : kilograms / Self.kilogramsPerPound }
    func toKg(_ amount: Double) -> Double { self == .kg ? amount : amount * Self.kilogramsPerPound }
    func rangeFromKg(_ range: ClosedRange<Double>) -> ClosedRange<Double> {
        fromKg(range.lowerBound)...fromKg(range.upperBound)
    }
    /// Native sliders anchor their detents at the lower bound. Use whole displayed
    /// steps inside the original range; typed entry keeps the exact converted limits.
    func sliderRangeFromKg(_ kilograms: ClosedRange<Double>, step: Double = 0.5) -> ClosedRange<Double> {
        let converted = rangeFromKg(kilograms)
        let lower = (converted.lowerBound / step).rounded(.up) * step
        let upper = (converted.upperBound / step).rounded(.down) * step
        return lower <= upper ? lower...upper : converted
    }
    func number(_ kilograms: Double, locale: Locale = .current) -> String {
        fromKg(kilograms).formatted(.number.precision(.fractionLength(1)).locale(locale))
    }
    func text(_ kilograms: Double, locale: Locale = .current) -> String { "\(number(kilograms, locale: locale)) \(symbol)" }
    func bandText(_ band: ClosedRange<Double>, withUnit: Bool = true) -> String {
        "\(number(band.lowerBound))–\(number(band.upperBound))" + (withUnit ? " \(symbol)" : "")
    }

}
