// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import android.app.Application
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import run.nuri.getagrip.GetAGripApplication
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.RPE

/// A screen can be recreated without ending the workout it displays. Retain the
/// route, runner, coroutine scope and unsaved decisions together across that change.
/// A RUNNING session is deliberately not restored after process death: there is no live
/// gauge timeline to resume in a new process. A FINISHED one is — as a draft written at
/// the last rep, which the next launch offers to save (`UnsavedSessionRecovery`).
class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    var active: ActiveWorkout? by mutableStateOf(null)
        private set

    /// Null outside the real app (a preview host), where there is nowhere to write one.
    private val drafts: FinishedSessionDraftStore? =
        (application as? GetAGripApplication)?.finishedSessionDrafts

    fun start(template: SessionTemplateEntity, createSession: (CoroutineScope) -> RunnerSession): Boolean {
        if (active != null) return false
        val session = createSession(viewModelScope)
        val workout = ActiveWorkout(template, session)
        session.onFinished = { outcome ->
            // The summary shows exactly the outcome the draft froze.
            workout.adopt(outcome)
            // "A session nobody pulled in is not worth logging" — the Save path's own rule,
            // so there is nothing to recover either.
            if (outcome.didAnyWork) drafts?.save(FinishedSessionDraft.of(outcome, template))
        }
        active = workout
        session.begin()
        return true
    }

    /// The summary was resolved — saved, or discarded on purpose — so the draft goes with
    /// the session. NOT from `onCleared`: a ViewModel cleared with the summary still open
    /// (the task swiped away) left a session nobody decided about, and that is exactly what
    /// the draft is for.
    fun finish(workout: ActiveWorkout) {
        if (active !== workout) return
        workout.session.end()
        drafts?.clear()
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
    private var finishedOutcome: SessionOutcome? = null

    fun outcome(): SessionOutcome = finishedOutcome ?: session.outcome().also { finishedOutcome = it }

    /// The outcome computed at the finish, which the draft was written from.
    fun adopt(outcome: SessionOutcome) {
        if (finishedOutcome == null) finishedOutcome = outcome
    }
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
