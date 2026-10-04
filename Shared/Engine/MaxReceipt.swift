// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

// MARK: - What a new max changes, as pure values
//
// Lifted out of `TemplateStore` (2026-10-04): the store records the max and writes any
// accepted rescale; everything in between — which percentage targets now resolve to
// different kilograms, which typed-kilogram targets may be offered a rescale — is
// arithmetic over two max tables and the routines' plans, and lives here where a test
// reaches it without a database.

/// Unsaved values from one editing or measurement flow. Existing records remain
/// untouched so a new working max preserves the history for its grip and hand.
struct MaxSave: Hashable, Sendable {
    let grip: GripSpec
    let side: Side
    let kg: Double
    let source: MaxSource
    /// 0 for a peak max; otherwise the timed window — see `MaxRecord.durationSeconds`.
    var seconds: Int = 0
}

/// The pieces a max-save receipt is made of: the percentage targets that followed a new
/// max, and the typed-kilogram targets offered a rescale.
enum MaxImpact {
    /// A percentage band that now resolves to different kilograms. INFORMATIONAL:
    /// percent targets follow the newest max by design — this is the visibility,
    /// not a consent form.
    struct PercentMove: Hashable, Sendable, Identifiable {
        let routineID: UUID
        let routineName: String
        let side: Side
        let loPercent: Double
        let hiPercent: Double
        /// nil when the grip had no max before — the band never resolved until now.
        let oldBand: ClosedRange<Double>?
        let newBand: ClosedRange<Double>
        var id: String { routineID.uuidString + "·\(side.rawValue)·\(loPercent)–\(hiPercent)" }
    }

    /// Explicit-kilogram sets on this grip, offered a proportional rescale. An
    /// OFFER, never automatic: a number a person typed is never moved by
    /// arithmetic without a yes — the same precedence rule `PlanMath` states.
    struct KgOffer: Hashable, Sendable, Identifiable {
        struct Move: Hashable, Sendable {
            let oldBand: ClosedRange<Double>
            let newBand: ClosedRange<Double>
        }
        let routineID: UUID
        let routineName: String
        let moves: [Move]
        var id: UUID { routineID }
    }
}

/// A receipt describes one committed save, not an intermediate left/right state.
/// In particular, a batch containing shared and individual values must resolve its
/// targets from the final table, with the individual values taking precedence.
struct MaxSaveReceipt: Identifiable, Sendable {
    struct PercentMove: Identifiable, Sendable {
        let grip: GripSpec
        let move: MaxImpact.PercentMove
        var id: String { grip.key + "|" + move.id }
    }

    struct RescaleOffer: Identifiable, Sendable {
        let grip: GripSpec
        let ratio: Double
        let newMaxKg: Double
        let routines: [MaxImpact.KgOffer]
        /// Offers are reviewable snapshots. A routine edited while the receipt is
        /// open must not be silently scaled using a now-outdated preview.
        let expectedPlans: [UUID: SessionPlan]
        var id: String { grip.key }
    }

    let id = UUID()
    let values: [MaxSave]
    let percentMoves: [PercentMove]
    let rescaleOffers: [RescaleOffer]
    var hasDetails: Bool { !percentMoves.isEmpty || !rescaleOffers.isEmpty }
}

enum MaxReceiptMath {
    /// A routine as the receipt reads it.
    struct Routine: Sendable {
        let id: UUID
        let name: String
        let plan: SessionPlan
    }

