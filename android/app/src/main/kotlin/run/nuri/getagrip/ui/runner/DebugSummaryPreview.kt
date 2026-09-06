// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.runner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.runner.MaxCandidate
import run.nuri.getagrip.runner.SessionOutcome
import run.nuri.getagrip.ui.theme.LocalGripPalette
import java.time.Instant

/// Only reachable through a debug-build intent. Selection never writes to the real store.
@Composable
fun DebugSummaryPreview(onDone: () -> Unit) {
    val outcome = remember {
        val grips = listOf(
            GripSpec(20, FingerSet.four, GripPosition.halfCrimp),
            GripSpec(20, FingerSet.frontThree, GripPosition.halfCrimp),
            GripSpec(20, FingerSet.middleTwo, GripPosition.openHand),
            GripSpec(15, FingerSet.four, GripPosition.fullCrimp),
            GripSpec(20, FingerSet.four, GripPosition.drag),
            GripSpec(45, FingerSet.four, GripPosition.pinch),
        )
        val reps = grips.mapIndexed { i, grip ->
            RepSummary(setIndex = i, grip = grip, side = Side.both, heldSeconds = 10.0,
                peakKg = 12.0 + i, avgKg = 10.0 + i, outcome = RepOutcome.completed)
        }
        val plan = SessionPlan(name = "Daily routine · preview",
            sets = grips.map { SetPlan(grip = it, repsPerSide = 1) }, handMode = HandMode.bothHands)
        SessionOutcome(plan, plan.name, reps, Instant.now().minusSeconds(180), Instant.now(),
            17.0, 12.5, 60.0, 6, 6, false, GaugeKind.progressor, true,
            reps.map { MaxCandidate(it.grip, it.side, it.peakKg, null) }.sortedByDescending { it.kg })
    }
    SessionSummaryScreen(outcome, 1,
        modifier = Modifier.fillMaxSize().background(LocalGripPalette.current.field).safeDrawingPadding()) { _, _ ->
        onDone()
        true
    }
}
