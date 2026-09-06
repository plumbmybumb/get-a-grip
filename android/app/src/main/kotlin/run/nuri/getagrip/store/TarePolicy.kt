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
    /// move.** `DeviceStore` forces `currentKg` to 0 when samples go stale while the link
    /// stays up, which is exactly what an interruption produces. Treating that false zero
    /// as "unloaded" would skip the confirmation and tare a climber still hanging on the
    /// edge, corrupting every reading afterwards — the precise corruption the confirmation
    /// exists to prevent, arriving through the very fault being diagnosed.
    ///
    /// Waking rather than refusing also keeps the promise the empty state already makes:
    /// "Connected, but no readings yet. Tap Tare to wake it."
    wakeStream,

    confirm,
    tare,
}

/// Pure policy for the runner's tare control. Keeping the load decision separate from the
/// UI makes the confirmation threshold and its revalidation rule testable without
/// pretending a JVM test can exercise a Bluetooth gauge.
object TarePolicy {
    const val confirmationThresholdKg = 1.0

    /// A named tolerance avoids exact-equality loops from sensor jitter while requiring a
    /// signed load move large enough to invalidate the number shown in the alert.
    const val confirmationToleranceKg = 0.5

    /// **Armed ALLOWS taring** (2026-08-18, Nuri: "I should be able to tare before I start
    /// during the first pull. Assumably, that's when most of the taring would happen") —
    /// and he is right about when: getting set up while the screen says PULL is exactly
    /// the taring moment. It is also safe, for reasons the other machinery already
    /// provides: nothing accrues during armed, so there is no clock for a tare to corrupt;
    /// a load worth worrying about (≥1 kg) goes through the confirmation, which
    /// revalidates the PHASE before writing — a pull that engaged mid-alert reads
    /// `Working` and rejects; and the tap itself re-reads the phase, so a rep that just
    /// started blocks on its own. `Working` and `Releasing` stay forbidden: one has the
    /// clock running, the other a hand still on the edge.
    fun phaseAllowsTare(phase: RunnerPhase): Boolean = when (phase) {
        is RunnerPhase.Paused -> phaseAllowsTare(phase.before)
        is RunnerPhase.Working, is RunnerPhase.Releasing -> false
        else -> true
    }

    fun shouldConfirm(readingKg: Double): Boolean = abs(readingKg) >= confirmationThresholdKg

    fun readingIsStable(promptedKg: Double, currentKg: Double): Boolean =
        abs(currentKg - promptedKg) <= confirmationToleranceKg

    /// How old the newest sample may be and still be treated as the live load.
    ///
    /// Much tighter than `DeviceStore.isSignalFresh`, deliberately. That diagnostic flag
    /// only flips once a 500 ms watchdog observes a sample already
    /// over a second old — so it can still say "fresh" ~1.5 s after the stream stopped,
    /// which is long enough for a climber to load the edge against a reading frozen at
    /// 0 kg. A tare is irreversible for the rest of the session; it gets its own bound.
    const val liveReadingMaxAgeSeconds = 0.3

    /// **Staleness is checked BEFORE the phase, and that order is the point.** Waking the
    /// stream never tares, so there is no phase in which it is unsafe — and the phases
    /// that forbid taring (`Working`, `Releasing`) are exactly the ones where a dead
    /// stream hurts most. Checking the phase first would disable the button during a pull
    /// and leave "tap to wake it" impossible at the only moment it matters.
    ///
    /// `isReadingLive` is the OBSERVABLE liveness, so one call computes the mode the
    /// button draws AND the action it performs — label, glyph, enabled state and tap all
    /// from a single decision per render.
    ///
    /// **`isLoadedForTare` is a coarse Boolean, not the raw kilogram figure.** `currentKg`
    /// changes at sample rate (~80 Hz); `DeviceStore` publishes this flag change-guarded,
    /// the same shape as `isReadingLive`, so a render-path read of it cannot re-evaluate
    /// the whole button body 80×/s the way reading `currentKg` directly used to.
    fun tapDecision(
        phase: RunnerPhase,
        isReadingLive: Boolean,
        isLoadedForTare: Boolean,
    ): TareTapDecision {
        if (!isReadingLive) return TareTapDecision.wakeStream
        if (!phaseAllowsTare(phase)) return TareTapDecision.blocked
        return if (isLoadedForTare) TareTapDecision.confirm else TareTapDecision.tare
    }