    /// What `values`, already saved, changed: `previous` is the max table before the
    /// save and `current` the one after it.
    static func receipt(for values: [MaxSave], previous: MaxTable, current: MaxTable,
                        routines candidates: [Routine]) -> MaxSaveReceipt {
        // CloudKit does not guarantee unique routine UUIDs. An ambiguous ID cannot
        // identify a reviewable target, but must not hide unrelated valid routines.
        let routinesByID = Dictionary(grouping: candidates, by: \.id)
        let routines = candidates.filter { routinesByID[$0.id]?.count == 1 }
        var percentMoves: [MaxSaveReceipt.PercentMove] = []
        var rescaleOffers: [MaxSaveReceipt.RescaleOffer] = []
        let grips = Dictionary(grouping: values, by: { $0.grip.key })

        for gripKey in grips.keys.sorted() {
            guard let changes = grips[gripKey], let grip = changes.first?.grip else { continue }
            // Rescaling typed kilograms is a PEAK-max story: a timed max is a different
            // number and no ratio against the old peak.
            let sharedChange = changes.first { $0.side == .both && $0.seconds == 0 }
            let ratio = sharedChange.flatMap { change in
                previous.exact(grip: gripKey, side: .both).map { change.kg / $0 }
            }
            var kgOffers: [MaxImpact.KgOffer] = []
            var expectedPlans: [UUID: SessionPlan] = [:]

            for routine in routines {
                let plan = routine.plan.executable
                let sides: [Side] = plan.handMode == .bothHands ? [.both] : [.left, .right]
                // A shared typed band can be rescaled only if both alternating hands
                // used, and still use, that same shared benchmark. New exact values in
                // this very batch disqualify the offer just like older exact values do.
                let canScale = sharedChange != nil && (plan.handMode == .bothHands ||
                    [Side.left, .right].allSatisfy {
                        previous.exact(grip: gripKey, side: $0) == nil &&
                            current.exact(grip: gripKey, side: $0) == nil
                    })
                var seenPercents: Set<String> = []
                var kgMoves: [MaxImpact.KgOffer.Move] = []

                for set in plan.sets where set.grip.key == gripKey {
                    if let explicit = set.targetBand {
                        guard canScale, let ratio, ratio.isFinite, ratio > 0 else { continue }
                        let newBand = scaled(explicit, by: ratio)
                        guard explicit != newBand else { continue }
                        let move = MaxImpact.KgOffer.Move(oldBand: explicit, newBand: newBand)
                        if !kgMoves.contains(move) { kgMoves.append(move) }
                    } else if let percent = PlanMath.targetPercent(set, in: plan) {
                        // Keyed with the basis too: 90 % of the peak and 90 % of a 10 s
                        // max are different targets on the same grip.
                        let basis = PlanMath.maxSeconds(set, in: plan).map { "\($0)s" } ?? "peak"
                        let key = "\(percent.lowerBound)–\(percent.upperBound)|\(basis)"
                        guard seenPercents.insert(key).inserted else { continue }
                        for side in sides {
                            // Resolved through the tables, so each set reads the max it is
                            // a percentage OF — a peak save moves peak sets, a timed save
                            // moves the sets measured against that length.
                            let oldBand = PlanMath.targetBand(set, in: plan, side: side, maxes: previous)
                            guard let newBand = PlanMath.targetBand(set, in: plan, side: side, maxes: current),
                                  oldBand != newBand else { continue }
                            let move = MaxImpact.PercentMove(
                                routineID: routine.id, routineName: routine.name, side: side,
                                loPercent: percent.lowerBound, hiPercent: percent.upperBound,
                                oldBand: oldBand, newBand: newBand)
                            percentMoves.append(.init(grip: grip, move: move))
                        }
                    }
                }
                if !kgMoves.isEmpty {
                    kgOffers.append(.init(routineID: routine.id, routineName: routine.name, moves: kgMoves))
                    expectedPlans[routine.id] = routine.plan
                }
            }
            if !kgOffers.isEmpty, let ratio, let sharedChange {
                rescaleOffers.append(.init(grip: grip, ratio: ratio, newMaxKg: sharedChange.kg,
                                          routines: kgOffers, expectedPlans: expectedPlans))
            }
        }
        return MaxSaveReceipt(values: values, percentMoves: percentMoves, rescaleOffers: rescaleOffers)
    }

    /// Half-kilogram rounding, same as the percent path resolves to — a scaled typed
    /// number should look like a number someone could have typed.
    static func scaledKg(_ kg: Double, by ratio: Double) -> Double {
        PlanMath.roundedToHalfKg(kg * ratio)
    }

    static func scaled(_ band: ClosedRange<Double>, by ratio: Double) -> ClosedRange<Double> {
        let lo = scaledKg(band.lowerBound, by: ratio)
        let hi = scaledKg(band.upperBound, by: ratio)
        return Swift.min(lo, hi)...Swift.max(lo, hi)
    }
}
