// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.ble.SystemHostClock
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.RunnerCue
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.SamplePacing
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SessionRunner
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.engine.StaleBatchHealDecision
import run.nuri.getagrip.engine.StaleBatchHealer
import run.nuri.getagrip.store.DeviceStore
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

/// Owns a running session: the state machine, the clock that drives its countdowns, the
/// gauge subscription, and the cue playback.
///
/// TRANSLATION NOTE (from Sources/Runner/RunnerSession.swift): the iOS type is an
/// `@Observable @MainActor final class`; this is a `@Stable` class holding Compose
/// snapshot state, and it is a CLASS for exactly the same load-bearing reason. The gauge
/// hands samples to a callback ~80 times a second and that callback has to mutate the
/// runner — a composable is a function, so state captured in one is not something a
/// long-lived closure can write through. One identity, nothing to lose.
///
/// The three pure policies iOS keeps in this file (`StaleBatchHealer`,
/// `BackgroundPausePolicy`, the silence threshold) live in `:engine`'s `RunnerPolicies.kt`
/// instead — see its own translation note. Everything else moves across unchanged.
@Stable
class RunnerSession(
    /// The routine's plan, as authored. `SessionRunner` takes its `executable` copy.
    val plan: SessionPlan,

    /// Frozen with the session — a routine renamed or deleted tomorrow must not rewrite
    /// what today's log says it was.
    val routineName: String,

    private val device: DeviceStore,

    /// Every max on file, by grip AND hand.
    ///
    /// Percentage targets become kilograms at the moment the session begins and never move
    /// again: the runner executes concrete loads and the log freezes them, so a max
    /// recorded next month cannot rewrite what this morning told you to pull.
    ///
    /// **The plan is NOT pre-baked** — `SessionRunner` resolves per REP instead, because a
    /// set covers both hands and the two hands do not have the same max. Baking a single
    /// band onto the set here would hand both hands the same kilograms and silently undo
    /// per-hand loads; the freeze is preserved by capturing the TABLE once.
    private val maxes: MaxTable = MaxTable(),

    /// Run the whole plan on the clock with no gauge at all — see `SessionRunner.timerOnly`.
    /// Nothing here touches `DeviceStore` in that mode: no connect, no stream, no sample
    /// callback, no watchdog. The store is still held because the screen shares one view,
    /// and because a session started without a gauge must not start quietly using one that
    /// happens to be connected.
    val timerOnly: Boolean = false,

    /// The ticker and the watchdog live here. `RunnerHost` hands over a scope tied to the
    /// composition so both die with the screen even if `end()` is never reached.
    private val scope: CoroutineScope,

    /// Injected because `SystemClock.elapsedRealtimeNanos()` reads a stubbed zero forever
    /// in a JVM unit test — the same seam `DeviceStore` and the trace already use.
    private val clock: HostClock = SystemHostClock,

    /// Where a cue is actually played. Defaulted to nothing so the engine's timing can be
    /// driven in a test with no audio session, no vibrator and no waiting.
    private val cues: CueSink = CueSink {},

    /// Where the Live Update is published. `AndroidActivityPublisher` in the app; nothing
    /// in a test, so a whole session can be driven with no notification manager.
    private val activity: ActivityPublisher = NoActivityPublisher,

    /// Where a session asks the OS to keep it alive — `SessionForegroundService` in the
    /// app, nothing in a test. Started only for a MEASURED session on a CONNECTED gauge
    /// that sustains background streaming; see `updateForegroundService`.
    private val service: SessionServiceController = NoSessionServiceController,
) {

    /// **NOT Compose state, and that is the whole performance story of this screen.**
    ///
    /// `SessionRunner` is mutated by every force sample, 80× a second. Held in a
    /// `mutableStateOf` and read piecemeal by the screen, that rebuilds counters, prompt,
    /// grip line and every control 80×/s to move two numbers. The engine state stays a
    /// plain field; what the SCREEN needs is republished as `snapshot` only when it
    /// actually changes.
    val runner: SessionRunner = SessionRunner(
        plan = plan,
        maxes = maxes,
        timerOnly = timerOnly,
        // Keyed to the CAPABILITY, never to the kind: a gauge with no clock of its own is
        // stamped from host uptime by the client, so one sample's delta is an arrival gap
        // and may only ever buy the cap. The Progressor's own µs deltas are truth and stay
        // uncapped. See `SessionRunner.maxCreditedSampleGapSeconds`.
        maxCreditedSampleGapSeconds =
            if (device.gaugeCapabilities.hasDeviceClock) null else SamplePacing.syntheticClockGapCapSeconds,
    )

    /// The coarse, view-shaped view of the runner. A data class assigned only on a real
    /// change, so a second of holding invalidates the UI once or twice instead of ~80 times.
    var snapshot: RunnerSnapshot by mutableStateOf(RunnerSnapshot())
        private set

    /// Measured fraction, separate from the coarse snapshot so only the small bar
    /// updates with samples. The view settles toward this value and never extrapolates.
    var repProgress: Float by mutableFloatStateOf(0f)
        private set
    val repProgressBucket: Int get() = (repProgress * 100).roundToInt()

    /// Timer-only ring state. Keep this 10 Hz fraction off the screen snapshot too.
    var phaseRemainingFraction: Double? by mutableStateOf(null)
        private set

    /// Bumped on every real republish. A test seam for the change-guard itself, which is
    /// otherwise only observable by watching how often a view recomposes.
    var snapshotRevision: Int = 0
        private set

    var startedAt: Instant = Instant.now()
        private set

    /// Monotonic seconds the countdowns are measured against. NOT observable: the screen
    /// reads whole seconds off `snapshot`, so republishing this 10× a second would
    /// invalidate every reader for a number none of them display.
    var now: Double = clock.uptimeSeconds()
        private set

    /// How many force samples have reached this session. Zero while a workout is under way
    /// means the gauge is connected but not talking, which is the one failure the screen
    /// must not render as "0.0 kg" — that reads as a device measuring nothing rather than
    /// an app receiving nothing.
    var samplesSeen: Int = 0
        private set

    // MARK: - Read once, at construction

    /// Whether the gauge stamps its own samples. The Progressor does; every ported device
    /// is stamped from host uptime at ingestion, which is why a re-kick means something
    /// different to each — see `restartStreamArmingStaleBatchHeal`.
    ///
    /// Read ONCE, like the timing policy below: what this session is driving must not
    /// change under it because a different gauge was selected in Settings mid-workout.
    private val hasDeviceClock: Boolean = device.gaugeCapabilities.hasDeviceClock

    /// Whether this gauge can go on delivering samples with the app in the background —
    /// the capability that decides whether the foreground service is worth running at all,
    /// and the same one `BackgroundPausePolicy` reads when the app leaves the foreground.
    ///
    /// Read ONCE, for the same reason as above: what this session is driving must not change
    /// under it because a different gauge was selected in Settings mid-workout.
    private val sustainsBackgroundStreaming: Boolean =
        device.gaugeCapabilities.sustainsBackgroundStreaming

    /// How much silence means the stream needs re-kicking, for THIS gauge — eight samples'
    /// worth, floored at the Progressor's 0.8 s. See `SamplePacing.silenceThreshold`.
    val silenceRestartSeconds: Double = SamplePacing.silenceThreshold(
        forRate = device.gaugeCapabilities.nominalSampleRate,
        isBroadcast = device.gaugeCapabilities.isBroadcast,
    )

    private val gaugeKind: GaugeKind = device.gaugeKind

    // MARK: - Internal state

    private var ticker: Job? = null
    private var streamWatchdog: Job? = null
    private var hasStarted = false
    private var hasBegun = false
    private var hasEnded = false
    private var lastSampleAt: Double = 0.0
    private var staleBatchHealArmedAt: Double? = null
    private var consecutiveRejectingChecks = 0
    private var lastStaleBatchHealAt: Double? = null
    private var lastActivitySignature: ActivitySignature? = null
    private var finishedAt: Instant? = null

    // MARK: - Lifecycle

    fun begin() {
        // Idempotent. A `DisposableEffect` can be re-run when its key changes, and a second
        // `begin()` would re-acquire the wake lock and re-arm the ticker.
        if (hasBegun) return
        hasBegun = true

        startedAt = Instant.now()
        runner.beginRecording(clock.uptimeSeconds())
        now = clock.uptimeSeconds()

        // begin only starts the audio queue; native setup and synthesis happen on its
        // worker. Register it before Start so a zero-lead-in routine keeps its first cue.
        cues.begin()

        if (!timerOnly) {
            device.onSample = { sample ->
                samplesSeen += 1
                lastSampleAt = clock.uptimeSeconds()
                send(RunnerEvent.Sample(sample))
            }
        }

        // Wall-clock heartbeat. Countdowns live here; work time never does — that only ever
        // comes from the device's own timestamps inside the runner.
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                tickNow()
            }
        }

        if (timerOnly) {
            // The session starts on the spot: there is nothing to connect to and nothing to
            // tare, and the count-in is the only preamble a clock needs.
            send(RunnerEvent.Start)
        } else if (device.state.isConnected) {
            startIfReady(StreamStartCause.initial)
        } else {
            device.connect()
        }

        // PUBLISH FIRST. The activity state reads the snapshot, and until this runs the
        // snapshot is still the empty default — which is how the very first card went out
        // saying "Pull 0 of 12" with no countdown, and then sat there until the next phase
        // change happened to correct it.
        publish()

        // Best-effort and deliberately last: an activity that cannot start (permission
        // refused, budget spent) must never disturb a workout that is already under way.
        val grip = runner.displaySlot?.grip ?: plan.executable.sets.firstOrNull()?.grip
        if (grip != null) {
            activity.start(
                routineName = routineName,
                plannedReps = runner.plannedRepCount,
                setCount = runner.setCount,
                state = activityState(grip),
            )
        }

        // AFTER the publisher, never before: the service's `startForeground` adopts the card
        // the publisher just built, and starting first would flash a placeholder.
        updateForegroundService()
    }

    fun end() {
        // Only ever tear down a session we actually started, and only once: a stray dispose
        // reaching this used to stop the stream out from under a live session, which looks
        // exactly like a dead gauge.
        if (!hasBegun || hasEnded) return
        hasEnded = true

        ticker?.cancel()
        ticker = null
        streamWatchdog?.cancel()
        streamWatchdog = null
        device.onSample = null
        // Never leave the gauge streaming behind us: it drains its own battery for ten
        // minutes and the user blames the app.
        if (device.isStreaming) device.stopStreaming(StreamStopCause.sessionEnded)
        // Ended with the session, not left to expire: a card still saying "Pull" on the
        // lock screen after you have finished is worse than no card at all.
        activity.end()
        // And the service goes with it. A `connectedDevice` foreground service outliving
        // the session it exists for is an app holding the radio open for nothing — and the
        // one thing every OEM battery manager notices.
        if (serviceRunning) {
            serviceRunning = false
            service.end()
        }
        cues.end()
    }

    /// Start the foreground service once this session actually qualifies for one.
    ///
    /// Called from `begin()` and again from `connectionChanged`, because Start on Today is
    /// deliberately enabled while the gauge is still asleep: the session's real first phase
    /// is connect-and-tare, so at `begin()` there is often no link yet to keep alive.
    ///
    /// **Started once, never stopped early.** A link that drops mid-session is exactly when
    /// the process most needs to stay alive — to reconnect, and to go on showing RE-GRIP
    /// rather than freezing — so the service's life is the SESSION's life, not the link's.
    /// iOS gets the same shape for free: `bluetooth-central` is a capability of the app, not
    /// of the current connection.
    private fun updateForegroundService() {
        if (serviceRunning || timerOnly) return
        // A gauge-free session has nothing to keep alive: no samples are coming, so
        // `RunnerLifecycle` pauses it outright on the way out. Same for a broadcast scale,
        // whose scan the OS silences whatever we ask for.
        if (!sustainsBackgroundStreaming) return
        if (!device.state.isConnected) return
        serviceRunning = true
        service.begin()
    }

    private var serviceRunning = false

    /// The session's real first phase is connect-and-tare, which is why Start on Today is
    /// deliberately enabled while the gauge is still asleep.
    fun startIfReady(cause: StreamStartCause) {
        if (timerOnly || !device.state.isConnected) return
        // UNCONDITIONAL, deliberately. This used to be `if (!device.isStreaming)`, and that
        // guard could only ever SKIP the one command the session depends on — if
        // `isStreaming` was true while the gauge was not actually streaming (a stale flag
        // after a reconnect, a backgrounded app, a session torn down early), the start
        // opcode never went out and the whole workout sat at 0.0 kg with an empty trace,
        // while the live gauge screen worked fine because it sends its own. Re-sending
        // start to a streaming Progressor is harmless; not sending it is a dead session.
        if (hasStarted) {
            // The foreground kick lands here. A re-kick may start a fresh device timestamp
            // epoch, so it needs the same break-first ordering as the silence watchdog and
            // one bounded recovery opportunity for queued old-epoch data.
            restartStreamArmingStaleBatchHeal(cause)
            return
        }
        hasStarted = true
        // Tare FIRST, then start — the order the vendor's own app uses. The reversed order
        // tared a freshly started stream, and on real firmware that killed it.
        device.tare()
        device.startStreaming(StreamStartCause.initial)
        send(RunnerEvent.TareCommitted)
        send(RunnerEvent.Start)
        armStreamWatchdog()
    }

    /// Restart the stream whenever it falls SILENT — not merely if it never started.
    ///
    /// The first version fired three times and only when no sample had EVER arrived, which
    /// left a hole the first hardware session fell straight through: a stream that dies
    /// after a few samples (a tare mid-stream did exactly this) defeated the
    /// `samplesSeen == 0` guard, and the workout sat dead beside a connected gauge with
    /// nothing left trying to revive it. A session's stream is supposed to be CONTINUOUS,
    /// so silence while connected is always wrong and always worth a restart — the start
    /// command is harmless when the stream is alive, and this is one cheap check every
    /// 500 ms. It runs for the session's whole life; `end()` cancels it.
    private fun armStreamWatchdog() {
        streamWatchdog?.cancel()
        lastSampleAt = clock.uptimeSeconds()
        streamWatchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_MILLIS)
                if (!device.state.isConnected || runner.isFinished || runner.phase.isPaused) continue
                val silence = clock.uptimeSeconds() - lastSampleAt
                if (silence > silenceRestartSeconds) {
                    restartStreamArmingStaleBatchHeal(StreamStartCause.watchdog)
                    continue
                }

                // Raw BLE data is arriving but the engine may be rejecting it because a
                // queued pre-background burst re-anchored the high-water mark. Two 500 ms
                // observations make that state durable rather than a packet-order blip.
                consecutiveRejectingChecks =
                    if (snapshot.isRejectingStaleBatches) consecutiveRejectingChecks + 1 else 0
                applyStaleBatchHealDecision(clock.uptimeSeconds())
            }
        }
    }

    /// Restart the stream and NOTHING else — no tare, on any path.
    ///
    /// `startIfReady` is not a substitute: its first-start branch tares, so routing a manual
    /// wake through it would make "waking never tares" a property of which branch happened
    /// to run rather than a guarantee of the API. With the load unknown (that is what stale
    /// means) a tare is the one thing that must not happen here.
    fun wakeStream() {
        if (timerOnly || !device.state.isConnected) return
        restartStreamArmingStaleBatchHeal(StreamStartCause.manualWake)
    }

    /// Every RunnerSession-owned re-kick uses this path. Arming happens before the break,
    /// and the break remains immediately before the command that can reset the device clock.
    private fun restartStreamArmingStaleBatchHeal(cause: StreamStartCause) {
        // **No timeline break on a gauge with no clock of its own.** The break exists to
        // survive a Tindeq restarting its µs epoch mid-session; a synthetic stamp is host
        // uptime, which no device restart can rewind, so there is no epoch here to break —
        // and the break is not free. It nulls the accrual anchor and the arming debounce,
        // and at 8–10 Hz the watchdog can land one between every pair of samples: a screen
        // that looks alive, kg moving, on a rep that can neither arm nor finish. So the
        // re-kick is just the re-kick, and the single-shot heal machinery stays for the one
        // gauge whose clock can actually reset.
        if (!hasDeviceClock) {
            device.startStreaming(cause)
            return
        }
        staleBatchHealArmedAt = clock.uptimeSeconds()
        consecutiveRejectingChecks = 0
        send(RunnerEvent.StreamRestarted)
        device.startStreaming(cause)
    }

    private fun applyStaleBatchHealDecision(checkTime: Double) {
        val armedAt = staleBatchHealArmedAt
        val decision = StaleBatchHealer.decision(
            armed = armedAt != null,
            armAge = armedAt?.let { checkTime - it } ?: 0.0,
            consecutiveRejectingChecks = consecutiveRejectingChecks,
            timeSinceLastHeal = lastStaleBatchHealAt?.let { checkTime - it } ?: Double.POSITIVE_INFINITY,
        )
        when (decision) {
            StaleBatchHealDecision.hold -> Unit
            StaleBatchHealDecision.fire -> {
                // Consume BEFORE sending. The healing break cannot authorize itself again;
                // only another actual re-kick can create another opportunity.
                staleBatchHealArmedAt = null
                consecutiveRejectingChecks = 0
                lastStaleBatchHealAt = checkTime
                send(RunnerEvent.StreamRestarted)
            }
            StaleBatchHealDecision.expire -> {
                staleBatchHealArmedAt = null
                consecutiveRejectingChecks = 0
            }
        }
    }

    fun connectionChanged(isConnected: Boolean) {
        // A gauge waking up in your bag must not silently take over a session you chose to
        // run without it — the clock would suddenly start waiting for force.
        if (timerOnly) return
        send(if (isConnected) RunnerEvent.ConnectionRestored else RunnerEvent.ConnectionLost)
        if (isConnected) {
            startIfReady(if (hasStarted) StreamStartCause.reconnect else StreamStartCause.initial)
            // The link that `begin()` was waiting for. From here the session can survive
            // the screen locking, which is the whole reason the service exists.
            updateForegroundService()
        }
    }

    fun tare() {
        device.tare()
        send(RunnerEvent.TareCommitted)
    }

    // MARK: - The one funnel

    /// Every event in, every cue out, in one place — so there is exactly one line in the app
    /// that decides what a session sounds like. The list is also RETURNED, which is what
    /// lets a test assert a whole workout's cue schedule with no audio involved.
    private val announcedGrips = mutableSetOf<String>()

    fun send(event: RunnerEvent): List<RunnerCue> {
        val emitted = runner.handle(event, at = now, recordedAt = clock.uptimeSeconds())
        if (runner.isFinished && finishedAt == null) {
            finishedAt = startedAt.plusNanos(((runner.finishedElapsedSeconds ?: 0.0) * 1e9).toLong())
        }
        publish()
        runner.newGripID?.let { if (announcedGrips.add(it)) cues.gripChanged() }
        for (cue in emitted) cues.play(cue)
        return emitted
    }

    /// Advance the wall clock and beat once. The ticker's body, exposed so a test can drive
    /// the same path against an injected clock instead of waiting on real time.
    fun tickNow(): List<RunnerCue> {
        now = clock.uptimeSeconds()
        return send(RunnerEvent.Tick)
    }

    /// Rebuild the view-facing snapshot, assigning ONLY on a real change. The guard is the
    /// point: Compose invalidates on every set, equal or not.
    private fun publish() {
        // `displaySlot`, not `currentSlot`: during a rest the screen describes the rep you
        // are about to do. See `SessionRunner.displaySlot`.
        val slot = runner.displaySlot
        // Published separately so only the progress bar reads this sample-rate state.
        repProgress = runner.repProgress.toFloat().coerceIn(0f, 1f)
        phaseRemainingFraction = if (timerOnly) runner.phaseRemainingFraction(now) else null
        val next = RunnerSnapshot(
            phase = runner.phase,
            isDropped = runner.isDropped,
            isOverTarget = runner.isOverTarget,
            isRejectingStaleBatches = runner.isRejectingStaleBatches,
            linkIsDown = runner.linkIsDown,
            isFinished = runner.isFinished,
            // A gauge-free session always "has signal": the clock is the signal, and the
            // no-readings notice would be complaining about a device nobody asked for.
            hasSignal = timerOnly || samplesSeen > 0,
            setNumber = runner.setNumber,
            setCount = runner.setCount,
            completedRepCount = runner.completedRepCount,
            plannedRepCount = runner.plannedRepCount,
            grip = slot?.grip,
            side = slot?.side,
            targetBand = slot?.targetBand,
            gripChangesNext = runner.nextGripDiffers,
            newGripID = runner.newGripID, upcomingGrip = runner.upcomingGrip,
            isSetBreak = runner.isSetBreak,
            // Whole seconds belong in the screen snapshot. The measured fraction stays
            // separate so smoothing the small progress bar cannot redraw the whole runner.
            secondsShown = secondsShown,
        )
        if (next != snapshot) {
            snapshot = next
            snapshotRevision += 1
        }
        pushActivity()
    }

    /// Mirror the snapshot into the activity — but only the parts it draws, and only when
    /// one of them actually moved. `secondsShown` deliberately does NOT reach it: the
    /// notification counts down on its own from `endsAt`, so forwarding a ticking number
    /// would spend the platform's update budget on frames it would have drawn anyway.
    private fun pushActivity() {
        val grip = snapshot.grip ?: return
        if (!activity.isRunning) return
        val signature = ActivitySignature(
            grip = grip,
            side = snapshot.side ?: Side.both,
            phase = activityPhase,
            setNumber = snapshot.setNumber ?: 1,
            repPosition = repPosition,
        )
        // **Compared WITHOUT `endsAt`, and that is the whole point.** `endsAt` is
        // `now + secondsRemaining`, so it drifts by fractions of a second on every one of
        // the ten publishes a second — comparing it would push ten times a second and spend
        // a whole session's update budget in the first minute. Recomputing the deadline only
        // when the rep or phase actually changes is also the CORRECT moment: a new phase is
        // exactly when a new countdown should start.
        if (signature == lastActivitySignature) return
        lastActivitySignature = signature
        activity.update(activityState(grip))
    }

    /// Everything that should force a push. Deliberately excludes the clock and the live
    /// load: both move continuously and neither is something the card needs told.
    private data class ActivitySignature(
        val grip: GripSpec,
        val side: Side,
        val phase: SessionActivityPhase,
        val setNumber: Int,
        val repPosition: Int,
    )

    /// Which pull you are ON. Floored at 1: a card reading "Pull 0 of 12" says the session
    /// has not started, and by the time anyone can see it, it has.
    private val repPosition: Int
        get() {
            if (snapshot.plannedRepCount <= 0) return 1
            return maxOf(1, minOf(snapshot.completedRepCount + 1, snapshot.plannedRepCount))
        }

    private fun activityState(grip: GripSpec): SessionActivityState {
        val phase = activityPhase
        // ARMED runs no clock — it waits on you, with no timeout, by design. So the deadline
        // goes out null and the hold LENGTH goes out instead.
        val isArmed = phase == SessionActivityPhase.armed
        return SessionActivityState(
            grip = grip,
            side = snapshot.side ?: Side.both,
            phase = phase,
            setNumber = snapshot.setNumber ?: 1,
            repPosition = repPosition,
            targetLoKg = snapshot.targetBand?.start,
            targetHiKg = snapshot.targetBand?.endInclusive,
            // An ABSOLUTE deadline, recomputed from the same countdown the screen shows.
            // Converting to a wall-clock instant here is what lets the notification tick
            // without us.
            endsAtEpochMillis = if (!isArmed && snapshot.secondsShown > 0) {
                System.currentTimeMillis() + snapshot.secondsShown * 1_000L
            } else {
                null
            },
            pendingSeconds = if (isArmed) snapshot.secondsShown else null,
        )
    }

    /// The runner's phases collapsed to the ones that change what you do with your hands —
    /// **and `armed` is one of them.** It used to fall into "pulling", which meant the card
    /// said "Pull" and ran a countdown while the edge was still hanging there untouched. It
    /// is its own beat, and its own colour: amber, the app's "waiting on you", against bleu
    /// for a clock that is genuinely running.
    private val activityPhase: SessionActivityPhase
        get() {
            val phase = snapshot.phase
            if (phase.isPaused) return SessionActivityPhase.paused
            return when (phase) {
                is RunnerPhase.Idle, is RunnerPhase.LeadIn -> SessionActivityPhase.leadIn
                is RunnerPhase.Armed -> SessionActivityPhase.armed
                is RunnerPhase.Working -> SessionActivityPhase.pulling
                // "Let go" shares rest's colour: the hold is banked either way, and a fifth
                // word on a glanceable card buys less than it costs.
                is RunnerPhase.Releasing, is RunnerPhase.Resting -> SessionActivityPhase.resting
                is RunnerPhase.Finished -> SessionActivityPhase.resting
                is RunnerPhase.Paused -> SessionActivityPhase.paused
            }
        }

    /// Whatever clock is running: the countdown during lead-in and rest, the hold counting
    /// DOWN while working, the target while armed.
    private val secondsShown: Int
        get() {
            runner.secondsRemaining(now)?.let { return it }
            val slot = runner.currentSlot ?: return 0
            val phase = runner.phase
            return when {
                phase is RunnerPhase.Working -> holdRemaining(slot.holdSeconds)
                phase is RunnerPhase.Paused && phase.before is RunnerPhase.Working ->
                    holdRemaining(slot.holdSeconds)
                phase is RunnerPhase.Paused -> slot.holdSeconds
                else -> slot.holdSeconds
            }
        }

    private fun holdRemaining(holdSeconds: Int): Int =
        maxOf(0, ceil(holdSeconds.toDouble() - runner.heldSeconds).toInt())

    // MARK: - Readouts

    val isFinished: Boolean get() = snapshot.isFinished

    /// Everything a store needs to write this session down, computed once when the summary
    /// asks for it.
    ///
    /// No store writes happen here on purpose: `RunnerHost` hands this to `onFinished` and
    /// the integrator decides what to persist. That keeps the whole runner previewable and
    /// testable with no database behind it.
    fun outcome(): SessionOutcome {
        val reps = runner.results
        val held = reps.sumOf { it.heldSeconds }
        return SessionOutcome(
            // The EXECUTABLE plan — what actually ran. Sets with no reps never happened and
            // must not appear in a log claiming they did.
            plan = plan.executable,
            routineName = routineName,
            results = reps,
            startedAt = startedAt,
            finishedAt = finishedAt ?: Instant.now().also { finishedAt = it },
            peakKg = reps.maxOfOrNull { it.peakKg } ?: 0.0,
            // Time-weighted, like the log's own: a rep that dropped off after a second must
            // not weigh as much as a full hang.
            avgKg = if (held > 0) reps.sumOf { it.avgKg * it.heldSeconds } / held else 0.0,
            totalHeldSeconds = held,
            completedReps = reps.count { it.outcome == RepOutcome.completed },
            plannedReps = runner.plannedRepCount,
            timerOnly = timerOnly,
            gaugeKind = if (timerOnly) null else gaugeKind,
            didAnyWork = runner.didAnyWork,
            maxCandidates = maxCandidates(reps),
        )
    }

    /// A pull inside a session that beat a grip's working max, offered as the new max per
    /// HAND. COMPLETED reps only, and only real pulls: a timer-only session records 0 kg
    /// peaks, and offering "0.0 kg — new max!" would be the app talking nonsense.
    private fun maxCandidates(reps: List<RepSummary>): List<MaxCandidate> {
        val best = LinkedHashMap<String, MaxCandidate>()
        for (rep in reps) {
            if (rep.outcome != RepOutcome.completed || !rep.peakKg.isFinite() || rep.peakKg <= MAX_CANDIDATE_FLOOR_KG) continue
            val key = rep.grip.key + "·" + rep.side.rawValue
            val held = best[key]
            if (held != null && held.kg >= rep.peakKg) continue
            best[key] = MaxCandidate(
                grip = rep.grip,
                side = rep.side,
                kg = rep.peakKg,
                previous = maxes.max(rep.grip.key, rep.side),
            )
        }
        return best.values
            .filter { candidate -> candidate.previous?.let { candidate.kg > it } ?: true }
            .sortedByDescending { it.kg }
    }

    companion object {
        /// 100 ms. Countdowns are whole seconds, so ten beats a second is four times the
        /// resolution anything on screen can show — enough that a phase boundary is never
        /// visibly late, cheap enough to run for twenty minutes.
        const val TICK_MILLIS = 100L

        /// One cheap check every 500 ms for the session's whole life; the silence it acts on
        /// is `silenceRestartSeconds`, which is per-gauge.
        const val WATCHDOG_MILLIS = 500L

        /// Every load cell drifts a few hundred grams unloaded, so a max is only offered
        /// above a kilogram — the same floor `MaxAttempt` uses to decide somebody pulled.
        const val MAX_CANDIDATE_FLOOR_KG = 1.0
    }
}

