// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import run.nuri.getagrip.engine.MaxAttemptLog
import run.nuri.getagrip.engine.MaxMeasurementDraft
import run.nuri.getagrip.engine.MaxMeasurementResult
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.TemplateStore

/// The draft behind the live max visit, observed at two speeds.
///
/// Samples arrive ~80×/s and every one mutates the draft, so the draft itself is NOT
/// snapshot state. Views read it through `snapshot`, which reads `revision` — bumped only
/// when a pull is logged or the review changes something. The one per-sample value, the
/// pull's climbing peak, is published on its own and read only by the hero leaf.
///
/// TRANSLATION NOTE (from `LiveMaxSession` in Sources/UI/Maxes/MaxMeasurementDraft.swift):
/// `@ObservationIgnored` is a plain field; the equality guards are explicit, and
/// `publishes` counts them so a JVM test can assert "a steady load published once".
///
/// It also holds the visit's save state (`isSaving`, `committed`, the receipt) and whether
/// the gauge was started, because it outlives a rotation in `RootPresentation` while the
/// screen does not: a recreation after Save must never offer the same pulls again.
class LiveMaxSession(bothTogether: Boolean, side: Side) {
    private val draft = MaxMeasurementDraft(bothTogether, side)
    private var revision by mutableIntStateOf(0)

    var isPulling: Boolean by mutableStateOf(false)
        private set
    var pullPeakKg: Double? by mutableStateOf(null)
        private set
    /// The most recent pull on the selected hand, for the hero between pulls.
    var lastAttempt: MaxAttemptLog.Attempt? by mutableStateOf(null)
        private set
    /// Bumped when a pull beats its hand's previous best — the success haptic's trigger.
    var newBestTick: Int by mutableIntStateOf(0)
        private set

    /// A TEST SEAM and nothing else: how many observable writes the samples caused.
    var publishes: Int = 0
        private set

    // The visit, beyond the pulls.
    internal var hasStarted = false
    var isSaving: Boolean by mutableStateOf(false)
        internal set
    var saveFailed: Boolean by mutableStateOf(false)
        internal set
    var committed: Boolean by mutableStateOf(false)
        internal set
    var receipt: TemplateStore.MaxSaveReceipt? by mutableStateOf(null)
        internal set
    var reviewing: Boolean by mutableStateOf(false)
        internal set
    var adjusting: Boolean by mutableStateOf(false)
        internal set

    val snapshot: MaxMeasurementDraft
        get() {
            @Suppress("UNUSED_EXPRESSION") revision
            return draft
        }

    val side: Side get() = snapshot.log.side
    val bothTogether: Boolean get() = draft.bothTogether

    fun receive(point: DeviceStore.TracePoint) {
        val previousBest = draft.log.best(draft.log.side)?.peakKg
        val logged = draft.add(point.kg, point.t)
        publishPull()
        if (logged != null) didLog(logged, previousBest)
    }

    fun close() {
        val previousBest = draft.log.best(draft.log.side)?.peakKg
        val logged = draft.close()
        publishPull()
        if (logged != null) didLog(logged, previousBest)
    }

    fun select(side: Side) {
        draft.select(side)
        lastAttempt = null
        publishPull()
        revision += 1
    }

    fun pick(id: Int) { draft.pick(id); revision += 1 }
    fun move(id: Int, to: Side) { draft.move(id, to); forgetLastIfGone(); revision += 1 }
    fun remove(id: Int) { draft.remove(id); forgetLastIfGone(); revision += 1 }

    fun correct(values: List<MaxMeasurementResult>): Boolean {
        val applied = draft.correct(values)
        if (applied) revision += 1
        return applied
    }

    private fun didLog(attempt: MaxAttemptLog.Attempt, previousBest: Double?) {
        if (attempt.side == draft.log.side) lastAttempt = attempt
        if (attempt.peakKg > (previousBest ?: 0.0)) newBestTick += 1
        revision += 1
        publishes += 1
    }

    private fun forgetLastIfGone() {
        val last = lastAttempt ?: return
        if (draft.log.attempts(draft.log.side).none { it.id == last.id }) lastAttempt = null
    }

    /// Equality-guarded: a snapshot write of an equal value is skipped by Compose too, but
    /// implicitly — the guard makes the rule, and `publishes`, explicit.
    private fun publishPull() {
        if (isPulling != draft.log.isPulling) {
            isPulling = draft.log.isPulling
            publishes += 1
        }
        val peak = draft.log.pullPeakKg
        if (pullPeakKg != peak) {
            pullPeakKg = peak
            publishes += 1
        }
    }
}
