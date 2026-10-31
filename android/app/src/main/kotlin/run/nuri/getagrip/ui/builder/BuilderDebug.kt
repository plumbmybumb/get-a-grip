// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import android.content.Intent
import run.nuri.getagrip.BuildConfig
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.TargetBasis

/// Headless builder states for `adb` screenshots, which cannot tap — DEBUG only, the twin of
/// iOS `BuilderDebug` (`-builderTargetBasis`, `-builderOpenTarget`):
///
/// ```
/// adb shell am start -n run.nuri.getagrip/.MainActivity --es builderTargetBasis routine
/// adb shell am start -n run.nuri.getagrip/.MainActivity --es builderTargetBasis set --ez builderOpenTarget true
/// ```
///
/// - `builderTargetBasis routine`: the routine band (opening on 20–30 % when it has none) is
///   a percentage of critical force.
/// - `builderTargetBasis set`: set 1 carries its own 70–80 % of critical force.
/// - `builderOpenTarget` (read by `TargetBandRow` and `RoutineTargetRow`): the target editors
///   open on appear.
object BuilderDebug {
    fun applying(draft: RoutineDraft, intent: Intent): RoutineDraft {
        if (!BuildConfig.DEBUG) return draft
        return when (intent.getStringExtra("builderTargetBasis")) {
            "routine" -> {
                val plan = draft.plan
                val banded = if (plan.targetPercentBand == null) {
                    BuilderDraft.withRoutineBand(draft, 0.20..0.30)
                } else draft
                BuilderDraft.withRoutineTargetBasis(banded, TargetBasis.criticalForce)
            }
            "set" -> {
                val sets = draft.plan.sets
                if (sets.isEmpty()) return draft
                val first = sets[0].copy(targetLoPercent = 0.70, targetHiPercent = 0.80,
                    targetLoKg = null, targetHiKg = null, targetMaxSeconds = null)
                    .withTargetBasis(TargetBasis.criticalForce)
                draft.copy(plan = draft.plan.copy(sets = listOf(first) + sets.drop(1)))
            }
            else -> draft
        }
    }
}