/// Everything the runner screen draws, at the resolution it draws it.
///
/// A `data class` on purpose: `RunnerSession.publish()` assigns only on a real change,
/// which is what turns 80 engine mutations a second into one or two recompositions.
data class RunnerSnapshot(
    val phase: RunnerPhase = RunnerPhase.Idle,
    val isDropped: Boolean = false,
    /// Over the top of the rep's target range — the clock is stopped and the instruction is
    /// the opposite of `isDropped`'s.
    val isOverTarget: Boolean = false,
    /// The engine is fail-closed on a notification whose starting timestamp is stale.
    /// `RunnerSession` reads this only for a recently armed, single-shot recovery break.
    val isRejectingStaleBatches: Boolean = false,
    val linkIsDown: Boolean = false,
    val isFinished: Boolean = false,
    /// False until the first force sample lands — a connected gauge that is silent.
    val hasSignal: Boolean = false,

    val setNumber: Int? = null,
    val setCount: Int = 0,
    val completedRepCount: Int = 0,
    val plannedRepCount: Int = 0,

    val grip: GripSpec? = null,
    val side: Side? = null,
    /// The load this rep is aiming for, already resolved to kilograms. Null when the routine
    /// sets no target, or when the grip has no max to take a percentage of.
    val targetBand: ClosedFloatingPointRange<Double>? = null,
    /// True while the rest currently running leads into a different grip — the one thing on
    /// a rest screen that is a change of instruction rather than a countdown. False outside
    /// a rest, `Releasing` included; see `SessionRunner.nextGripDiffers`.
    val gripChangesNext: Boolean = false,
    val newGripID: String? = null,
    val upcomingGrip: GripSpec? = null,
    /// True while the rest currently running is a SET BREAK rather than a between-pulls
    /// rest. Describes the rest, not the rep ahead — see `SessionRunner.isSetBreak`.
    val isSetBreak: Boolean = false,

    /// Whole seconds on whichever clock is running.
    val secondsShown: Int = 0,
)

