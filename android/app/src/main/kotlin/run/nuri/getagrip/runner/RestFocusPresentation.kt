// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.engine.RepSlot
import run.nuri.getagrip.engine.RunnerPhase

/** Presentation only: never changes the engine's rest deadline or release gate. */
internal object RestFocusPresentation {
    const val minimumScheduledSeconds = 10

    fun scheduledRestSeconds(phase: RunnerPhase, slots: List<RepSlot>): Int? =
        restingSlot(phase)?.let { slots.getOrNull(it)?.restAfter }

    fun isResting(phase: RunnerPhase): Boolean = restingSlot(phase) != null

    private tailrec fun restingSlot(phase: RunnerPhase): Int? = when (phase) {
        is RunnerPhase.Resting -> phase.slot
        is RunnerPhase.Paused -> restingSlot(phase.before)
        else -> null
    }
}
