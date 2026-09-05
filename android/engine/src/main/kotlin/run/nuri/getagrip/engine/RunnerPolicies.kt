// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.math.max

// TRANSLATION NOTE: these three policies live in `Sources/Runner/RunnerSession.swift`
// on iOS — the app layer — for historical reasons only. Each of them is a pure
// function of numbers with no clock, no BLE and no SwiftUI in it, each is asserted by
// `SessionRunnerTests`, and each is a rule about how the ENGINE must be driven. They
// belong here, so the Android store can read the same answers the runner does.

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
/// Three cases, and the middle one is the one `bluetooth-central` bought:
/// - **No link at all** → pause on any move off `.active`. Nothing keeps the process
///   alive, samples stop, and a rep would silently stall at whatever it had accrued.
/// - **A connected gauge that sustains background streaming** (the Progressor) → keep
///   running. Swiping home to change the music keeps the workout alive and the Live
///   Activity carries it (Nuri, 2026-08-09).
/// - **A connected gauge that does NOT** (the broadcast scales: CoreBluetooth coalesces
///   duplicate advertisements in the background, so the scan effectively goes silent) →
///   pause on `.background`. This is the app losing the ability to measure, which is the
///   same rule as having no gauge, not a dropout — and a dropout is the one thing that
///   must never end a rep.
///
/// `.inactive` deliberately does NOT pause a connected session either way: a
/// notification banner or a Control Centre pull is not a suspension, the scan is still
/// running, and a scenePhase pause needs a deliberate tap to come back from.
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
    /// Eight samples' worth of silence, floored at the Progressor's 0.8 s.
    ///
    /// 0.8 s was justified by "the gauge sends at 80 Hz", which is true of exactly one of
    /// the seven kinds: at 8 Hz an ordinary sample gap is 125 ms and two coalesced
    /// advertisements already spend a third of that budget, so a threshold that never moves
    /// re-kicks a perfectly healthy stream. Eight samples is the same judgement the 0.8 s
    /// expressed — silence long enough that a rep is quietly not being counted — read off
    /// the rate the gauge actually claims. The floor keeps the Progressor's number exactly
    /// where the hardware sessions put it.
    fun silenceThreshold(forRate: Double, isBroadcast: Boolean = false): Double {
        // A broadcast gauge's delivery is bursty by NATURE — multi-second holes are
        // ordinary advertisements, not a stalled stream, and the re-kick is a no-op
        // against an already-running scan anyway. A three-second floor keeps the
        // watchdog for the case it exists for (a scan that actually died) instead of
        // letting it beat in time with the radio (Nuri's hardware session, 2026-08-17).
        val eightSamples = 8.0 / max(1.0, forRate)
        return if (isBroadcast) max(3.0, eightSamples) else max(0.8, eightSamples)
    }

    /// One second — deliberately the same clamp `SessionRunner.holdTick` puts on a
    /// stalled wall clock, because it is the same judgement: a hand that was on the edge
    /// before the gap and still on it after plausibly held through a second of silence,
    /// and anything longer is the radio's story rather than the climber's.
    const val syntheticClockGapCapSeconds: Double = 1.0
}
