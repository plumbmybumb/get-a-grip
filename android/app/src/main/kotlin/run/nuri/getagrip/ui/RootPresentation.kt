// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.builder.BuilderMode
import run.nuri.getagrip.ui.maxes.NewMaxDraft

/// **What the root is presenting, retained across a rotation** — the same move
/// `WorkoutViewModel` makes for the runner.
///
/// These used to be `remember`ed in `RootTabView`, and a configuration change destroys every
/// `remember`: turning the phone mid-build dropped you back on Today with the routine gone,
/// and a max half-entered vanished with its sheet. A ViewModel outlives the Activity's
/// recreation and dies with the task, which is the lifetime a presentation has. Like the
/// runner, it is deliberately NOT restored after process death: the builder's create-mode
/// rescue stash already covers that for the one thing worth rescuing.
///
/// Each open screen keeps its own in-progress VALUES itself (the builder saves its draft with
/// `rememberSaveable`); this holds only which screen is up.
internal class RootPresentation : ViewModel() {
    /// THE BUILDER IS A FULL-SCREEN COVER — which door it was opened through, or null.
    var building: BuilderMode? by mutableStateOf(null)

    var newMax: NewMaxDraft? by mutableStateOf(null)
    var editingMax: MaxEditRequest? by mutableStateOf(null)
    var editingSharedMax: MaxEditRequest? by mutableStateOf(null)

    var measuring: MeasureRequest? by mutableStateOf(null)

    /// Whether THIS visit to the measure screen saved. Held as the visit it belongs to, so a
    /// new measurement starts unsaved without anybody remembering to reset it — the job
    /// `remember(measuring)` did when this lived in the composable.
    private var savedVisit: MeasureRequest? by mutableStateOf(null)
    var measurementSaved: Boolean
        get() = savedVisit != null && savedVisit === measuring
        set(value) { savedVisit = if (value) measuring else null }

    /// The log sheet — one sheet, two doors (History's row and Today's consistency card).
    var loggingSession: Boolean by mutableStateOf(false)
}

internal data class MaxEditRequest(val grip: GripSpec, val fromNew: Boolean = false)

/** Both individual hands are measured in one visit; shared measurement is explicitly chosen. */
internal data class MeasureRequest(
    val grip: GripSpec,
    val side: Side,
    val fromNew: Boolean = false,
)
