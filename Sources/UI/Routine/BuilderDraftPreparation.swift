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
