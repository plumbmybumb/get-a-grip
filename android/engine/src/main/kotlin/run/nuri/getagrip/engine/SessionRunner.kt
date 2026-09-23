// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// The guided session. Pure logic: no UI, no Bluetooth, no clock of its own. Every
// input arrives as an event and every output leaves as a returned cue, so a whole
// workout replays in a test from a synthetic force trace — the only way the timing
// rules below are checkable at all.
//
// TRANSLATION NOTE (from Shared/Engine/SessionRunner.swift): three shapes move.
//   - `RunnerPhase` / `RunnerEvent` / `RunnerCue` (Swift enums with associated values)
//     become sealed interfaces of data classes and objects, so `assertEquals` on a
//     list of cues works as `XCTAssertEqual` does on `[RunnerCue]`.
//   - `SessionRunner` is a Swift value-type `struct`; here it is a class with the same
//     members. No test copies a runner, so there is no `copy()` yet.
//   - `defer` becomes `try { … } finally { … }` in `receive` and `holdTick`, where the
//     deferred write must also happen on an early return.
// Integer widths follow this port's own mapping: `UInt64` → `ULong`, `UInt32` → `UInt`, and
// Swift's `&-` is plain `-` because Kotlin's unsigned arithmetic wraps by definition.

// MARK: - Phases

/// `indirect` because `.paused` wraps the phase it interrupted — resuming has to put
/// the runner back exactly where it was, including a countdown's remaining time.
sealed interface RunnerPhase {
    data object Idle : RunnerPhase

    /// "Get ready" before the first rep of a set. Wall-clock.
    data class LeadIn(val slot: Int) : RunnerPhase

    /// Waiting for the climber to take the load. **No timeout, deliberately**: the
    /// human override is Skip, not a clock that gives up on you mid-chalk.
    data class Armed(val slot: Int) : RunnerPhase

    /// Force is above threshold and the rep's clock is running.
    data class Working(val slot: Int) : RunnerPhase

    /// The hold is DONE and recorded, but you are still on the edge. No clock runs: the
    /// rest countdown waits until force drops below release. Only reachable when
    /// `plan.waitForReleaseBeforeRest` is on. Its own phase, not "resting but paused",
    /// because `isCountingDown` would lie and the screen must say LET GO.
    data class Releasing(val slot: Int) : RunnerPhase

    /// Between reps, or between sets — the slot's own `restAfter` covers both, and
    /// `isSetBreak` is what tells them apart. Wall-clock.
    data class Resting(val slot: Int) : RunnerPhase

    data class Paused(val before: RunnerPhase) : RunnerPhase

    data object Finished : RunnerPhase

    /// The rep this phase belongs to, if any.
    val slotIndex: Int?
        get() = when (this) {
            is LeadIn -> slot
            is Armed -> slot
            is Working -> slot
            is Releasing -> slot
            is Resting -> slot
            is Paused -> before.slotIndex
            Idle, Finished -> null
        }

    val isPaused: Boolean get() = this is Paused

    val isCountingDown: Boolean
        get() = when (this) {
            is LeadIn, is Resting -> true
            else -> false
        }
}

// MARK: - Events and cues

sealed interface RunnerEvent {
    data object Start : RunnerEvent
    data class Sample(val sample: ForceSample) : RunnerEvent

    /// Wall-clock heartbeat, ~4–10 Hz. Drives every countdown; never work time.
    data object Tick : RunnerEvent
    data object ConnectionLost : RunnerEvent
    data object ConnectionRestored : RunnerEvent

    /// The gauge was re-zeroed, so the next sample's timestamp is not comparable
    /// with the last one.
    data object TareCommitted : RunnerEvent

    /// The silence watchdog is restarting weight measurement. The device may begin a
    /// fresh timestamp epoch, so this breaks the timeline like a committed tare.
    data object StreamRestarted : RunnerEvent
    data object Pause : RunnerEvent
    data object Resume : RunnerEvent
    data object SkipRep : RunnerEvent
    data object SkipSet : RunnerEvent
    data object Abort : RunnerEvent
}

/// What the session wants the app to say or do. Returned from `handle`, never played
/// by the engine — which is what lets a test assert the entire cue schedule of a
/// workout without an audio session existing.
sealed interface RunnerCue {
    data class LeadInTick(val secondsRemaining: Int) : RunnerCue
    data class Armed(val side: Side) : RunnerCue
    data object RepStarted : RunnerCue
    data object RepHalfway : RunnerCue
    data class RepEnded(val completed: Boolean) : RunnerCue
    data class RestTick(val secondsRemaining: Int) : RunnerCue
    data class SetCompleted(val setIndex: Int) : RunnerCue
    data object SessionCompleted : RunnerCue

    /// Force fell below the release threshold mid-rep — the clock has stopped but the
    /// rep is still alive. Fires once per dip.
    data object DropoutWarning : RunnerCue
    data object ConnectionLost : RunnerCue
}

// MARK: - Runner

