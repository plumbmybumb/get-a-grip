// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.math.max

// TRANSLATION NOTE: on iOS these three policies live in `Sources/Runner/RunnerSession.swift`
// for historical reasons only. Each is a pure function of numbers, tested in
// `SessionRunnerTests`, and a rule about driving the ENGINE — so they belong here.

enum class StaleBatchHealDecision {
    hold,
    fire,
    expire,
}

/// Pure policy for the one recovery break a genuine stream re-kick authorizes.
/// Keeping the decision free of clocks and BLE makes the fail-closed boundary directly
/// testable: stale rejection alone can never heal itself.
object StaleBatchHealer {
    const val armLifetime: Double = 5.0
    const val requiredRejectingChecks: Int = 2
    const val minimumHealInterval: Double = 2.0

    fun decision(
        armed: Boolean,
        armAge: Double,
        consecutiveRejectingChecks: Int,
        timeSinceLastHeal: Double,
    ): StaleBatchHealDecision {
        if (!armed) return StaleBatchHealDecision.hold
        if (armAge >= armLifetime) return StaleBatchHealDecision.expire
        if (consecutiveRejectingChecks < requiredRejectingChecks ||
            timeSinceLastHeal < minimumHealInterval
        ) {
            return StaleBatchHealDecision.hold
        }
        return StaleBatchHealDecision.fire
    }
}

/// Whether losing the foreground has to PAUSE the session — a question about whether
/// samples can still reach us, answered from capabilities rather than from a device name.
///
/// - **No link** → pause on any move off `.active`: nothing keeps the process alive,
///   and a rep would silently stall.
/// - **A connected gauge that sustains background streaming** (the Progressor) → keep
///   running; swiping home to change the music keeps the workout alive (2026-08-09).
/// - **A connected gauge that does NOT** (broadcast scales, whose background scan goes
///   silent) → pause on `.background`: the app lost the ability to measure, which is not
///   a dropout — and a dropout must never end a rep.
///
/// `.inactive` never pauses a connected session: a banner or Control Centre pull is not
/// a suspension, and a scenePhase pause needs a deliberate tap to come back from.
object BackgroundPausePolicy {
    fun pausesOnLeavingForeground(
        isBackground: Boolean,
        isConnected: Boolean,
        sustainsBackgroundStreaming: Boolean,
    ): Boolean {
        if (!isConnected) return true
        return isBackground && !sustainsBackgroundStreaming
    }
}

/// The two constants a running session reads off the gauge it is driving.
///
/// TRANSLATION NOTE: `RunnerSession.silenceThreshold(forRate:isBroadcast:)` and
/// `RunnerSession.syntheticClockGapCapSeconds` on iOS. Named for what they describe
/// rather than for the class that happened to hold them.
object SamplePacing {

    /// How much silence means the stream needs re-kicking, for THIS gauge.
    ///
    /// Eight samples' worth of silence, floored at the Progressor's 0.8 s. A fixed 0.8 s
    /// assumed 80 Hz: at 8 Hz two coalesced advertisements spend a third of it and a
    /// healthy stream gets re-kicked. Eight samples is the same judgement at the gauge's
    /// own rate; the floor keeps the Progressor where hardware sessions put it.
    fun silenceThreshold(forRate: Double, isBroadcast: Boolean = false): Double {
        // Broadcast delivery is bursty by nature and a re-kick is a no-op on a running
        // scan, so a three-second floor keeps the watchdog for a scan that actually died
        // rather than beating in time with the radio (hardware session, 2026-08-17).
        val eightSamples = 8.0 / max(1.0, forRate)
        return if (isBroadcast) max(3.0, eightSamples) else max(0.8, eightSamples)
    }

    /// One second — the same clamp `SessionRunner.holdTick` puts on a stalled wall clock:
    /// a hand on the edge either side of a gap plausibly held through a second of silence;
    /// anything longer is the radio's story, not the climber's.
    const val syntheticClockGapCapSeconds: Double = 1.0
}
