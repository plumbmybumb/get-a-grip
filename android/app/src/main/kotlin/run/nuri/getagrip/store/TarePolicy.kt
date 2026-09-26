// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.RunnerPhase
import kotlin.math.abs

enum class TareConfirmationDecision {
    tare,
    reask,
    reject,
}

/// What a tap on Tare should actually do.
enum class TareTapDecision {
    blocked,

    /// **No live reading, so the load is UNKNOWN — waking the stream is the only safe
    /// move.** `DeviceStore` forces `currentKg` to 0 when samples go stale on a live link,
    /// which is what an interruption produces. Reading that false zero as "unloaded" would
    /// skip the confirmation and tare a climber still on the edge — the corruption the
    /// confirmation exists to prevent, arriving through the fault being diagnosed.
    ///
    /// Waking also keeps the empty state's promise: "Connected, but no readings yet. Tap
    /// Tare to wake it."
    wakeStream,

    confirm,
    tare,
}

/// Pure policy for the runner's tare control, so the confirmation threshold and its
/// revalidation are testable without a Bluetooth gauge.
object TarePolicy {
    const val confirmationThresholdKg = 1.0

    /// Tolerates sensor jitter while requiring a signed load move large enough to
    /// invalidate the number the alert showed.
    const val confirmationToleranceKg = 0.5

    /// **Armed ALLOWS taring** (Nuri, 2026-08-18): setting up while the screen says PULL is
    /// exactly when people tare. It is safe: nothing accrues while armed; a load ≥1 kg goes
    /// through the confirmation, which revalidates the PHASE (a pull that engaged mid-alert
    /// reads `Working` and rejects); and the tap itself re-reads the phase. `Working` and
    /// `Releasing` stay forbidden: clock running, or a hand still on the edge.
    fun phaseAllowsTare(phase: RunnerPhase): Boolean = when (phase) {
        is RunnerPhase.Paused -> phaseAllowsTare(phase.before)
        is RunnerPhase.Working, is RunnerPhase.Releasing -> false
        else -> true
    }

    fun shouldConfirm(readingKg: Double): Boolean = abs(readingKg) >= confirmationThresholdKg

    fun readingIsStable(promptedKg: Double, currentKg: Double): Boolean =
        abs(currentKg - promptedKg) <= confirmationToleranceKg

    /// How old the newest sample may be and still count as the live load. Much tighter than
    /// `DeviceStore.isSignalFresh`, which can read "fresh" ~1.5 s after the stream stopped
    /// (500 ms watchdog, one-second threshold) — long enough to load the edge against a
    /// reading frozen at 0 kg. A tare is irreversible, so it gets its own bound.
    const val liveReadingMaxAgeSeconds = 0.3

    /// **Staleness is checked BEFORE the phase.** Waking never tares, so no phase makes it
    /// unsafe, and the phases that forbid taring (`Working`, `Releasing`) are where a dead
    /// stream hurts most. Phase first would disable "tap to wake it" at the only moment it
    /// matters.
    ///
    /// `isReadingLive` is the OBSERVABLE liveness, so one decision per render gives the
    /// button's label, glyph, enabled state and action.
    ///
    /// **`isLoadedForTare` is a coarse Boolean, not raw kilograms**, change-guarded like
    /// `isReadingLive`, so the render path cannot re-evaluate the button at ~80 Hz.
    fun tapDecision(
        phase: RunnerPhase,
        isReadingLive: Boolean,
        isLoadedForTare: Boolean,
    ): TareTapDecision {
        if (!isReadingLive) return TareTapDecision.wakeStream
        if (!phaseAllowsTare(phase)) return TareTapDecision.blocked
        return if (isLoadedForTare) TareTapDecision.confirm else TareTapDecision.tare
    }