    /// The exact age, checked at ACTION time, after the rendered decision. The observable
    /// flag lags by up to one watchdog tick; this closes that window in the safe direction
    /// — a tap that was drawn as "Tare" wakes instead if the samples have actually
    /// stopped. It can only ever downgrade, never authorize.
    ///
    /// **`maxAgeSeconds` defaults to `liveReadingMaxAgeSeconds`** — this constant itself
    /// stays the Tindeq's 0.3 s, untouched. A broadcast gauge's caller passes
    /// `DeviceStore.tareReadingMaxAge` instead, so the same number that decides the
    /// button's mode also decides what the tap does with it.
    fun isSafeToTareNow(
        sampleAge: Double?,
        maxAgeSeconds: Double = liveReadingMaxAgeSeconds,
    ): Boolean {
        if (sampleAge == null) return false
        return sampleAge <= maxAgeSeconds
    }

    /// The exact sample age is checked here too, not only at the tap. The alert can be open
    /// across the moment the samples stop, and a reading that went stale mid-alert
    /// collapses to a false 0 kg, which would otherwise sail through the tolerance check
    /// and authorize a tare against a load nobody can see.
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
            L10n.tr("Tare is unavailable while waiting for you to let go.")
        else -> null
    }

    /// What the BUTTON says while it is disabled — the same reason as `disabledReason`,
    /// cut to fit a half-width control.
    ///
    /// The reason has to be visible, not only spoken: a dimmed control with no explanation
    /// is the failure this whole rule exists to correct, and swapping the label is the
    /// only way to say it here without a caption line that would shove the row below it
    /// down at every rep transition.
    fun disabledLabel(phase: RunnerPhase): String? = when (phase) {
        is RunnerPhase.Paused -> disabledLabel(phase.before)
        is RunnerPhase.Working -> L10n.tr("Pulling")
        is RunnerPhase.Releasing -> L10n.tr("Let go")
        else -> null
    }
}

/// Pure policy for the runner's Pause/Resume and Skip controls — the same shape as
/// `TarePolicy` above, for the same reason: keeping the enabled/disabled decision free of
/// the UI makes it directly testable.
///
/// **`Idle` is every session opened before the gauge has connected** (the screen reads
/// CONNECTING, indefinitely if it never answers). Before this policy existed, Pause and
/// both Skip buttons drew full press-down feedback there and silently did nothing:
/// `SessionRunner.pause` returns an empty cue list for `Idle`, and `endCurrentRep`/
/// `skipSet` both guard `!phase.isPaused` — which additionally makes Skip dead any time
/// the session is paused. With chalk on your hands there is no way to tell "nothing
/// happened" from "the app is broken" — a disabled control has to SAY why.
object RunnerControlPolicy {
    /// Pause/Resume is the SAME button throughout a pause — resuming is what makes it live
    /// again — so it stays enabled once paused. Only the pre-connect window disables it.
    fun pauseEnabled(phase: RunnerPhase): Boolean = phase !is RunnerPhase.Idle

    /// Skip is additionally dead while paused: `endCurrentRep`/`skipSet` both guard
    /// `!phase.isPaused`, because skipping a rep whose clock the climber cannot see is
    /// running would silently record an outcome for a hold that is not happening.
    fun skipEnabled(phase: RunnerPhase): Boolean =
        phase !is RunnerPhase.Idle && !phase.isPaused

    /// Full sentences, for the accessibility HINT — the buttons keep their identity labels
    /// while disabled. Swapping the label spent the two Skip buttons' names on the same
    /// word twice ("Paused" beside "Paused"), and the reason is already the largest text
    /// on the screen.
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