/// A pull that beat the grip's working max — offered per HAND, because a left and a right
/// max are different numbers and a session pulls on one hand at a time.
data class MaxCandidate(
    val grip: GripSpec,
    val side: Side,
    val kg: Double,
    /// Null = the grip had no max at all for this hand — a first number, not a beat.
    val previous: Double?,
) {
    val id: String get() = grip.key + "·" + side.rawValue
}

/// Everything a finished session leaves behind, ready for `recordSession`. The runner
/// computes it; nothing here writes.
data class SessionOutcome(
    val plan: SessionPlan,
    val routineName: String,
    val results: List<RepSummary>,
    val startedAt: Instant,
    val finishedAt: Instant,
    val peakKg: Double,
    val avgKg: Double,
    val totalHeldSeconds: Double,
    val completedReps: Int,
    val plannedReps: Int,
    /// True when the session ran on the clock alone. The reps are real and the loads are
    /// not, which is a fact a log has to carry rather than infer from zero kilograms.
    val timerOnly: Boolean,
    /// Which gauge measured it, or null when nothing did.
    val gaugeKind: GaugeKind?,
    val didAnyWork: Boolean,
    val maxCandidates: List<MaxCandidate>,
)

/// What the summary decided. `save` false means the climber held the Discard button — the
/// session is thrown away and nothing is written (Nuri, 2026-08-09: "just in case you get
/// interrupted"). A session you were pulled out of halfway is not training, and logging it
/// drags a bad number through every average and marks the day done when it was not.
data class SessionSummaryDecision(
    val save: Boolean,
    val rpe: run.nuri.getagrip.engine.RPE? = null,
    /// The candidates the climber actually tapped "Save as max" on — never all of them.
    val newMaxes: List<MaxCandidate> = emptyList(),
)

/// Where a cue is played. One seam, so the engine's timing can be driven with no audio.
fun interface CueSink {
    fun gripChanged() {}
    fun play(cue: RunnerCue)

    /// Prepare whatever the sink needs. Called once, deferred a frame off the presenting
    /// pass — see `RunnerSession.begin`.
    fun begin() {}

    fun end() {}
}