    /// The exact age, checked at ACTION time. The observable flag lags by up to a watchdog
    /// tick; this closes that window in the safe direction (a tap drawn as "Tare" wakes
    /// instead). It can only downgrade, never authorize.
    ///
    /// **`maxAgeSeconds` defaults to `liveReadingMaxAgeSeconds`** (the Tindeq's 0.3 s). A
    /// broadcast gauge's caller passes `DeviceStore.tareReadingMaxAge`, so one number
    /// decides both mode and action.
    fun isSafeToTareNow(
        sampleAge: Double?,
        maxAgeSeconds: Double = liveReadingMaxAgeSeconds,
    ): Boolean {
        if (sampleAge == null) return false
        return sampleAge <= maxAgeSeconds
    }

    /// The exact sample age is checked here too: samples can stop while the alert is open,
    /// and a stale reading collapses to a false 0 kg that would pass the tolerance check
    /// and authorize a tare against an unseen load.
    fun confirmationDecision(
        promptedKg: Double,
        currentKg: Double,
        promptedEpoch: ULong,
        currentEpoch: ULong,
        isConnected: Boolean,
        sampleAge: Double?,
        phase: RunnerPhase,
        maxAgeSeconds: Double = liveReadingMaxAgeSeconds,
    ): TareConfirmationDecision {
        if (!isConnected || !phaseAllowsTare(phase) || promptedEpoch != currentEpoch) {
            return TareConfirmationDecision.reject
        }
        if (sampleAge == null || sampleAge > maxAgeSeconds) return TareConfirmationDecision.reject
        return if (readingIsStable(promptedKg, currentKg)) {
            TareConfirmationDecision.tare
        } else {
            TareConfirmationDecision.reask
        }
    }

    fun disabledReason(phase: RunnerPhase): String? = when (phase) {
        is RunnerPhase.Paused -> disabledReason(phase.before)
        is RunnerPhase.Working -> L10n.tr("Tare is unavailable during the pull.")
        is RunnerPhase.Releasing ->
            L10n.tr("Tare is unavailable until you let go.")
        else -> null
    }

    /// What the BUTTON says while disabled — `disabledReason` cut to a half-width control.
    /// The reason must be visible: a dimmed control with no explanation is the failure this
    /// rule corrects, and a caption line would shove the row below down at every rep
    /// transition.
    fun disabledLabel(phase: RunnerPhase): String? = when (phase) {
        is RunnerPhase.Paused -> disabledLabel(phase.before)
        is RunnerPhase.Working -> L10n.tr("Pulling")
        is RunnerPhase.Releasing -> L10n.tr("Let go")
        else -> null
    }
}

/// Pure policy for the runner's Pause/Resume and Skip controls, testable like `TarePolicy`.
///
/// **`Idle` is every session opened before the gauge connects** (CONNECTING, indefinitely
/// if it never answers). Pause and both Skips once drew press feedback there and did
/// nothing (`SessionRunner.pause` returns no cues for `Idle`; `endCurrentRep`/`skipSet`
/// guard `!phase.isPaused`, so Skip was also dead while paused). With chalk on your hands
/// "nothing happened" looks like "broken"; a disabled control has to SAY why.
object RunnerControlPolicy {
    /// Pause/Resume is the SAME button throughout a pause, so it stays enabled once paused;
    /// only pre-connect disables it.
    fun pauseEnabled(phase: RunnerPhase): Boolean = phase !is RunnerPhase.Idle

    /// Skip is dead while paused: `endCurrentRep`/`skipSet` guard `!phase.isPaused`,
    /// because skipping a rep whose clock is not visibly running would record an outcome
    /// for a hold that is not happening.
    fun skipEnabled(phase: RunnerPhase): Boolean =
        phase !is RunnerPhase.Idle && !phase.isPaused

    /// Full sentences for the accessibility HINT; the buttons keep their labels while
    /// disabled. Swapping labels put "Paused" beside "Paused", and the reason is already
    /// the largest text on screen.
    fun pauseDisabledReason(phase: RunnerPhase): String? =
        if (pauseEnabled(phase)) null else L10n.tr("Pause is unavailable while connecting.")

    fun skipDisabledReason(phase: RunnerPhase): String? {
        if (skipEnabled(phase)) return null
        if (phase is RunnerPhase.Idle) {
            return L10n.tr("Skip is unavailable while connecting.")
        }
        return L10n.tr("Skip is unavailable while paused.")
    }
}
