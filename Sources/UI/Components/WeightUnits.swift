// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

private struct WeightUnitKey: EnvironmentKey { static let defaultValue: WeightUnit = .kg }
extension EnvironmentValues {
    var weightUnit: WeightUnit {
        get { self[WeightUnitKey.self] }
        set { self[WeightUnitKey.self] = newValue }
    }
}

extension WeightUnit {
    /// Round only values the person edits, in the displayed unit. Merely displaying a
    /// field or changing units never writes back or progressively rounds stored data.
    func binding(_ kilograms: Binding<Double>) -> Binding<Double> {
        Binding(get: { fromKg(kilograms.wrappedValue) }, set: { kilograms.wrappedValue = toKg($0) })
    }
}

extension WeightUnit {
    func targetText(_ set: SetPlan, in plan: SessionPlan, maxes: MaxTable,
                    criticalForces: CriticalForceTable = CriticalForceTable()) -> String? {
        guard plan.handMode.sideCount > 1 else {
            return PlanMath.targetBand(set, in: plan, side: .both, maxes: maxes,
                                       criticalForces: criticalForces).map { bandText($0) }
        }
        let left = PlanMath.targetBand(set, in: plan, side: .left, maxes: maxes, criticalForces: criticalForces)
        let right = PlanMath.targetBand(set, in: plan, side: .right, maxes: maxes, criticalForces: criticalForces)
        switch (left, right) {
        case (nil, nil): return nil
        case let (l?, r?) where l == r: return bandText(l)
        case let (l?, r?): return String(localized: "L \(bandText(l, withUnit: false)) · R \(bandText(r))")
        case let (l?, nil): return String(localized: "L \(bandText(l))")
        case let (nil, r?): return String(localized: "R \(bandText(r))")
        }
    }
}