class SessionRunner(
    plan: SessionPlan,
    maxes: MaxTable = MaxTable(),

    /// No gauge at all — a flat battery, or the gauge left at home (Nuri, 2026-08-09).
    /// The engine changes in exactly three places:
    /// - no `armed` phase, because nothing can observe you taking the load, so the hold
    ///   starts when the lead-in ends;
    /// - the hold's clock runs on `tick` (wall time) instead of device timestamps;
    /// - `waitForReleaseBeforeRest` is skipped, because there is no release to see.
    ///
    /// Everything else is the same code path: a real session with unmeasured load, not
    /// a simulation, which is why it books real reps.
    val timerOnly: Boolean = false,

    /// The most hang time ONE sample-to-sample gap may bank, for gauges whose timestamps
    /// the CLIENT synthesizes from host uptime (`SyntheticSampleClock`). null, the
    /// Progressor's setting, keeps the device-clock rule: a gap over
    /// `maxCreditableDeltaMicros` credits nothing. See `creditableDelta`.
    val maxCreditedSampleGapSeconds: Double? = null,
) {

    // MARK: Tuning
    //
    // Named so they tune from one place. Each absorbs a specific physical reality.

    companion object {
        /// Force must stay above the threshold this long before the rep's clock starts.
        /// Rejects the single-sample spike of bumping the edge on the way to gripping it.
        val engageDebounceMicros: ULong = 100_000uL

        /// A larger device-time delta is a broken timeline, not hang time (the Progressor
        /// samples every ~12.5 ms). Applies to gauges that stamp their OWN samples; the rest
        /// get a clamp instead of a cliff — see `creditableDelta`.
        val maxCreditableDeltaMicros: UInt = 200_000u

        // THERE IS NO DROPOUT TIMEOUT, AND THERE MUST NOT BE ONE (Nuri, 2026-08-03).
        // Coming off the edge never ends a rep: the clock stops, the screen says RE-GRIP,
        // and it resumes where it paused. Re-gripping honestly takes more than three
        // seconds, so any grace long enough to be fair is long enough to be pointless.
        // Skip is the only way to end a rep early; a dropped connection likewise waits for
        // the gauge rather than abandoning work somebody actually did.

        /// Release sits BELOW engage by this band, so force hovering exactly at the
        /// threshold cannot chatter the rep on and off. 5% of the threshold, clamped to
        /// something a hand can actually feel.
        fun releaseBand(thresholdKg: Double): Double = min(2.0, max(0.5, thresholdKg * 0.05))
    }

    /// The window a rep's clock runs inside.
    ///
    /// No target band: the session threshold and no ceiling. With one, the BAND is the
    /// rule (Nuri, 2026-08-09): the clock runs only while the load is inside it, so a rep
    /// prescribed at 22.5–34 kg cannot be banked at 12. Below reads RE-GRIP, above reads
    /// EASE OFF; both stop the clock, neither ends the rep. `release*` sit OUTSIDE
    /// `engage*` by the same hysteresis both ways, so neither edge chatters and the
    /// ordinary overshoot at the start of a hard pull costs nothing.
    data class RepGate(
        val engageLo: Double,
        val engageHi: Double,
        val releaseLo: Double,
        val releaseHi: Double,
    ) {
        fun holds(kg: Double): Boolean = kg >= releaseLo && kg <= releaseHi
        fun admits(kg: Double): Boolean = kg >= engageLo && kg <= engageHi
    }

    private fun gate(slot: RepSlot): RepGate {
        // `pausesOutsideTargetBand == false` takes the band-less gate: threshold, no
        // ceiling. The band is still drawn, it just stops refereeing. Letting go still
        // stops the rep — that is about whether you pull, not in which range.
        val band = slot.targetBand
        if (!plan.pausesOutsideTargetBand || band == null || band.endInclusive <= 0) {
            return RepGate(
                engageLo = engageKg, engageHi = Double.POSITIVE_INFINITY,
                releaseLo = releaseKg, releaseHi = Double.POSITIVE_INFINITY,
            )
        }
        // A target line cannot require exact floating-point equality. Ordinary
        // bands retain their authored bounds; lines tolerate half a 0.5 kg step.
        val tolerance = if (band.start == band.endInclusive) 0.25 else 0.0
        return RepGate(
            engageLo = max(Double.MIN_VALUE, band.start - tolerance),
            engageHi = band.endInclusive + tolerance,
            releaseLo = max(0.0, band.start - releaseBand(band.start)),
            releaseHi = band.endInclusive + releaseBand(band.endInclusive),
        )
    }

    // MARK: Plan

    /// TRANSLATION NOTE: the constructor parameter is also called `plan`, and a Kotlin
    /// constructor parameter shadows the member of the same name inside every property
    /// initializer — so the members below say `this.plan` on purpose. The call site
    /// keeps Swift's spelling (`SessionRunner(plan = …, maxes = …)`).
    val plan: SessionPlan = plan.executable

    /// The session as a flat list of fully-resolved reps. `PlanMath.sequence` is the
    /// single source of truth for what happens in what order, so the runner cannot
    /// drift from the duration the routine advertised.
    ///
    /// `maxes` is read ONCE here, which freezes the session's loads: percentage targets
    /// become kilograms per rep, per hand, so a max recorded next month cannot rewrite
    /// what this morning prescribed. Empty by default for callers testing timing only.
    val slots: List<RepSlot> = PlanMath.sequence(this.plan, maxes)

    private val engageKg: Double = this.plan.thresholdKg
    private val releaseKg: Double =
        max(0.0, this.plan.thresholdKg - releaseBand(this.plan.thresholdKg))

    /// `maxCreditedSampleGapSeconds` in the accumulator's own units, resolved once.
    ///
    /// Clamped at zero rather than trusted: a negative cap would underflow the
    /// unsigned accumulator this feeds, and "no credit" is the honest reading of it.
    private val creditedGapCeilingMicros: ULong? =
        maxCreditedSampleGapSeconds?.let { (max(0.0, it) * 1_000_000).toULong() }

    // MARK: Observable state

    var phase: RunnerPhase = RunnerPhase.Idle
        private set

    private val _results = mutableListOf<RepSummary>()
    val results: List<RepSummary> get() = _results

    /// Live force, for the hero numeral. Zeroed when the link drops so a stale number
    /// can't sit on screen looking current.
    var currentKg: Double = 0.0
        private set

    var linkIsDown: Boolean = false
        private set

    /// Seconds of the current rep already banked. Drives the ring and the countdown.
    var heldSeconds: Double = 0.0
        private set

    /// True while a rep is alive but its clock is stopped because force fell below the
    /// release threshold. The screen has to SAY this — a timer that silently stops
    /// looks broken, and the climber's instinct is to pull harder rather than re-grip.
    var isDropped: Boolean = false
        private set

    /// The other way to stall a banded rep: OVER the target range. Its own flag because
    /// "pull harder" is the wrong thing to tell someone already pulling too hard.
    var isOverTarget: Boolean = false
        private set

    // MARK: Internal state

    /// When the current countdown phase ends, in the caller's monotonic seconds.
    private var countdownEndsAt: Double = 0.0

    /// The original length of that countdown, so the dial can show the phase as a
    /// fraction. Replaced whenever `countdownEndsAt` is.
    private var countdownDuration: Double = 0.0

    /// Last value announced by a countdown cue, so a tick only speaks when the number
    /// on screen actually changes.
    private var lastAnnouncedSecond: Int = -1
    private var pausedAt: Double = 0.0

    /// Previous sample, for wrap-aware deltas. Set to null whenever the timeline breaks
    /// (pause, reconnect, tare) so an outage is never credited as hang time.
    private var lastSample: ForceSample? = null

    /// Last accepted device timestamp. A notification beginning at or behind this mark
    /// is a retransmission and is rejected as a unit. Cleared only by an explicit
    /// timeline break; stale data can never authorize a new epoch by itself.
    private var highWaterMicros: UInt? = null
    private var rejectingBatch = false

    /// The previous tick, for the gauge-free hold's wall-clock delta. null across every
    /// discontinuity, exactly like `lastSample`, so a pause is never banked as hang time.
    private var lastTickAt: Double? = null

    /// Device time at which force first rose above engage, for the debounce.
    private var engagedSince: UInt? = null
    private var warnedThisDip = false

    /// Once force leaves the outer HOLD range, the clock cannot resume in the dead band;
    /// it must cross back through the stricter ENGAGE range first.
    private var mustReengage = false
    private var announcedHalfway = false

    private var accruedMicros: ULong = 0uL
    private var peakKg: Double = 0.0

    /// Σ(kg × µs) over engaged time only, so the average is time-weighted: a 1 s tail
    /// must not pull the mean as hard as a 9 s plateau.
    private var weightedKgMicros: Double = 0.0

    // MARK: - The one entry point

    /// `at` is caller-supplied monotonic seconds. The engine never reads a clock itself,
    /// which is what makes a whole session replayable from `0.0, 0.1, 0.2…`.
    ///
    /// TRANSLATION NOTE: Swift's `handle(_ event:at:)` — the label is `at` here too, so
    /// `runner.handle(RunnerEvent.Start, at = 0.0)` reads the same in both languages.
    private var recordingOrigin: Double? = null
    private var recordingNow: Double = 0.0
    private var repStartedElapsedSeconds: Double? = null
    var finishedElapsedSeconds: Double? = null
        private set
    fun beginRecording(at: Double) {
        if (recordingOrigin == null && at.isFinite()) recordingOrigin = at
    }
    private val recordedElapsed: Double? get() = recordingOrigin?.let {
        if (recordingNow.isFinite()) maxOf(0.0, recordingNow - it) else null
    }

    fun handle(event: RunnerEvent, at: Double, recordedAt: Double? = null): List<RunnerCue> {
        recordingNow = recordedAt ?: at
        try { return when (event) {
        RunnerEvent.Start -> start(at)
        is RunnerEvent.Sample -> receive(event.sample, at)
        RunnerEvent.Tick -> tick(at)
        RunnerEvent.ConnectionLost -> linkLost(at)
        RunnerEvent.ConnectionRestored -> linkRestored()
        RunnerEvent.TareCommitted -> { breakTimeline(); emptyList() }
        RunnerEvent.StreamRestarted -> { breakTimeline(); emptyList() }
        RunnerEvent.Pause -> pause(at)
        RunnerEvent.Resume -> resume(at)
        RunnerEvent.SkipRep -> endCurrentRep(RepOutcome.skipped, at)
        RunnerEvent.SkipSet -> skipSet(at)
        RunnerEvent.Abort -> abort(at)
    }
        } finally {
            if (isFinished && finishedElapsedSeconds == null) finishedElapsedSeconds = recordedElapsed
        }
    }

    // MARK: - Lifecycle

    private fun start(t: Double): List<RunnerCue> {
        if (phase !is RunnerPhase.Idle) return emptyList()
        beginRecording(recordingNow)
        if (slots.isEmpty()) {
            phase = RunnerPhase.Finished
            return listOf(RunnerCue.SessionCompleted)
        }
        return enter(0, t)
    }

    /// Begin a rep: its lead-in if it has one, otherwise straight to armed.
    private fun enter(index: Int, t: Double): List<RunnerCue> {
        resetRepAccumulators()
        val slot = slots[index]
        if (slot.leadInBefore > 0) {
            phase = RunnerPhase.LeadIn(index)
            return beginCountdown(slot.leadInBefore, t) { RunnerCue.LeadInTick(it) }
        }
        return arm(index, t)
    }

    /// Hand the rep over to the climber. With a gauge that means WAITING for the load;
    /// without one there is nothing to wait for, so the hold starts on the same beat —
    /// which is exactly what a count-in is for.
    private fun arm(index: Int, t: Double): List<RunnerCue> {
        if (!timerOnly) {
            phase = RunnerPhase.Armed(index)
            return listOf(RunnerCue.Armed(slots[index].side))
        }
        phase = RunnerPhase.Working(index)
        repStartedElapsedSeconds = recordedElapsed
        lastTickAt = t
        return listOf(RunnerCue.Armed(slots[index].side), RunnerCue.RepStarted)
    }

    /// The slot NOT yet recorded — the one a skip or abort applies to. Differs from
    /// `phase.slotIndex` during `.resting(i)`, where slot `i` is already in `results`;
    /// treating it as current booked it twice and reported "37 of 36 pulls".
    fun pending(inPhase: RunnerPhase): Int? = when (inPhase) {
        is RunnerPhase.LeadIn -> if (inPhase.slot < slots.size) inPhase.slot else null
        is RunnerPhase.Armed -> if (inPhase.slot < slots.size) inPhase.slot else null
        is RunnerPhase.Working -> if (inPhase.slot < slots.size) inPhase.slot else null
        // `.releasing` sits on the same side of the line as `.resting`: the rep is
        // already in `results` by the time either is entered.
        is RunnerPhase.Releasing -> if (inPhase.slot + 1 < slots.size) inPhase.slot + 1 else null
        is RunnerPhase.Resting -> if (inPhase.slot + 1 < slots.size) inPhase.slot + 1 else null
        is RunnerPhase.Paused -> pending(inPhase.before)
        RunnerPhase.Idle, RunnerPhase.Finished -> null
    }

    /// Record the current rep and move on. The ONE place a rep is closed, so every
    /// outcome — completed, early, skipped, aborted — books the same fields.
    private fun endCurrentRep(outcome: RepOutcome, t: Double): List<RunnerCue> {
        val index = pending(phase) ?: return emptyList()
        if (phase.isPaused) return emptyList()
        // A rep that never started still has to be recorded: a session summary with a
        // missing rep reads as a bug, and "you skipped it" is information.
        _results.add(summary(slots[index], outcome))
        val cues = mutableListOf<RunnerCue>(RunnerCue.RepEnded(outcome == RepOutcome.completed))
        cues += advance(index, t)
        return cues
    }

    private fun summary(slot: RepSlot, outcome: RepOutcome): RepSummary {
        val held = accruedMicros.toDouble() / 1_000_000
        return RepSummary(
            setIndex = slot.setIndex,
            repIndex = slot.repIndex,
            side = slot.side,
            grip = slot.grip,
            targetSeconds = slot.holdSeconds,
            heldSeconds = held,
            peakKg = peakKg,
            avgKg = if (accruedMicros > 0uL) weightedKgMicros / accruedMicros.toDouble() else 0.0,
            // The load THIS hand was asked for, frozen with the rep that was asked for
            // it — see `RepSummary.targetLoKg`.
            targetLoKg = slot.targetBand?.start,
            targetHiKg = slot.targetBand?.endInclusive,
            outcome = outcome,
            startedElapsedSeconds = repStartedElapsedSeconds,
            endedElapsedSeconds = recordedElapsed,
        )
    }

    /// Move past `index`: rest if the slot calls for one, otherwise straight into the
    /// next rep; finish if there is none.
    private fun advance(index: Int, t: Double): List<RunnerCue> {
        val cues = mutableListOf<RunnerCue>()
        if (slots[index].isLastOfSet) {
            cues.add(RunnerCue.SetCompleted(slots[index].setIndex))
        }
        val next = index + 1
        if (next >= slots.size) {
            phase = RunnerPhase.Finished
            heldSeconds = 0.0
            cues.add(RunnerCue.SessionCompleted)
            return cues
        }
        // `restAfter` is 0 on the very last rep, so this is also what stops the session
        // ending with a pointless countdown.
        if (slots[index].restAfter > 0) {
            resetRepAccumulators()
            // Hold the countdown until the hand is off the edge, but only when a release
            // can be observed: with no gauge or a dead link the session would sit on
            // LET GO forever, which is worse than starting the rest a few seconds early.
            if (plan.waitForReleaseBeforeRest && !timerOnly && !linkIsDown && currentKg >= releaseKg) {
                phase = RunnerPhase.Releasing(index)
                return cues
            }
            phase = RunnerPhase.Resting(index)
            cues += beginCountdown(slots[index].restAfter, t) { RunnerCue.RestTick(it) }
            return cues
        }
        cues += enter(next, t)
        return cues
    }

    /// Force has dropped off the edge after a completed hold — start the rest that was
    /// waiting for it. Also the path taken when the link dies mid-wait.
    private fun beginRest(afterIndex: Int, t: Double): List<RunnerCue> {
        phase = RunnerPhase.Resting(afterIndex)
        return beginCountdown(slots[afterIndex].restAfter, t) { RunnerCue.RestTick(it) }
    }

    private fun skipSet(t: Double): List<RunnerCue> {
        val index = pending(phase) ?: return emptyList()
        if (phase.isPaused) return emptyList()
        val set = slots[index].setIndex
        // Everything still owed in this set is recorded as skipped, so the log says
        // what happened rather than quietly containing fewer reps than the plan.
        var i = index
        while (i < slots.size && slots[i].setIndex == set) {
            _results.add(summary(slots[i], RepOutcome.skipped))
            resetRepAccumulators()
            i += 1
        }
        val cues = mutableListOf<RunnerCue>(
            RunnerCue.RepEnded(false),
            RunnerCue.SetCompleted(set),
        )
        if (i >= slots.size) {
            phase = RunnerPhase.Finished
            heldSeconds = 0.0
            cues.add(RunnerCue.SessionCompleted)
            return cues
        }
        cues += enter(i, t)
        return cues
    }

    private fun abort(t: Double): List<RunnerCue> {
        if (phase is RunnerPhase.Finished) return emptyList()
        // Only the rep in flight is aborted; finished reps keep their outcome.
        // `pending(in:)` resolves through `.paused`, so ending while paused still books
        // the rep with what it had accrued. No `!phase.isPaused` guard: it silently
        // dropped that rep, breaking `HoldToEndButton`'s "nothing is destroyed" promise.
        // See `abortWhilePausedMidHoldStillRecordsTheRepAndItsAccruedTime`.
        pending(phase)?.let { index ->
            _results.add(summary(slots[index], RepOutcome.aborted))
        }
        phase = RunnerPhase.Finished
        heldSeconds = 0.0
        return listOf(RunnerCue.SessionCompleted)
    }

    // MARK: - Countdowns (wall clock)

    private fun beginCountdown(seconds: Int, t: Double, tick: (Int) -> RunnerCue): List<RunnerCue> {
        countdownEndsAt = t + seconds.toDouble()
        countdownDuration = seconds.toDouble()
        lastAnnouncedSecond = seconds
        return listOf(tick(seconds))
    }

    private fun tick(t: Double): List<RunnerCue> {
        // With no gauge the tick IS the hold's clock, filling the same accumulator the
        // force path fills, so every readout reads one number.
        val current = phase
        if (timerOnly && current is RunnerPhase.Working) {
            return holdTick(current.slot, t)
        }
        if (!phase.isCountingDown) return emptyList()
        val remaining = countdownSeconds(t)
        if (remaining >= lastAnnouncedSecond) return emptyList()
        lastAnnouncedSecond = remaining
        if (remaining <= 0) return countdownFinished(t)
        return when (phase) {
            is RunnerPhase.LeadIn -> listOf(RunnerCue.LeadInTick(remaining))
            is RunnerPhase.Resting -> listOf(RunnerCue.RestTick(remaining))
            else -> emptyList()
        }
    }

    /// The gauge-free hold, advanced by wall time. Deltas are CLAMPED to a second: the
    /// ticker is a sleep loop, so a backgrounded phone or stalled main thread would
    /// otherwise bank time never spent on the edge.
    private fun holdTick(index: Int, t: Double): List<RunnerCue> {
        try {
            val last = lastTickAt ?: return emptyList()
            accruedMicros += (max(0.0, min(t - last, 1.0)) * 1_000_000).toULong()
            heldSeconds = accruedMicros.toDouble() / 1_000_000

            val cues = mutableListOf<RunnerCue>()
            val targetMicros = max(0, slots[index].holdSeconds).toULong() * 1_000_000uL
            if (!announcedHalfway && targetMicros > 0uL && accruedMicros * 2uL >= targetMicros) {
                announcedHalfway = true
                cues.add(RunnerCue.RepHalfway)
            }
            if (accruedMicros < targetMicros) return cues
            return cues + endCurrentRep(RepOutcome.completed, t)
        } finally {
            lastTickAt = t
        }
    }

    private fun countdownFinished(t: Double): List<RunnerCue> {
        val current = phase
        return when (current) {
            is RunnerPhase.LeadIn -> arm(current.slot, t)
            is RunnerPhase.Resting -> {
                val next = current.slot + 1
                if (next >= slots.size) {
                    phase = RunnerPhase.Finished
                    listOf(RunnerCue.SessionCompleted)
                } else {
                    enter(next, t)
                }
            }
            else -> emptyList()
        }
    }

    // MARK: - Force samples (device clock)

    private fun receive(sample: ForceSample, t: Double): List<RunnerCue> {
        if (!accept(sample)) return emptyList()
        currentKg = sample.kg
        try {
            return when (val current = phase) {
                is RunnerPhase.Armed -> armedSample(sample, current.slot, t)
                is RunnerPhase.Working -> workingSample(sample, current.slot, t)
                is RunnerPhase.Releasing -> {
                    // The ONE place a sample starts a countdown, on the rep's own release
                    // threshold, so "off the edge" means one thing in the whole engine.
                    if (sample.kg >= releaseKg) emptyList()
                    else beginRest(current.slot, t)
                }
                else ->
                    // Samples outside a rep still update the live readout — the gauge is on
                    // screen during rests — but must never move a clock.
                    emptyList()
            }
        } finally {
            lastSample = sample
        }
    }

    /// Accept or reject one sample at its notification boundary. Wrapping subtraction
    /// distinguishes a genuine UInt32 rollover (a small forward delta) from an older
    /// epoch (a delta in the backward half of the counter's range). Once a batch start
    /// is stale, every interior sample remains rejected even if its raw timestamp moves
    /// beyond the previous high-water mark.
    private fun accept(sample: ForceSample): Boolean {
        if (sample.isBatchStart) {
            rejectingBatch = false
            highWaterMicros?.let { mark ->
                val forward = sample.deviceMicros - mark
                if (forward == 0u || forward >= 0x8000_0000u) {
                    rejectingBatch = true
                }
            }
        }
        if (rejectingBatch) return false
        highWaterMicros = sample.deviceMicros
        return true
    }

    private fun armedSample(sample: ForceSample, index: Int, t: Double): List<RunnerCue> {
        // INSIDE the gate, not merely above its floor: with a target band the rep does
        // not start until the load is actually in range, so blowing straight through the
        // ceiling on the way up never banks the first tenth of a second at the wrong load.
        if (!gate(slots[index]).admits(sample.kg)) {
            engagedSince = null
            return emptyList()
        }
        val since = engagedSince
        if (since == null) {
            engagedSince = sample.deviceMicros
            return emptyList()
        }
        // Debounce measured in DEVICE time, not arrival time: a stalled UI thread
        // must not be able to satisfy it.
        if ((sample.deviceMicros - since).toULong() < engageDebounceMicros) return emptyList()
        phase = RunnerPhase.Working(index)
        resetRepAccumulators()
        repStartedElapsedSeconds = recordedElapsed
        peakKg = sample.kg
        return listOf(RunnerCue.RepStarted)
    }

    private fun workingSample(sample: ForceSample, index: Int, t: Double): List<RunnerCue> {
        val cues = mutableListOf<RunnerCue>()
        peakKg = max(peakKg, sample.kg)
        val slot = slots[index]
        val repGate = gate(slot)
        val targetMicros = max(0, slot.holdSeconds).toULong() * 1_000_000uL

        // Crossing the outer HOLD edge latches the stall. Samples in the hysteresis
        // dead band keep both the latch and its one-shot warning; only re-entering the
        // stricter ENGAGE range resumes accrual and arms the next warning.
        if (mustReengage) {
            if (!repGate.admits(sample.kg)) {
                if (!repGate.holds(sample.kg)) {
                    isDropped = sample.kg < repGate.releaseLo
                    isOverTarget = !isDropped
                }
                return cues
            }
            mustReengage = false
            warnedThisDip = false
            isDropped = false
            isOverTarget = false
        }

        if (repGate.holds(sample.kg)) {
            warnedThisDip = false
            isDropped = false
            isOverTarget = false
            // Accrue ONLY from sample-timestamp deltas, never from arrival time: arrival
            // is subject to BLE batching and UI hitches, and crediting it would let a
            // stalled phone invent hang time nothing measured. How much of one delta may
            // be banked is `creditableDelta`, and it differs by which clock stamped it.
            val previous = lastSample
            if (previous != null && !linkIsDown) {
                val credited = creditableDelta(sample.microsSince(previous).toULong())
                if (credited > 0uL) {
                    accruedMicros += credited
                    weightedKgMicros += sample.kg * credited.toDouble()
                }
            }
            heldSeconds = accruedMicros.toDouble() / 1_000_000
            if (!announcedHalfway && targetMicros > 0uL && accruedMicros * 2uL >= targetMicros) {
                announcedHalfway = true
                cues.add(RunnerCue.RepHalfway)
            }
            if (accruedMicros >= targetMicros) {
                return cues + endCurrentRep(RepOutcome.completed, t)
            }
            return cues
        }

        // Outside the gate: the clock stops and the rep STAYS ALIVE, for as long as it
        // takes. A stall costs exactly the seconds it costs and nothing else — the same
        // contract whether you came off the edge or went over the top of the range.
        mustReengage = true
        isDropped = sample.kg < repGate.releaseLo
        isOverTarget = !isDropped
        if (!warnedThisDip) {
            warnedThisDip = true
            // One cue for both, because it says the same thing — the clock stopped. WHICH
            // way you left the range is a job for the screen, which can use words.
            cues.add(RunnerCue.DropoutWarning)
        }
        return cues
    }

    /// How much of one sample-to-sample gap may be banked as hang time. Two clocks, two
    /// opposite rules.
    ///
    /// A DEVICE clock (the Progressor's) measures the gauge's own sampling, so at ~80 Hz a
    /// delta over 200 ms is a broken timeline (retransmission, restarted epoch, corrupt
    /// payload). It credits NOTHING; `lastSample` still advances so the next coherent
    /// delta resumes.
    ///
    /// A SYNTHETIC clock stamps readings with host uptime, so its deltas measure the
    /// RADIO. An 8 Hz scale is already 125 ms apart and one coalesced advertisement
    /// doubles that, so the 200 ms rule would under-count every hang. The gap is CLAMPED
    /// instead, as `holdTick` does: credit what the cap can account for, never the
    /// surplus — a two-second RF hole is a fact about the antenna, not the hand.
    ///
    /// KNOWN LIMITATION: on a gauge that packs several readings into one notification,
    /// `avgKg` is sampled at the NOTIFICATION rate. Interior readings share the batch's
    /// stamp, so their zero delta never reaches `weightedKgMicros`. `accruedMicros` (the
    /// next delta carries the whole interval) and `peakKg` (taken before this gate) stay
    /// exact. Weighting interior samples by a share of the batch delta is the fix once a
    /// real ForceBoard can be measured; guessing the spacing now would fabricate it.
    private fun creditableDelta(delta: ULong): ULong {
        val ceiling = creditedGapCeilingMicros
            ?: return if (delta <= maxCreditableDeltaMicros.toULong()) delta else 0uL
        return minOf(delta, ceiling)
    }

    // MARK: - Link and pause

    private fun linkLost(t: Double): List<RunnerCue> {
        if (linkIsDown) return emptyList()
        linkIsDown = true
        currentKg = 0.0
        breakTimeline()
        // A rep waits for the gauge to come back; a REST does not. Waiting for a release
        // nobody can observe would strand the session on a phase that has no clock and
        // no way out but Skip — so the rest starts now and the countdown is honest.
        val current = phase
        if (current is RunnerPhase.Releasing) {
            return listOf(RunnerCue.ConnectionLost) + beginRest(current.slot, t)
        }
        if (current is RunnerPhase.Paused && current.before is RunnerPhase.Releasing) {
            val index = current.before.slot
            beginRest(index, pausedAt)
            phase = RunnerPhase.Paused(RunnerPhase.Resting(index))
        }
        return listOf(RunnerCue.ConnectionLost)
    }

    private fun linkRestored(): List<RunnerCue> {
        linkIsDown = false
        breakTimeline()
        return emptyList()
    }

    private fun pause(t: Double): List<RunnerCue> {
        when (phase) {
            RunnerPhase.Idle, RunnerPhase.Finished, is RunnerPhase.Paused -> return emptyList()
            else -> {
                pausedAt = t
                phase = RunnerPhase.Paused(phase)
                breakTimeline()
                return emptyList()
            }
        }
    }

    private fun resume(t: Double): List<RunnerCue> {
        val current = phase
        if (current !is RunnerPhase.Paused) return emptyList()
        val inner = current.before
        // Shift the countdown by however long we were away, so a pause costs no rest
        // and steals none either.
        if (inner.isCountingDown) countdownEndsAt += t - pausedAt
        phase = inner
        breakTimeline()
        return emptyList()
    }

    /// Forget the previous sample so the next delta is not measured across a gap.
    /// Called for every explicit discontinuity: pause, resume, disconnect, reconnect,
    /// tare, and a watchdog-driven stream restart.
    private fun breakTimeline() {
        lastSample = null
        highWaterMicros = null
        rejectingBatch = false
        engagedSince = null
        lastTickAt = null
    }

    private fun resetRepAccumulators() {
        repStartedElapsedSeconds = null
        accruedMicros = 0uL
        weightedKgMicros = 0.0
        peakKg = 0.0
        heldSeconds = 0.0
        engagedSince = null
        lastTickAt = null
        warnedThisDip = false
        mustReengage = false
        announcedHalfway = false
        isDropped = false
        isOverTarget = false
    }

    // MARK: - Readouts for the UI

    val currentSlot: RepSlot?
        get() {
            val index = phase.slotIndex ?: return null
            return if (index < slots.size) slots[index] else null
        }

    /// The rep the SCREEN should describe — during a rest, the one you are about to do
    /// (Nuri, 2026-08-04). Resting is preparation; the rep behind you is the one thing
    /// you do not need. `.releasing` does NOT look forward: you are still on the edge,
    /// and swapping the grip under a hand that has not let go describes nothing real.
    val displaySlot: RepSlot?
        get() {
            if (unpaused is RunnerPhase.Resting) {
                val index = pending(phase)
                if (index != null && index < slots.size) return slots[index]
            }
            return currentSlot
        }

    /// Whether the running rest is a SET BREAK. Keyed to the slot just finished: it
    /// describes the rest itself, so it keeps looking back while the rest look forward
    /// (`isLastOfSet` on `displaySlot` would say "set break" before a set's last rep).
    val isSetBreak: Boolean
        get() {
            val resting = unpaused as? RunnerPhase.Resting ?: return false
            if (resting.slot >= slots.size) return false
            return slots[resting.slot].isLastOfSet
        }

    /** Persists from rest/lead-in through the first pull, including skipped sets. */
    val newGripID: String? get() {
        val slot = displaySlot ?: return null
        val previous = results.lastOrNull() ?: return null
        return slot.id.takeIf { slot.grip != previous.grip }
    }

    /** Three credited seconds of notice, without changing the current instruction. */
    val upcomingGrip: GripSpec? get() {
        val index = (unpaused as? RunnerPhase.Working)?.slot ?: return null
        if (index + 1 >= slots.size) return null
        val current = slots[index]
        val next = slots[index + 1]
        return next.grip.takeIf { current.restAfter == 0 && next.leadInBefore == 0 &&
            current.grip != next.grip && current.holdSeconds - heldSeconds <= 3 }
    }

    /// Whether the running rest leads into a DIFFERENT grip (Nuri, 2026-08-19: easy to
    /// miss while you shake out). Compared against the slot that OWNS the rest, since
    /// `displaySlot` has already swapped forward and would compare a slot with itself.
    /// `.releasing` is excluded: the warning belongs to the rest that follows. Whole
    /// specs, because an edge change alone moves your hand.
    val nextGripDiffers: Boolean
        get() {
            val resting = unpaused as? RunnerPhase.Resting ?: return false
            if (resting.slot >= slots.size) return false
            val next = pending(phase) ?: return false
            return slots[next].grip != slots[resting.slot].grip
        }

    /// The phase with any `.paused` wrapper removed — a paused rest is still a rest for
    /// every purpose the screen has.
    private val unpaused: RunnerPhase
        get() {
            val current = phase
            return if (current is RunnerPhase.Paused) current.before else current
        }

    /// 0…1 through the current rep's hold, for the measured runner's progress bar. The
    /// timer-only dial deliberately uses `phaseRemainingFraction` instead: lead-in and
    /// rest have no held seconds to report.
    val repProgress: Double
        get() {
            val slot = currentSlot ?: return 0.0
            if (slot.holdSeconds <= 0) return 0.0
            return min(1.0, heldSeconds / slot.holdSeconds.toDouble())
        }

    /// Whole seconds left on whatever is counting down, for the big numeral.
    ///
    /// A paused phase reads against `pausedAt`: the deadline shifts only on resume, so
    /// the current time would keep draining a paused rest behind the pause button.
    fun secondsRemaining(at: Double): Int? {
        val phaseToRead: RunnerPhase
        val clock: Double
        if (phase.isPaused) {
            phaseToRead = unpaused
            clock = pausedAt
        } else {
            phaseToRead = phase
            clock = at
        }
        if (!phaseToRead.isCountingDown) return null
        return countdownSeconds(clock)
    }

    /** Unrounded interval for countdown consumers; stored timing remains unchanged. */
    fun countdownRemainingInterval(at: Double): Double? {
        if (!unpaused.isCountingDown) return null
        val clock = if (phase.isPaused) pausedAt else at
        return max(0.0, min(countdownDuration, countdownEndsAt - clock))
    }

    private fun countdownSeconds(clock: Double): Int {
        val remaining = countdownEndsAt - clock
        if (remaining <= 0) return 0
        // Uptime arithmetic can turn 30 seconds into 30.00000000000003. Correct
        // only floating-point resolution errors, not genuine fractions. A positive
        // interval never rounds to zero, so the actual deadline still ends the phase.
        val nearest = kotlin.math.round(remaining)
        val tolerance = 2 * max(Math.ulp(countdownEndsAt), Math.ulp(clock))
        if (nearest >= 1 && kotlin.math.abs(remaining - nearest) <= tolerance) {
            return nearest.toInt()
        }
        return ceil(remaining).toInt()
    }

    /// The fraction of the CURRENT phase remaining, 1…0 as its clock runs down.
    ///
    /// `repProgress` is hold-only, zero through lead-in and rest; timer-only needs one
    /// dial filled in all three live phases. `RunnerSession` gates this to `timerOnly`
    /// so measured sessions do not publish a continuous value per force sample.
    fun phaseRemainingFraction(at: Double): Double? {
        val phaseToRead = unpaused
        val clock: Double = if (phase.isPaused) pausedAt else at

        return when (phaseToRead) {
            is RunnerPhase.LeadIn, is RunnerPhase.Resting -> {
                if (countdownDuration <= 0) null
                else max(0.0, min(1.0, (countdownEndsAt - clock) / countdownDuration))
            }
            is RunnerPhase.Working -> {
                val index = phaseToRead.slot
                if (!timerOnly || index >= slots.size || slots[index].holdSeconds <= 0) null
                else max(0.0, min(1.0, 1 - heldSeconds / slots[index].holdSeconds.toDouble()))
            }
            else -> null
        }
    }

    /// Reps closed out of reps planned — the "12 of 36" line.
    val completedRepCount: Int get() = _results.size
    val plannedRepCount: Int get() = slots.size

    /// 1-based set position, for "SET 2 OF 6". Reads `displaySlot`, so a set break says
    /// the set you are about to start rather than the one you just finished — the same
    /// forward tense as the grip line and the pull count beside it.
    val setNumber: Int? get() = displaySlot?.let { it.setIndex + 1 }

    /// STORED, not computed: `RunnerSession.publish()` reads it on EVERY sample, where
    /// recomputing it cost ~96,000 map-and-Set passes a session.
    val setCount: Int = this.plan.sets.size

    /// 1-based rep position within the current set.
    val repNumberInSet: Int? get() = displaySlot?.let { it.repIndex + 1 }

    val repsInCurrentSet: Int?
        get() {
            val slot = displaySlot ?: return null
            return PlanMath.repCount(plan.sets[slot.setIndex], plan.handMode)
        }

    val isFinished: Boolean get() = phase is RunnerPhase.Finished

    /// A notification began at or behind the accepted device-time high-water mark, so
    /// its whole batch is being rejected fail-closed. RunnerSession mirrors this into
    /// its snapshot for the explicitly armed foreground recovery path.
    val isRejectingStaleBatches: Boolean get() = rejectingBatch

    /// Any rep actually held — what decides whether a log is worth writing.
    val didAnyWork: Boolean get() = _results.any { it.heldSeconds > 0 }
}
