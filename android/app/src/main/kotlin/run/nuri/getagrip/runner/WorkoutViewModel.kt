// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.RPE

/// A screen can be recreated without ending the workout it displays. Retain the
/// route, runner, coroutine scope and unsaved decisions together across that change.
/// This is deliberately not restored after process death: there is no live gauge
/// timeline to resume in a new process.
class WorkoutViewModel : ViewModel() {
    var active: ActiveWorkout? by mutableStateOf(null)
        private set

    fun start(template: SessionTemplateEntity, createSession: (CoroutineScope) -> RunnerSession): Boolean {
        if (active != null) return false
        val session = createSession(viewModelScope)
        active = ActiveWorkout(template, session)
        session.begin()
        return true
    }

    fun finish(workout: ActiveWorkout) {
        if (active !== workout) return
        workout.session.end()
        active = null
    }

    override fun onCleared() {
        active?.session?.end()
        active = null
    }
}

@Stable
class ActiveWorkout(val template: SessionTemplateEntity, val session: RunnerSession) {
    val summary = WorkoutSummaryState()
    var pausedByTour = false
    var lastConnected: Boolean? = null
    private var finishedOutcome: SessionOutcome? = null

    fun outcome(): SessionOutcome = finishedOutcome ?: session.outcome().also { finishedOutcome = it }
}

/// The summary remains editable after a configuration change, and an in-flight
/// save stays single-flight while its replacement screen attaches.
@Stable
class WorkoutSummaryState {
    var rpe: RPE? by mutableStateOf(null)
    var chosenMaxIDs: Set<String> by mutableStateOf(emptySet())
    var finished: Boolean by mutableStateOf(false)
    var saveFailed: Boolean by mutableStateOf(false)
    var maxesExpanded: Boolean by mutableStateOf(false)
    var setsExpanded: Boolean by mutableStateOf(false)
}
