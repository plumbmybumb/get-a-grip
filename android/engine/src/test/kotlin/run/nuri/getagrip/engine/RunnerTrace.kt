// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/// **The runner half of the cross-engine contract.**
///
/// A `SessionRunner` is a pure function of the events it is handed, so a whole workout
/// can be written down: every `(t, event, cues)` triple plus the state it ended in.
/// `Fixtures/runner/<scenario>.json` is that recording, and it is asserted from BOTH
/// sides — `RunnerFixtureTests` replays it through this engine, `tools/oracle runner
/// verify` replays it through the real iOS `SessionRunner`. Neither engine can drift
/// without one of those two going red.
///
/// **Recording is opt-in.** By default `theRecordedScenariosMatchTheFixturesOnDisk`
/// re-derives every document and compares it byte for byte with the file, so a Kotlin
/// regression cannot quietly rewrite the evidence against itself. Regenerate on purpose:
///
///     ./android/gradlew :engine:test --tests '*RunnerTrace*' -Dgetagrip.record=1
///
/// Two deliberate departures from `SessionRunnerTests.Feeder`, both to keep the files
/// readable rather than to change what is exercised:
/// - `hold` emits SAMPLES ONLY and `wait` emits TICKS ONLY. A tick outside a countdown
///   phase returns no cues and moves nothing, so the interleaved ticks the XCTest feeder
///   sends during a hold are pure volume here; every countdown a scenario needs is
///   driven by an explicit `wait`.
/// - samples run at 25 Hz, not 80. Any gap under `maxCreditableDeltaMicros` accrues
///   identically, and the scenarios that are ABOUT the gap set it explicitly.
class RunnerTrace(
    val name: String,
    plan: SessionPlan,
    /// `(gripKey, side, kg)`, recorded into the fixture so the oracle rebuilds the same
    /// table — the per-hand loads a rep freezes are part of what is being pinned.
    private val maxes: List<Triple<String, Side, Double>> = emptyList(),
    private val timerOnly: Boolean = false,
    private val maxCreditedSampleGapSeconds: Double? = null,
) {
    private val authoredPlan = plan
    private val table = MaxTable().also { t ->
        maxes.forEach { t.record(it.third, it.first, it.second) }
    }

    val runner = SessionRunner(
        plan = plan,
        maxes = table,
        timerOnly = timerOnly,
        maxCreditedSampleGapSeconds = maxCreditedSampleGapSeconds,
    )

    private val steps = mutableListOf<JsonObject>()

    var micros: UInt = 0u
    var now: Double = 0.0

    fun send(event: RunnerEvent, at: Double = now): List<RunnerCue> {
        val cues = runner.handle(event, at = at)
        steps.add(
            JsonObject(
                mapOf(
                    "t" to JsonPrimitive(at),
                    "event" to encode(event),
                    "cues" to JsonArray(cues.map { encode(it) }),
                ),
            ),
        )
        return cues
    }

    fun start() {
        send(RunnerEvent.Start, at = now)
    }

    /// Holds `kg` for `seconds` of device time at 25 Hz.
    fun hold(kg: Double, seconds: Double, hz: Double = 25.0) {
        val period = 1.0 / hz
        val periodMicros = (period * 1_000_000).toUInt()
        val count = kotlin.math.round(seconds * hz).toInt()
        repeat(maxOf(0, count)) {
            micros += periodMicros
            now += period
            send(RunnerEvent.Sample(ForceSample(kg, micros)), at = now)
        }
    }

    /// Come off the edge — what a hand does the moment a hold ends, and what the
    /// release-gated rest is waiting for.
    fun letGo() = hold(kg = 0.2, seconds = 0.2)

    /// Wall-clock only, at 10 Hz. What a rest period actually looks like.
    fun wait(seconds: Double) {
        val count = kotlin.math.round(seconds / 0.1).toInt()
        repeat(maxOf(0, count)) {
            now += 0.1
            send(RunnerEvent.Tick, at = now)
        }
    }

    /// Feed a real Progressor notification, so the recorded samples are the ones the
    /// CODEC produced — batch markers, force filtering and timeline validation included.
    fun deliverPacket(samples: List<Pair<Float, UInt>>) {
        val payload = samples.flatMap { (kg, stamp) ->
            leBytes(kg.toRawBits().toUInt()) + leBytes(stamp)
        }
        val packet = ByteArray(payload.size + 2)
        packet[0] = 0x01
        packet[1] = payload.size.toByte()
        payload.forEachIndexed { i, b -> packet[i + 2] = b.toByte() }
        for (event in ProgressorCodec.decode(packet)) {
            if (event is ProgressorEvent.Sample) send(RunnerEvent.Sample(event.sample), at = now)
        }
    }

    private fun leBytes(v: UInt): List<Int> = listOf(
        (v and 0xFFu).toInt(),
        ((v shr 8) and 0xFFu).toInt(),
        ((v shr 16) and 0xFFu).toInt(),
        ((v shr 24) and 0xFFu).toInt(),
    )

    /// The whole scenario as the document `Fixtures/README.md` describes.
    fun document(): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "name" to JsonPrimitive(name),
            // A STRING of canonical JSON, exactly like `planmath/sequences.json`, so a
            // decode/re-encode round trip on either side is a free byte check.
            "plan" to JsonPrimitive(BlobCodec.encode(authoredPlan)!!),
            "maxes" to JsonArray(
                maxes.map { (grip, side, kg) ->
                    JsonObject(
                        linkedMapOf(
                            "grip" to JsonPrimitive(grip),
                            "side" to JsonPrimitive(side.rawValue),
                            "kg" to JsonPrimitive(kg),
                        ),
                    )
                },
            ),
            "timerOnly" to JsonPrimitive(timerOnly),
            "steps" to JsonArray(steps),
            "final" to JsonObject(
                linkedMapOf(
                    "phase" to JsonPrimitive(phaseText(runner.phase)),
                    "completedRepCount" to JsonPrimitive(runner.completedRepCount),
                    "results" to JsonArray(
                        runner.results.map { JsonPrimitive(BlobCodec.encode(it)!!) },
                    ),
                ),
            ),
        )
        // Optional and omitted when absent, the house rule for every blob: only the
        // synthetic-clock scenarios carry a cap, and the Progressor's answer is "none".
        maxCreditedSampleGapSeconds?.let {
            fields["maxCreditedSampleGapSeconds"] = JsonPrimitive(it)
        }
        return JsonObject(fields)
    }

    companion object {

        // MARK: - The wire shapes, per Fixtures/README.md

        fun encode(event: RunnerEvent): JsonObject = when (event) {
            RunnerEvent.Start -> typed("start")
            is RunnerEvent.Sample -> JsonObject(
                linkedMapOf(
                    "type" to JsonPrimitive("sample"),
                    "kg" to JsonPrimitive(event.sample.kg),
                    "micros" to JsonPrimitive(event.sample.deviceMicros.toLong()),
                    "batchStart" to JsonPrimitive(event.sample.isBatchStart),
                ),
            )
            RunnerEvent.Tick -> typed("tick")
            RunnerEvent.ConnectionLost -> typed("connectionLost")
            RunnerEvent.ConnectionRestored -> typed("connectionRestored")
            RunnerEvent.TareCommitted -> typed("tareCommitted")
            RunnerEvent.StreamRestarted -> typed("streamRestarted")
            RunnerEvent.Pause -> typed("pause")
            RunnerEvent.Resume -> typed("resume")
            RunnerEvent.SkipRep -> typed("skipRep")
            RunnerEvent.SkipSet -> typed("skipSet")
            RunnerEvent.Abort -> typed("abort")
        }

        fun encode(cue: RunnerCue): JsonObject = when (cue) {
            is RunnerCue.LeadInTick -> typed("leadInTick", "seconds", JsonPrimitive(cue.secondsRemaining))
            is RunnerCue.Armed -> typed("armed", "side", JsonPrimitive(cue.side.rawValue))
            RunnerCue.RepStarted -> typed("repStarted")
            RunnerCue.RepHalfway -> typed("repHalfway")
            is RunnerCue.RepEnded -> typed("repEnded", "completed", JsonPrimitive(cue.completed))
            is RunnerCue.RestTick -> typed("restTick", "seconds", JsonPrimitive(cue.secondsRemaining))
            is RunnerCue.SetCompleted -> typed("setCompleted", "setIndex", JsonPrimitive(cue.setIndex))
            RunnerCue.SessionCompleted -> typed("sessionCompleted")
            RunnerCue.DropoutWarning -> typed("dropoutWarning")
            RunnerCue.ConnectionLost -> typed("connectionLost")
        }

        private fun typed(type: String, key: String? = null, value: JsonElement? = null): JsonObject {
            val fields = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type))
            if (key != null && value != null) fields[key] = value
            return JsonObject(fields)
        }

        /// `idle | leadIn:<slot> | armed:<slot> | working:<slot> | releasing:<slot> |
        /// resting:<slot> | paused(<inner>) | finished`.
        fun phaseText(phase: RunnerPhase): String = when (phase) {
            RunnerPhase.Idle -> "idle"
            is RunnerPhase.LeadIn -> "leadIn:${phase.slot}"
            is RunnerPhase.Armed -> "armed:${phase.slot}"
            is RunnerPhase.Working -> "working:${phase.slot}"
            is RunnerPhase.Releasing -> "releasing:${phase.slot}"
            is RunnerPhase.Resting -> "resting:${phase.slot}"
            is RunnerPhase.Paused -> "paused(${phaseText(phase.before)})"
            RunnerPhase.Finished -> "finished"
        }

        // MARK: - Layout

        /// Two-space indent to match every other fixture, with any object or array whose
        /// compact form fits on a line left compact — which is what keeps a 900-step trace
        /// one line per step instead of five thousand. Deterministic, so re-recording an
        /// unchanged engine produces byte-identical files.
        fun pretty(element: JsonElement, indent: String = ""): String {
            val compact = BlobCodec.write(element)
            if (compact.length <= 110) return compact
            val inner = "$indent  "
            return when (element) {
                is JsonArray -> element.joinToString(
                    separator = ",\n$inner", prefix = "[\n$inner", postfix = "\n$indent]",
                ) { pretty(it, inner) }
                is JsonObject -> element.entries.sortedBy { it.key }.joinToString(
                    separator = ",\n$inner", prefix = "{\n$inner", postfix = "\n$indent}",
                ) { (key, value) -> BlobCodec.write(JsonPrimitive(key)) + ": " + pretty(value, inner) }
                JsonNull, is JsonPrimitive -> compact
            }
        }
    }
}

