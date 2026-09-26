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
            return result
        }
        result.plan.targetLoPercent = nil
        result.plan.targetHiPercent = nil
        return result
    }
}

extension BuilderDraftPreparation {
    /// The paged prototype's Rhythm page edits ONE routine-wide percentage. `editable`
    /// spreads a routine band onto the sets; when every set carries the same percentage
    /// and no kilograms, fold it back up so page 1 shows it. Resolution-preserving, and
    /// `RoutineDraft.normalized` demotes it again on Save.
    static func promotingUniformBand(_ draft: RoutineDraft) -> RoutineDraft {
        let sets = draft.plan.sets
        guard draft.plan.targetPercentBand == nil,
              let band = sets.first?.targetPercentBand,
              sets.allSatisfy({ !$0.hasTarget && $0.targetPercentBand == band }) else { return draft }
        var result = draft
        result.plan.targetLoPercent = band.lowerBound
        result.plan.targetHiPercent = band.upperBound
        result.plan.sets = sets.map { set in
            var s = set
            s.targetLoPercent = nil
            s.targetHiPercent = nil
            return s
        }
        return result
    }
}
