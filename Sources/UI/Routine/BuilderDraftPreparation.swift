// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

enum BuilderDraftPreparation {
    /// The editor only has per-set load controls. Materialize legacy inheritance in its
    /// local draft, preserving resolution and leaving storage unchanged until Save.
    static func editable(_ draft: RoutineDraft) -> RoutineDraft {
        guard let band = draft.plan.targetPercentBand else { return draft }
        var result = draft
        result.plan.sets = draft.plan.sets.map { set in
            guard !set.hasTarget, !set.hasPercentTarget else { return set }
            var result = set
            result.targetLoPercent = band.lowerBound
            result.targetHiPercent = band.upperBound
            result.targetMaxSeconds = draft.plan.targetMaxSeconds
            return result
        }
        result.plan.targetLoPercent = nil
        result.plan.targetHiPercent = nil
        result.plan.targetMaxSeconds = nil
        return result
    }
}

extension BuilderDraftPreparation {
    /// The builder's Rhythm page edits ONE routine-wide percentage. `editable`
    /// spreads a routine band onto the sets; when every set carries the same percentage
    /// and no kilograms, fold it back up so page 1 shows it. Resolution-preserving, and
    /// `RoutineDraft.normalized` demotes it again on Save.
    static func promotingUniformBand(_ draft: RoutineDraft) -> RoutineDraft {
        let sets = draft.plan.sets
        // The basis is part of the band: two sets at 90 % of different maxes do not share one.
        guard draft.plan.targetPercentBand == nil,
              let first = sets.first, let band = first.targetPercentBand,
              sets.allSatisfy({ !$0.hasTarget && $0.targetPercentBand == band
                                && $0.targetMaxSeconds == first.targetMaxSeconds }) else { return draft }
        var result = draft
        result.plan.targetLoPercent = band.lowerBound
        result.plan.targetHiPercent = band.upperBound
        result.plan.targetMaxSeconds = first.targetMaxSeconds
        result.plan.sets = sets.map { set in
            var s = set
            s.targetLoPercent = nil
            s.targetHiPercent = nil
            s.targetMaxSeconds = nil
            return s
        }
        return result
    }
}
