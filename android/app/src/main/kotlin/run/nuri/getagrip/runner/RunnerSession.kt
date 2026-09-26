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
import run.nuri.getagrip.store.TarePolicy
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/// Owns a running session: the state machine, the clock driving its countdowns, the gauge
/// subscription and cue playback.
///
/// TRANSLATION NOTE (Sources/Runner/RunnerSession.swift): iOS's `@Observable @MainActor`
/// class becomes a `@Stable` class holding Compose snapshot state — a CLASS for the same
/// reason: the ~80 Hz sample callback must mutate the runner, and a long-lived closure
/// cannot write through state captured in a composable.
///
/// The three pure policies iOS keeps here (`StaleBatchHealer`, `BackgroundPausePolicy`, the
/// silence threshold) live in `:engine`'s `RunnerPolicies.kt`.
@Stable
class RunnerSession(
    /// The routine's plan, as authored. `SessionRunner` takes its `executable` copy.
    val plan: SessionPlan,

    /// Frozen with the session: a routine renamed or deleted tomorrow must not rewrite
    /// today's log.
    val routineName: String,

    private val device: DeviceStore,

    /// Every max on file, by grip AND hand. Percentage targets become kilograms when the
    /// session begins and never move again, so a max recorded next month cannot rewrite
    /// what this morning prescribed.
    ///
    /// **The plan is NOT pre-baked**: `SessionRunner` resolves per REP, because a set
    /// covers both hands and they have different maxes. Baking one band onto the set would
    /// silently undo per-hand loads; the freeze comes from capturing the TABLE once.
    private val maxes: MaxTable = MaxTable(),

    /// Run the whole plan on the clock with no gauge — see `SessionRunner.timerOnly`.
    /// Nothing touches `DeviceStore` then: no connect, stream, sample callback or watchdog.
    /// The store is still held for the shared screen, and so a gauge-free session never
    /// quietly starts using a connected one.
    val timerOnly: Boolean = false,

    /// The ticker and watchdog share the retained workout's scope: recreating the Activity
    /// must not cancel measurement; explicit finish or ViewModel cleanup does.
    private val scope: CoroutineScope,

    /// Injected because `SystemClock.elapsedRealtimeNanos()` reads zero forever in a JVM
    /// unit test.
    private val clock: HostClock = SystemHostClock,

    /// Where a cue is played. Defaulted to nothing so tests drive timing with no audio or
    /// vibrator.
    private val cues: CueSink = CueSink {},

    /// Where the Live Update is published: `AndroidActivityPublisher` in the app, nothing
    /// in a test.
    private val activity: ActivityPublisher = NoActivityPublisher,

    /// Where the session asks the OS to keep it alive — `SessionForegroundService` in the
    /// app, nothing in a test. Only for a MEASURED session on a CONNECTED gauge that
    /// sustains background streaming; see `updateForegroundService`.
    private val service: SessionServiceController = NoSessionServiceController,
) {

    /// **NOT Compose state — the whole performance story of this screen.** `SessionRunner`
    /// is mutated by every force sample, 80× a second; as `mutableStateOf` read piecemeal
    /// it would rebuild every control 80×/s to move two numbers. What the SCREEN needs is
    /// republished as `snapshot` only on a real change.
    val runner: SessionRunner = SessionRunner(
        plan = plan,
        maxes = maxes,
        timerOnly = timerOnly,
        // Keyed to the CAPABILITY, never the kind: a clockless gauge is stamped from host
        // uptime, so one delta is an arrival gap and may only buy the cap. The Progressor's
        // µs deltas are truth and stay uncapped. See
        // `SessionRunner.maxCreditedSampleGapSeconds`.
        maxCreditedSampleGapSeconds =
            if (device.gaugeCapabilities.hasDeviceClock) null else SamplePacing.syntheticClockGapCapSeconds,
    )

    /// The coarse, view-shaped view of the runner, assigned only on a real change: a second
    /// of holding invalidates the UI once or twice instead of ~80 times.
    var snapshot: RunnerSnapshot by mutableStateOf(RunnerSnapshot())
        private set

    /// Measured fraction, separate from the snapshot so only the small bar updates with
    /// samples. The view settles toward it and never extrapolates.
    var repProgress: Float by mutableFloatStateOf(0f)
        private set

    /// The running countdown's fraction left: the timer-only ring, and the runner's time bar
    /// draining through a rest. Keep this 10 Hz fraction off the screen snapshot too; only
    /// leaf views read it.
    var phaseRemainingFraction: Double? by mutableStateOf(null)
        private set

    /// Bumped on every real republish; a test seam for the change-guard.
    var snapshotRevision: Int = 0
        private set

    var startedAt: Instant = Instant.now()
        private set

    /// The id this session's log row is written under, shared by the summary's Save and
    /// launch recovery so it can never be logged twice. See `FinishedSessionDraft`.
    val sessionID: UUID = UUID.randomUUID()

    /// Called ONCE, when the session finishes, with what a store would write.
    /// `WorkoutViewModel` writes the finished-session draft here, before the summary and
    /// before anything can take the process away.
    var onFinished: ((SessionOutcome) -> Unit)? = null

    /// Monotonic seconds the countdowns are measured against. NOT observable: the screen
    /// reads whole seconds off `snapshot`.
    var now: Double = clock.uptimeSeconds()
        private set

    /// Force samples received by this session. Zero mid-workout means connected but not
    /// talking, which the screen must not render as "0.0 kg" (a device measuring nothing
    /// rather than an app receiving nothing).
    var samplesSeen: Int = 0
        private set

    // MARK: - Read once, at construction

    /// Whether the gauge stamps its own samples. The Progressor does; every ported device
    /// is stamped from host uptime, so a re-kick means something different to each — see
    /// `restartStreamArmingStaleBatchHeal`.
    ///
    /// Read ONCE, like the timing policy below: selecting a different gauge in Settings
    /// mid-workout must not change what this session drives.
    private val hasDeviceClock: Boolean = device.gaugeCapabilities.hasDeviceClock

    /// Whether this gauge keeps delivering samples in the background: decides whether the
    /// foreground service is worth running, and is what `BackgroundPausePolicy` reads. Read
    /// ONCE, as above.
    private val sustainsBackgroundStreaming: Boolean =
        device.gaugeCapabilities.sustainsBackgroundStreaming

    /// Silence that means the stream needs re-kicking, for THIS gauge: eight samples'
    /// worth, floored at the Progressor's 0.8 s. See `SamplePacing.silenceThreshold`.
    val silenceRestartSeconds: Double = SamplePacing.silenceThreshold(
        forRate = device.gaugeCapabilities.nominalSampleRate,
        isBroadcast = device.gaugeCapabilities.isBroadcast,
    )

    private val gaugeKind: GaugeKind = device.gaugeKind

    // MARK: - Internal state

    private var ticker: Job? = null
    private var streamWatchdog: Job? = null
    private var connectionWatch: Job? = null

    /// The link the watcher last saw. It reports CHANGES only, so the engine hears each
    /// drop and return once.
    private var lastLink: DeviceStore.Link? = null
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
        // Idempotent: a re-run `DisposableEffect` must not re-acquire the wake lock or
        // re-arm the ticker.
        if (hasBegun) return
        hasBegun = true

        startedAt = Instant.now()
        runner.beginRecording(clock.uptimeSeconds())
        now = clock.uptimeSeconds()

        // Only starts the audio queue; native setup and synthesis run on its worker.
        // Registered before Start so a zero-lead-in routine keeps its first cue.
        cues.begin()

        if (!timerOnly) {
            device.onSample = { sample ->
                samplesSeen += 1
                lastSampleAt = clock.uptimeSeconds()
                send(RunnerEvent.Sample(sample))
            }
        }

        // Wall-clock heartbeat. Countdowns live here; work time never does — only device
        // timestamps inside the runner.
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                tickNow()
            }
        }

        if (!timerOnly) {
            // Captured BEFORE anything can connect, so a link coming up during `begin()` is
            // a change, not a swallowed baseline.
            lastLink = device.link.value
        }

        if (timerOnly) {
            // Starts on the spot: nothing to connect or tare; the count-in is the only
            // preamble.
            send(RunnerEvent.Start)
        } else if (device.state.isConnected) {
            startIfReady(StreamStartCause.initial)
        } else {
            device.connect()
        }

        // PUBLISH FIRST. The activity state reads the snapshot, and before this it is the
        // empty default — the first card once went out saying "Pull 0 of 12" with no
        // countdown.
        publish()

        // Best-effort and last: an activity that cannot start (permission refused, budget
        // spent) must never disturb the workout.
        val grip = runner.displaySlot?.grip ?: plan.executable.sets.firstOrNull()?.grip
        if (grip != null) {
            activity.start(
                routineName = routineName,
                plannedReps = runner.plannedRepCount,
                setCount = runner.setCount,
                state = activityState(grip),
            )
        }

        // AFTER the publisher: `startForeground` adopts the card just built; starting first
        // would flash a placeholder.
        updateForegroundService()

        // Last, for the same reason: a link that arrived during `begin()` is reported here
        // and starts the service, which must find the card built.
        if (!timerOnly) watchConnection()
    }

    /// **The session follows the link on its own scope.** A `LaunchedEffect` on the screen
    /// stops with the Activity, so a session kept alive behind a locked screen never heard
    /// the link drop or return. See `DeviceStore.link`.
    private fun watchConnection() {
        connectionWatch?.cancel()
        connectionWatch = scope.launch {
            device.link.collect { link ->
                val last = lastLink
                lastLink = link
                if (last == null || link == last) return@collect
                if (link.isConnected && last.isConnected) {
                    // A NEW connection with no drop observed (the flow conflated it); the
                    // engine must still break its timeline.
                    connectionChanged(false)
                    connectionChanged(true)
                } else if (link.isConnected != last.isConnected) {
                    connectionChanged(link.isConnected)
                }
            }
        }
    }

    fun end() {
        // Tear down only a session we started, and only once: a stray dispose once stopped
        // the stream under a live session, which looks exactly like a dead gauge.
        if (!hasBegun || hasEnded) return
        hasEnded = true

        ticker?.cancel()
        ticker = null
        streamWatchdog?.cancel()
        streamWatchdog = null
        connectionWatch?.cancel()
        connectionWatch = null
        device.onSample = null
        // Never leave the gauge streaming: it drains its own battery and the user blames
        // the app.
        if (device.isStreaming) device.stopStreaming(StreamStopCause.sessionEnded)
        // Ended with the session: a lock-screen card still saying "Pull" afterwards is
        // worse than none.
        activity.end()
        // A `connectedDevice` service outliving its session holds the radio open for
        // nothing — what every OEM battery manager notices.
        if (serviceRunning) {
            serviceRunning = false
            service.end()
        }
        cues.end()
    }

    /// Start the foreground service once this session qualifies. Called from `begin()` and
    /// `connectionChanged`, because Start is enabled while the gauge sleeps: the first
    /// phase is connect-and-tare, so there is often no link yet at `begin()`.
    ///
    /// **Started once, never stopped early.** A mid-session drop is exactly when the
    /// process must stay alive to reconnect and keep showing RE-GRIP, so the service lives
    /// as long as the SESSION, not the link (as iOS's app-wide `bluetooth-central`).
    private fun updateForegroundService() {
        if (serviceRunning || timerOnly || hasEnded || runner.isFinished) return
        // A gauge-free session has nothing to keep alive (`RunnerLifecycle` pauses it on
        // the way out); nor does a broadcast scale, whose scan the OS silences regardless.
        if (!sustainsBackgroundStreaming) return
        if (!device.state.isConnected) return
        serviceRunning = true
        service.begin()
    }

    private var serviceRunning = false

    /// The first phase is connect-and-tare, which is why Start on Today is enabled while
    /// the gauge is asleep.
    fun startIfReady(cause: StreamStartCause) {
        if (timerOnly || hasEnded || runner.isFinished || !device.state.isConnected) return
        // UNCONDITIONAL. An `if (!device.isStreaming)` guard could only SKIP the one
        // command the session depends on: with a stale flag the workout sat at 0.0 kg with
        // an empty trace while the gauge screen (which sends its own) worked fine.
        // Re-sending start is harmless; not sending it is a dead session.
        if (hasStarted) {
            // The foreground kick lands here. A re-kick may start a fresh device-µs epoch,
            // so it gets the watchdog's break-first ordering and one bounded recovery for
            // queued old-epoch data.
            restartStreamArmingStaleBatchHeal(cause)
            return
        }
        hasStarted = true
        // Tare FIRST, then start — the vendor app's order. The reverse tared a fresh stream
        // and killed it on real firmware.
        armStaleBatchHeal()
        send(RunnerEvent.TareCommitted)
        device.tare()
        device.startStreaming(StreamStartCause.initial)
        send(RunnerEvent.Start)
        armStreamWatchdog()
    }

    /// Restart the stream whenever it falls SILENT, not merely if it never started. The
    /// first version fired only when no sample had EVER arrived, so a stream that died
    /// after a few samples (as a mid-stream tare did) was never revived. A session's stream
    /// is CONTINUOUS, so silence while connected is always worth a restart: harmless when
    /// alive, one cheap check every 500 ms for the session's life. `end()` cancels it.
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

                // Raw data arrives but the engine may be rejecting it because a queued
                // pre-background burst re-anchored the high-water mark. Two 500 ms
                // observations separate that from a packet-order blip.
                consecutiveRejectingChecks =
                    if (snapshot.isRejectingStaleBatches) consecutiveRejectingChecks + 1 else 0
                applyStaleBatchHealDecision(clock.uptimeSeconds())
            }
        }
    }

    /// Restart the stream and NOTHING else — no tare, on any path. Not `startIfReady`,
    /// whose first-start branch tares: "waking never tares" must be a guarantee of the API,
    /// not of which branch ran. With the load unknown (that is what stale means), a tare
    /// must not happen.
    fun wakeStream() {
        if (timerOnly || hasEnded || runner.isFinished || !device.state.isConnected) return
        restartStreamArmingStaleBatchHeal(StreamStartCause.manualWake)
    }

    /// Every session-owned re-kick uses this path. Arming precedes the break, and the break
    /// sits immediately before the command that can reset the device clock.
    private fun restartStreamArmingStaleBatchHeal(cause: StreamStartCause) {
        // **No timeline break on a clockless gauge.** The break survives a Tindeq
        // restarting its µs epoch; host uptime has no epoch to restart. And the break is
        // not free: it nulls the accrual anchor and arming debounce, and at 8–10 Hz the
        // watchdog could land one between every pair of samples — kg moving on a rep that
        // can neither arm nor finish. The single-shot heal stays for the one gauge whose
        // clock can reset.
        if (!hasDeviceClock) {
            device.startStreaming(cause)
            return
        }
        armStaleBatchHeal()
        send(RunnerEvent.StreamRestarted)
        device.startStreaming(cause)
    }

    private fun armStaleBatchHeal() {
        if (!hasDeviceClock) return
        staleBatchHealArmedAt = clock.uptimeSeconds()
        consecutiveRejectingChecks = 0
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
                // Consume BEFORE sending: the healing break cannot authorize itself again;
                // only a real re-kick can.
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

    /// Reported by `watchConnection`, which already filtered repeats. Internal so a test on
    /// an inert scope can stand in for it.
    internal fun connectionChanged(isConnected: Boolean) {
        // A gauge waking up in your bag must not take over a session you chose to run
        // without it.
        if (timerOnly) return
        send(if (isConnected) RunnerEvent.ConnectionRestored else RunnerEvent.ConnectionLost)
        if (isConnected) {
            startIfReady(if (hasStarted) StreamStartCause.reconnect else StreamStartCause.initial)
            // The link `begin()` was waiting for; from here the session can survive the
            // screen locking.
            updateForegroundService()
        }
    }

    fun tare() {
        if (timerOnly || hasEnded || runner.isFinished || !device.state.isConnected) return
        if (!TarePolicy.phaseAllowsTare(runner.phase) ||
            !TarePolicy.isSafeToTareNow(device.secondsSinceLastSample(), device.tareReadingMaxAge)) return
        // DeviceStore re-kicks a running stream after tare, and buffered pre-tare samples
        // can anchor the new epoch first; allow the same bounded single-use heal.
        if (device.isStreaming) armStaleBatchHeal()
        send(RunnerEvent.TareCommitted)
        device.tare()
    }

    // MARK: - The one funnel

    /// Every event in, every cue out, in one place — the one line deciding what a session
    /// sounds like. The list is also RETURNED, so a test can assert a whole workout's cue
    /// schedule with no audio.
    private val announcedGrips = mutableSetOf<String>()

    fun send(event: RunnerEvent): List<RunnerCue> {
        val emitted = runner.handle(event, at = now, recordedAt = clock.uptimeSeconds())
        if (runner.isFinished && finishedAt == null) {
            finishedAt = startedAt.plusNanos(((runner.finishedElapsedSeconds ?: 0.0) * 1e9).toLong())
            finish()
        }
        publish()
        runner.newGripID?.let { if (announcedGrips.add(it)) cues.gripChanged() }
        for (cue in emitted) cues.play(cue)
        return emitted
    }

    /// **The last rep is not the end of the session's life — the summary is.** Ending the
    /// card and service at the last rep, with nothing written until Save, left a finished
    /// workout only in memory exactly when the phone gets put down, and Android free to
    /// reclaim it. Now, in order:
    ///
    /// 1. **The draft is written** (`onFinished` → `FinishedSessionDraft`), so a process
    ///    killed now leaves something the next launch can offer to save.
    /// 2. **The stream stops.** The summary reads no force, and the gauge must not stream
    ///    behind a locked screen while the climber decides; stopping also lets the idle
    ///    grace work.
    /// 3. **The card becomes "session done"** and the service keeps running until `end()`
    ///    (Save or Discard) stops both.
    private fun finish() {
        onFinished?.invoke(outcome())
        // **A finished session lets go of what only a running one needs**, as on iPhone:
        // the heartbeat, the stream watchdog and the sample callback. `end()` later undoes
        // only what is still live.
        ticker?.cancel()
        ticker = null
        streamWatchdog?.cancel()
        streamWatchdog = null
        if (!timerOnly) device.onSample = null
        if (!timerOnly && device.isStreaming) device.stopStreaming(StreamStopCause.sessionEnded)
        activity.showFinished(routineName)
        // Released a beat later: the session-complete chord is queued by this `send`, and
        // stopping now would cut it.
        scope.launch {
            delay(CUE_RELEASE_AFTER_FINISH_MILLIS)
            if (!hasEnded) cues.end()
        }
    }

    /// Advance the wall clock and beat once: the ticker's body, exposed so a test can drive
    /// it against an injected clock.
    fun tickNow(): List<RunnerCue> {
        now = clock.uptimeSeconds()
        return send(RunnerEvent.Tick)
    }

    /// Rebuild the snapshot, assigning ONLY on a real change: Compose invalidates on every
    /// set, equal or not.
    private fun publish() {
        // `displaySlot`, not `currentSlot`: during a rest the screen describes the next
        // rep. See `SessionRunner.displaySlot`.
        val slot = runner.displaySlot
        // Published separately so only the progress bar reads this sample-rate state.
        repProgress = runner.repProgress.toFloat().coerceIn(0f, 1f)
        // Measured sessions too, for the time bar's rest countdown (iOS ce36b1f). The engine returns
        // null while a measured pull works, so this changes only on countdown ticks.
        val remaining = runner.phaseRemainingFraction(now)
        if (remaining != phaseRemainingFraction) phaseRemainingFraction = remaining
        val next = RunnerSnapshot(
            phase = runner.phase,
            isDropped = runner.isDropped,
            isOverTarget = runner.isOverTarget,
            isRejectingStaleBatches = runner.isRejectingStaleBatches,
            linkIsDown = runner.linkIsDown,
            isFinished = runner.isFinished,
            // A gauge-free session always "has signal": the clock is the signal.
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
            scheduledRestSeconds = RestFocusPresentation.scheduledRestSeconds(runner.phase, runner.slots),
            // Whole seconds belong here; the measured fraction stays separate so the
            // progress bar cannot redraw the runner.
            secondsShown = secondsShown,
        )
        if (next != snapshot) {
            snapshot = next
            snapshotRevision += 1
        }
        pushActivity()
    }

    /// Mirror the snapshot into the activity, only the parts it draws, only when one moved.
    /// Not `secondsShown`: the notification counts down from `endsAt` itself, and
    /// forwarding a ticking number spends the update budget for nothing.
    private fun pushActivity() {
        if (snapshot.isFinished) return
        val grip = snapshot.grip ?: return
        if (!activity.isRunning) return
        val signature = ActivitySignature(
            grip = grip,
            side = snapshot.side ?: Side.both,
            phase = activityPhase,
            setNumber = snapshot.setNumber ?: 1,
            repPosition = repPosition,
        )
        // **Compared WITHOUT `endsAt`.** `endsAt` is `now + secondsRemaining` and drifts on
        // each of the ten publishes a second, so comparing it would spend a session's
        // update budget in the first minute. A new rep or phase is also exactly when a new
        // countdown should start.
        if (signature == lastActivitySignature) return
        lastActivitySignature = signature
        activity.update(activityState(grip))
    }

    /// Everything that should force a push. Excludes the clock and the live load: both move
    /// continuously.
    private data class ActivitySignature(
        val grip: GripSpec,
        val side: Side,
        val phase: SessionActivityPhase,
        val setNumber: Int,
        val repPosition: Int,
    )

    /// Which pull you are ON, floored at 1: "Pull 0 of 12" says the session has not
    /// started, and by the time anyone sees it, it has.
    private val repPosition: Int
        get() {
            if (snapshot.plannedRepCount <= 0) return 1
            return maxOf(1, minOf(snapshot.completedRepCount + 1, snapshot.plannedRepCount))
        }

    private fun activityState(grip: GripSpec): SessionActivityState {
        val phase = activityPhase
        // ARMED runs no clock (it waits on you, no timeout), so the deadline goes out null
        // and the hold LENGTH instead.
        val isArmed = phase == SessionActivityPhase.armed
        val remainingInterval = runner.countdownRemainingInterval(clock.uptimeSeconds())
            ?: snapshot.secondsShown.toDouble()
        return SessionActivityState(
            grip = grip,
            side = snapshot.side ?: Side.both,
            phase = phase,
            setNumber = snapshot.setNumber ?: 1,
            repPosition = repPosition,
            targetLoKg = snapshot.targetBand?.start,
            targetHiKg = snapshot.targetBand?.endInclusive,
            // An ABSOLUTE deadline from the screen's countdown, so the notification ticks
            // without us.
            endsAtEpochMillis = if (phase.runsCountdown && remainingInterval > 0) {
                System.currentTimeMillis() + (remainingInterval * 1_000).toLong()
            } else {
                null
            },
            pendingSeconds = if (isArmed) snapshot.secondsShown else null,
        )
    }

    /// The phases that change what you do with your hands — **`armed` included.** Folded
    /// into "pulling", the card said "Pull" and counted down while the edge was untouched.
    /// Its own colour: amber, "waiting on you", against bleu for a running clock.
    private val activityPhase: SessionActivityPhase
        get() {
            val phase = snapshot.phase
            if (phase.isPaused) return SessionActivityPhase.paused
            return when (phase) {
                is RunnerPhase.Idle, is RunnerPhase.LeadIn -> SessionActivityPhase.leadIn
                is RunnerPhase.Armed -> SessionActivityPhase.armed
                is RunnerPhase.Working -> SessionActivityPhase.pulling
                is RunnerPhase.Releasing -> SessionActivityPhase.releasing
                is RunnerPhase.Resting -> SessionActivityPhase.resting
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
    /// No store writes here: `RunnerHost` hands this to `onFinished` and the integrator
    /// persists, keeping the runner testable with no database.
    fun outcome(): SessionOutcome {
        val reps = runner.results
        val held = reps.sumOf { it.heldSeconds }
        return SessionOutcome(
            id = sessionID,
            // The EXECUTABLE plan — what actually ran. Sets with no reps never happened.
            plan = plan.executable,
            routineName = routineName,
            results = reps,
            startedAt = startedAt,
            finishedAt = finishedAt ?: Instant.now().also { finishedAt = it },
            peakKg = reps.maxOfOrNull { it.peakKg } ?: 0.0,
            // Time-weighted, like the log's own: a rep dropped after a second must not
            // weigh like a full hang.
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

    /// A pull that beat a grip's working max, offered per HAND. COMPLETED reps and real
    /// pulls only: a timer-only session records 0 kg peaks.
    internal fun maxCandidates(reps: List<RepSummary>): List<MaxCandidate> {
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
                // A shared fallback target is not a recorded peak for this hand; offer its
                // first measured max even below the shared value.
                previous = maxes.exact(rep.grip.key, rep.side),
            )
        }
        return best.values
            .filter { candidate -> candidate.previous?.let { candidate.kg > it } ?: true }
            .sortedByDescending { it.kg }
    }

    companion object {
        /// 100 ms: four times the resolution of whole-second countdowns, so a phase
        /// boundary is never visibly late, and cheap for twenty minutes.
        const val TICK_MILLIS = 100L
        /// How long the cue player outlives the finish, so the session chord (0.52 s) plays out.
        const val CUE_RELEASE_AFTER_FINISH_MILLIS = 1_000L

        /// One cheap check every 500 ms; the silence it acts on is per-gauge
        /// `silenceRestartSeconds`.
        const val WATCHDOG_MILLIS = 500L

        /// Load cells drift a few hundred grams unloaded, so a max is only offered above a
        /// kilogram — `MaxAttempt`'s floor too.
        const val MAX_CANDIDATE_FLOOR_KG = 1.0
    }
}

/// Everything the runner screen draws, at the resolution it draws it. A `data class` so
/// `publish()` can assign only on a real change: 80 engine mutations a second become one or
/// two recompositions.
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
    /// True while the running rest leads into a different grip — a change of instruction,
    /// not a countdown. False outside a rest, `Releasing` included; see
    /// `SessionRunner.nextGripDiffers`.
    val gripChangesNext: Boolean = false,
    val newGripID: String? = null,
    val upcomingGrip: GripSpec? = null,
    /// True while the rest currently running is a SET BREAK rather than a between-pulls
    /// rest. Describes the rest, not the rep ahead — see `SessionRunner.isSetBreak`.
    val isSetBreak: Boolean = false,

    /// Whole seconds on whichever clock is running.
    val secondsShown: Int = 0,
    /// Original duration of the completed slot's rest: does not shrink with the countdown
    /// or inherit the next grip's rest.
    val scheduledRestSeconds: Int? = null,
) {
    val showsRestFocus: Boolean
        get() = RestFocusPresentation.isResting(phase) &&
            (scheduledRestSeconds ?: 0) >= RestFocusPresentation.minimumScheduledSeconds
}

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
    /// Ran on the clock alone: the reps are real, the loads are not — a fact the log must
    /// carry, not infer from zero kg.
    val timerOnly: Boolean,
    /// Which gauge measured it, or null when nothing did.
    val gaugeKind: GaugeKind?,
    val didAnyWork: Boolean,
    val maxCandidates: List<MaxCandidate>,
    /// The log row's id — see `RunnerSession.sessionID`. Never defaulted: a fresh id would
    /// break one-session-one-row between Save and launch recovery.
    val id: UUID,
)

/// What the summary decided. `save` false means the climber held Discard and nothing is
/// written (Nuri, 2026-08-09): an interrupted session is not training, and logging it skews
/// every average and marks the day done.
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