/// The scenarios themselves — one per rule the runner has, drawn from the XCTest matrix.
///
/// Deliberately shorter than their XCTest twins: a scenario exists to pin the ENGINE's
/// answer on both platforms, and a 10 s hold at 80 Hz is 800 lines of nothing. Every
/// timing constant that matters (the 100 ms debounce, the 200 ms plausibility cliff, the
/// 1 s synthetic cap, the hysteresis band) is still crossed by real samples.
object RunnerScenarios {

    private val fourFinger = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)

    /// **Set ids are FIXED, not `UUID.randomUUID()`.** The plan travels in the fixture as
    /// a canonical JSON string and is re-encoded on both sides as a byte check, so a
    /// random id would make every re-recording a diff and every round trip a lie.
    private fun setID(index: Int): java.util.UUID =
        java.util.UUID.fromString("5E7C0000-0000-4000-8000-%012d".format(index))

    /// Fixed scenarios retain their original timings; short holds are covered separately.
    private fun plan(
        reps: Int = 1,
        sets: Int = 1,
        hold: Int = 10,
        rest: Int = 20,
        setBreak: Int = 60,
        leadIn: Int = 0,
        threshold: Double = 2.0,
        mode: HandMode = HandMode.bothHands,
    ): SessionPlan = SessionPlan(
        name = "Test",
        sets = (0 until sets).map { SetPlan(id = setID(it), grip = fourFinger, repsPerSide = reps) },
        handMode = mode,
        holdSeconds = hold,
        restSeconds = rest,
        setBreakSeconds = setBreak,
        leadInSeconds = leadIn,
        thresholdKg = threshold,
    )

    private fun banded(plan: SessionPlan): SessionPlan =
        plan.copy(sets = plan.sets.map { it.copy(targetLoKg = 20.0, targetHiKg = 30.0) })

    private const val pulling = 20.0
    private const val released = 0.2

    fun all(): List<RunnerTrace> = listOf(
        cleanRepAtTarget(),
        engageDebounce(),
        timerOnlyFullSession(),
        timerOnlyPauseBanksNothing(),
        timerOnlyHasNoArmedPhase(),
        timerOnlySkipsTheReleaseWait(),
        underTheBand(),
        overTheBand(),
        bandGateOffOverload(),
        bandGateOffStillStopsOnRelease(),
        longDropNeverEndsTheRep(),
        shakyHold(),
        thresholdHoverEngagesOnce(),
        disconnectAndReconnect(),
        tareMidRep(),
        timestampWrap(),
        staleEpochFailsClosed(),
        armedHealAfterForegroundRace(),
        streamRestartMidHold(),
        skipRep(),
        skipSet(),
        skipWhileResting(),
        skipWhileReleasing(),
        skipEverySetFromTheRestScreen(),
        abortMidRep(),
        abortWhilePaused(),
        pauseResumeMidRep(),
        pauseResumeMidRest(),
        releaseGatedRestOn(),
        releaseGatedRestOff(),
        linkLostWhileReleasing(),
        finalPullNeverWaits(),
        leadInOnlyBeforeASetsFirstRep(),
        handsAlternate(),
        emptyPlan(),
        syntheticGapCap(),
        coalescedAdvertisement(),
        deviceClockDropsTheWholeGap(),
        perHandMaxFreeze(),
    )

    /// A rep held cleanly to its target, banked and closed.
    private fun cleanRepAtTarget() = RunnerTrace("clean-rep-at-target", plan(hold = 3)).apply {
        start()
        hold(pulling, 3.6)
    }

    /// 120 ms of load is under the 100 ms debounce for the first three samples; letting
    /// go re-arms rather than starting.
    private fun engageDebounce() = RunnerTrace("engage-debounce", plan(hold = 3)).apply {
        start()
        hold(pulling, 0.12)
        hold(released, 0.2)
        hold(pulling, 0.6)
    }

    /// No gauge at all: count-in, hold, rest, both reps, on ticks alone.
    private fun timerOnlyFullSession() = RunnerTrace(
        "timer-only-full-session",
        plan(reps = 2, hold = 3, rest = 2, leadIn = 2),
        timerOnly = true,
    ).apply {
        start()
        wait(14.0)
    }

    /// A paused wall-clock hold banks none of the pause.
    private fun timerOnlyPauseBanksNothing() = RunnerTrace(
        "timer-only-pause-banks-nothing",
        plan(hold = 5, leadIn = 0),
        timerOnly = true,
    ).apply {
        start()
        wait(2.0)
        send(RunnerEvent.Pause)
        wait(6.0)
        send(RunnerEvent.Resume)
        wait(1.0)
    }

    /// Nothing can observe you taking the load, so the hold starts on the same beat.
    private fun timerOnlyHasNoArmedPhase() = RunnerTrace(
        "timer-only-has-no-armed-phase",
        plan(hold = 3, leadIn = 0),
        timerOnly = true,
    ).apply {
        start()
    }

    /// `waitForReleaseBeforeRest` is skipped with no gauge, or the session sits on LET GO
    /// waiting for a sample that never comes.
    private fun timerOnlySkipsTheReleaseWait() = RunnerTrace(
        "timer-only-skips-the-release-wait",
        plan(reps = 2, hold = 3, rest = 3, leadIn = 0).copy(waitForReleaseBeforeRest = true),
        timerOnly = true,
    ).apply {
        start()
        wait(4.0)
    }

    /// 12 kg is not a 20–30 kg rep: it never starts.
    private fun underTheBand() = RunnerTrace("under-the-band", banded(plan(hold = 3))).apply {
        start()
        hold(12.0, 2.0)
    }

    /// Over the ceiling stalls the clock the other way, then resumes in range.
    private fun overTheBand() = RunnerTrace("over-the-band", banded(plan(hold = 4))).apply {
        start()
        hold(25.0, 2.0)
        hold(45.0, 2.0)
        hold(25.0, 2.6)
    }

    /// With the gate off, double the ceiling banks the whole hold.
    private fun bandGateOffOverload() = RunnerTrace(
        "band-gate-off-overload",
        banded(plan(hold = 3)).copy(pausesOutsideTargetBand = false),
    ).apply {
        start()
        hold(60.0, 3.5)
    }

    /// What the switch must NOT loosen: letting go still stops the clock.
    private fun bandGateOffStillStopsOnRelease() = RunnerTrace(
        "band-gate-off-still-stops-on-release",
        banded(plan(hold = 6)).copy(pausesOutsideTargetBand = false),
    ).apply {
        start()
        hold(25.0, 2.0)
        hold(0.0, 1.5)
    }

    /// Eight seconds off the edge is still not a finished rep.
    private fun longDropNeverEndsTheRep() =
        RunnerTrace("long-drop-never-ends-the-rep", plan(hold = 4)).apply {
            start()
            hold(pulling, 2.0)
            hold(released, 8.0)
            hold(pulling, 2.5)
        }

    /// Dips pause accrual rather than resetting it, so the rep still banks its target.
    private fun shakyHold() = RunnerTrace("shaky-hold", plan(hold = 3)).apply {
        start()
        repeat(6) {
            hold(pulling, 0.6)
            hold(released, 0.6)
        }
    }

    /// 2.1 and 1.9 straddle ENGAGE and both sit above RELEASE — one engagement, no flicker.
    private fun thresholdHoverEngagesOnce() = RunnerTrace(
        "threshold-hover-engages-once",
        plan(hold = 30, threshold = 2.0),
    ).apply {
        start()
        repeat(8) {
            hold(2.1, 0.2)
            hold(1.9, 0.2)
        }
    }

    /// The outage is never credited as hang time, however far the device clock jumped.
    private fun disconnectAndReconnect() =
        RunnerTrace("disconnect-and-reconnect", plan(hold = 4)).apply {
            start()
            hold(pulling, 2.0)
            send(RunnerEvent.ConnectionLost)
            wait(3.0)
            micros += 3_000_000u
            send(RunnerEvent.ConnectionRestored)
            hold(pulling, 2.5)
        }

    /// The post-tare blip reads near zero through no fault of the climber.
    private fun tareMidRep() = RunnerTrace("tare-mid-rep", plan(hold = 5)).apply {
        start()
        hold(pulling, 2.0)
        send(RunnerEvent.TareCommitted)
        hold(released, 0.2)
        hold(pulling, 2.0)
    }

    /// The device's µs clock is a UInt32 and rolls over every ~71.6 minutes.
    private fun timestampWrap() = RunnerTrace("timestamp-wrap", plan(hold = 3)).apply {
        micros = UInt.MAX_VALUE - 60_000u
        start()
        hold(pulling, 3.6)
    }

    /// Consecutive stale batches can never authorize their own epoch; the explicit break
    /// can.
    private fun staleEpochFailsClosed() =
        RunnerTrace("stale-epoch-fails-closed", plan(hold = 5)).apply {
            start()
            hold(pulling, 1.0)
            deliverPacket(listOf(20f to 1_000u, 20f to 13_500u, 20f to 26_000u))
            deliverPacket(listOf(20f to 50_000u, 20f to 62_500u, 20f to 75_000u))
            send(RunnerEvent.StreamRestarted)
            deliverPacket(listOf(20f to 1_000u, 20f to 13_500u, 20f to 26_000u))
        }

    /// Queued pre-background data from epoch A wins the race and re-anchors; only the
    /// second, ARMED break lets the live epoch B back in.
    private fun armedHealAfterForegroundRace() =
        RunnerTrace("armed-heal-after-foreground-race", plan(hold = 5)).apply {
            start()
            hold(pulling, 1.0)
            send(RunnerEvent.StreamRestarted)
            deliverPacket(
                listOf(
                    20f to micros + 12_500u,
                    20f to micros + 25_000u,
                    20f to micros + 37_500u,
                ),
            )
            val liveEpochB = listOf(20f to 1_000u, 20f to 13_500u, 20f to 26_000u)
            deliverPacket(liveEpochB)
            send(RunnerEvent.StreamRestarted)
            deliverPacket(liveEpochB)
        }

    /// A restart preserves accrual and only resets the anchor.
    private fun streamRestartMidHold() =
        RunnerTrace("stream-restart-mid-hold", plan(hold = 5)).apply {
            start()
            hold(pulling, 1.0)
            send(RunnerEvent.StreamRestarted)
            send(RunnerEvent.Sample(ForceSample(pulling, 1_000u)))
            send(RunnerEvent.Sample(ForceSample(pulling, 13_500u)))
        }

    /// A skip still takes its rest.
    private fun skipRep() =
        RunnerTrace("skip-rep", plan(reps = 2, hold = 5, rest = 3)).apply {
            start()
            send(RunnerEvent.SkipRep, at = 1.0)
        }

    /// Everything still owed in the set is recorded as skipped.
    private fun skipSet() =
        RunnerTrace("skip-set", plan(reps = 3, sets = 2, hold = 5)).apply {
            start()
            send(RunnerEvent.SkipSet, at = 1.0)
        }

    /// From the REST screen the skip applies to the NEXT pull, never the finished one.
    private fun skipWhileResting() =
        RunnerTrace("skip-while-resting", plan(reps = 3, hold = 3, rest = 3)).apply {
            start()
            hold(pulling, 3.4)
            letGo()
            send(RunnerEvent.SkipRep)
        }

    /// And exactly the same from the LET GO screen.
    private fun skipWhileReleasing() =
        RunnerTrace("skip-while-releasing", plan(reps = 3, hold = 3, rest = 3)).apply {
            start()
            hold(pulling, 3.4)
            send(RunnerEvent.SkipRep)
        }

    /// The "37 of 36 pulls" regression: a summary can never exceed the plan it came from.
    private fun skipEverySetFromTheRestScreen() = RunnerTrace(
        "skip-every-set-from-the-rest-screen",
        plan(reps = 2, sets = 3, hold = 3, rest = 3, setBreak = 3),
    ).apply {
        start()
        hold(pulling, 3.4)
        letGo()
        repeat(6) {
            if (!runner.isFinished) {
                send(RunnerEvent.SkipSet)
                wait(3.2)
            }
        }
    }

    /// The rep in flight keeps the hang time it earned.
    private fun abortMidRep() =
        RunnerTrace("abort-mid-rep", plan(reps = 4, hold = 5)).apply {
            start()
            hold(pulling, 2.0)
            send(RunnerEvent.Abort)
        }

    /// And so does one aborted from behind the pause button — the regression that made
    /// a whole session unloggable.
    private fun abortWhilePaused() =
        RunnerTrace("abort-while-paused", plan(hold = 5)).apply {
            start()
            hold(pulling, 2.0)
            send(RunnerEvent.Pause)
            send(RunnerEvent.Abort)
        }

    /// The pause is not hang time.
    private fun pauseResumeMidRep() =
        RunnerTrace("pause-resume-mid-rep", plan(hold = 5)).apply {
            start()
            hold(pulling, 2.0)
            send(RunnerEvent.Pause)
            wait(5.0)
            micros += 5_000_000u
            send(RunnerEvent.Resume)
            hold(pulling, 2.0)
        }

    /// A paused rest steals none and gives none back.
    private fun pauseResumeMidRest() =
        RunnerTrace("pause-resume-mid-rest", plan(reps = 2, hold = 3, rest = 6)).apply {
            start()
            hold(pulling, 3.4)
            letGo()
            wait(2.0)
            send(RunnerEvent.Pause)
            wait(10.0)
            send(RunnerEvent.Resume)
            wait(4.4)
        }

    /// The hold ends on time; the REST waits for the hand to come off the edge.
    private fun releaseGatedRestOn() =
        RunnerTrace("release-gated-rest-on", plan(reps = 2, hold = 3, rest = 4)).apply {
            start()
            hold(pulling, 5.0)
            letGo()
            wait(4.2)
        }

    /// Off, the old fixed cadence is still available and still honest.
    private fun releaseGatedRestOff() = RunnerTrace(
        "release-gated-rest-off",
        plan(reps = 2, hold = 3, rest = 4).copy(waitForReleaseBeforeRest = false),
    ).apply {
        start()
        hold(pulling, 3.4)
    }

    /// You cannot wait for a release nobody can observe, so the rest starts anyway.
    private fun linkLostWhileReleasing() =
        RunnerTrace("link-lost-while-releasing", plan(reps = 2, hold = 3, rest = 4)).apply {
            start()
            hold(pulling, 3.4)
            send(RunnerEvent.ConnectionLost)
            wait(1.0)
        }

    /// The last pull owes no rest, so it never waits — the worst place to strand someone.
    private fun finalPullNeverWaits() =
        RunnerTrace("final-pull-never-waits", plan(reps = 2, hold = 3, rest = 4)).apply {
            start()
            hold(pulling, 3.4)
            letGo()
            wait(4.2)
            hold(pulling, 3.4)
        }

    /// Rep 2 of a set gets no count-in — you are already on the edge.
    private fun leadInOnlyBeforeASetsFirstRep() = RunnerTrace(
        "lead-in-only-before-a-sets-first-rep",
        plan(reps = 2, sets = 2, hold = 3, rest = 2, setBreak = 2, leadIn = 2),
    ).apply {
        start()
        wait(2.2)
        hold(pulling, 3.4)
        letGo()
        wait(2.2)
    }

    /// Hands alternate across reps and reset at each set boundary.
    private fun handsAlternate() = RunnerTrace(
        "hands-alternate",
        plan(reps = 1, sets = 2, hold = 3, rest = 2, setBreak = 2, mode = HandMode.alternateEachRep),
    ).apply {
        start()
        repeat(4) {
            hold(pulling, 3.4)
            letGo()
            wait(2.2)
        }
    }

    /// Nothing to run: it finishes rather than hanging.
    private fun emptyPlan() =
        RunnerTrace("empty-plan", SessionPlan(name = "Empty", sets = emptyList())).apply {
            start()
        }

    /// A synthetic clock's gap is CLAMPED, per sample: three five-second holes buy three
    /// seconds, not fifteen.
    private fun syntheticGapCap() = RunnerTrace(
        "synthetic-gap-cap",
        plan(hold = 30),
        maxCreditedSampleGapSeconds = 1.0,
    ).apply {
        start()
        hold(pulling, 1.0)
        repeat(3) {
            micros += 5_000_000u
            now += 5.0
            send(RunnerEvent.Sample(ForceSample(pulling, micros)))
        }
    }

    /// One coalesced advertisement from an 8 Hz scale is a 250 ms gap and ordinary
    /// sampling — it banks in full.
    private fun coalescedAdvertisement() = RunnerTrace(
        "coalesced-advertisement",
        plan(hold = 30),
        maxCreditedSampleGapSeconds = 1.0,
    ).apply {
        start()
        hold(pulling, 1.0)
        micros += 250_000u
        now += 0.25
        send(RunnerEvent.Sample(ForceSample(pulling, micros)))
    }

    /// The same gap on the one gauge that timestamps its own samples credits NOTHING.
    private fun deviceClockDropsTheWholeGap() =
        RunnerTrace("device-clock-drops-the-whole-gap", plan(hold = 30)).apply {
            start()
            hold(pulling, 1.0)
            micros += 5_000_000u
            now += 5.0
            send(RunnerEvent.Sample(ForceSample(pulling, micros)))
        }

    /// **The per-hand freeze.** 20–30 % of a 40 kg left and a 36 kg right is 8–12 and
    /// 7–11 kg, and each `RepSummary` records the band ITS hand was asked for. 10 kg sits
    /// inside both, so one trace exercises both hands.
    private fun perHandMaxFreeze() = RunnerTrace(
        "per-hand-max-freeze",
        SessionPlan(
            name = "Per hand",
            sets = listOf(SetPlan(id = setID(0), grip = fourFinger, repsPerSide = 1)),
            handMode = HandMode.alternateEachRep,
            holdSeconds = 3,
            restSeconds = 1,
            leadInSeconds = 0,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        ),
        maxes = listOf(
            Triple(fourFinger.key, Side.left, 40.0),
            Triple(fourFinger.key, Side.right, 36.0),
        ),
    ).apply {
        start()
        hold(10.0, 3.5)
        letGo()
        wait(1.2)
        hold(10.0, 3.5)
    }
}

/// Regenerates `Fixtures/runner/*.json`, or — by default — proves the files on disk are
/// exactly what this engine records.
class RunnerTraceTests {

    @Test
    fun theRecordedScenariosMatchTheFixturesOnDisk() {
        val recording = System.getProperty("getagrip.record") != null
        val directory = File(Fixtures.root, "runner")
        if (recording) directory.mkdirs()

        for (trace in RunnerScenarios.all()) {
            val text = RunnerTrace.pretty(trace.document()) + "\n"
            val file = File(directory, "${trace.name}.json")
            if (recording) {
                file.writeText(text)
            } else {
                assertEquals(
                    text, file.readText(),
                    "${trace.name}: the fixture on disk is not what this engine records. " +
                        "Regenerate deliberately with -Dgetagrip.record=1 and re-run the oracle.",
                )
            }
        }
    }
}
