// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

enum TareConfirmationDecision: Sendable, Equatable {
    case tare
    case reask
    case reject
}

/// What a tap on Tare should actually do.
enum TareTapDecision: Sendable, Equatable {
    case blocked
    /// **No live reading, so the load is UNKNOWN — waking the stream is the only safe
    /// move.** `DeviceStore` forces `currentKg` to 0 when samples go stale while the link
    /// stays up, which is exactly what a Siri interruption produces. Treating that false
    /// zero as "unloaded" would skip the confirmation and tare a climber still hanging on
    /// the edge, corrupting every reading afterwards — the precise corruption the
    /// confirmation exists to prevent, arriving through the very fault being diagnosed.
    ///
    /// Waking rather than refusing also keeps the promise the empty state already makes:
    /// "Connected, but no readings yet. Tap Tare to wake it."
    case wakeStream
    case confirm
    case tare
}

/// Pure policy for the runner's tare control. Keeping the load decision separate from
/// SwiftUI makes the confirmation threshold and its revalidation rule testable without
/// pretending the Simulator can exercise a CoreBluetooth gauge.
enum TarePolicy {
    static let confirmationThresholdKg = 1.0
    /// A named tolerance avoids exact-equality loops from sensor jitter while requiring a
    /// signed load move large enough to invalidate the number shown in the alert.
    static let confirmationToleranceKg = 0.5

    /// **Armed ALLOWS taring** (2026-08-18, Nuri: "I should be able to tare before I
    /// start during the first pull. Assumably, that's when most of the taring would
    /// happen") — and he is right about when: getting set up while the screen says
    /// PULL is exactly the taring moment. It is also safe, for reasons the other
    /// machinery already provides: nothing accrues during armed, so there is no clock
    /// for a tare to corrupt; a load worth worrying about (≥1 kg) goes through the
    /// confirmation, which revalidates the PHASE before writing — a pull that engaged
    /// mid-alert reads `.working` and rejects; and the tap itself re-reads the phase,
    /// so a rep that just started blocks on its own. `.working` and `.releasing` stay
    /// forbidden: one has the clock running, the other a hand still on the edge.
    static func phaseAllowsTare(_ phase: RunnerPhase) -> Bool {
        switch phase {
        case .paused(let inner): phaseAllowsTare(inner)
        case .working, .releasing: false
        case .idle, .leadIn, .armed, .resting, .finished: true
        }
    }

    static func shouldConfirm(readingKg: Double) -> Bool {
        abs(readingKg) >= confirmationThresholdKg
    }

    static func readingIsStable(promptedKg: Double, currentKg: Double) -> Bool {
        abs(currentKg - promptedKg) <= confirmationToleranceKg
    }

    /// How old the newest sample may be and still be treated as the live load.
    ///
    /// Much tighter than `DeviceStore.isSignalFresh`, deliberately. That diagnostic flag
    /// only flips once a 500 ms watchdog observes a sample already
    /// over a second old — so it can still say "fresh" ~1.5 s after the stream stopped,
    /// which is long enough for a climber to load the edge against a reading frozen at
    /// 0 kg. A tare is irreversible for the rest of the session; it gets its own bound.
    static let liveReadingMaxAgeSeconds = 0.3

    /// **Staleness is checked BEFORE the phase, and that order is the point.** Waking the
    /// stream never tares, so there is no phase in which it is unsafe — and the phases
    /// that forbid taring (`.working`, `.releasing`) are exactly the ones where a dead
    /// stream hurts most. Checking the phase first would disable the button during
    /// a pull and leave "tap to wake it" impossible at the only moment it matters.
    /// `isReadingLive` is the OBSERVABLE liveness, so one call computes the mode the
    /// button draws AND the action it performs — label, glyph, enabled state and tap all
    /// from a single decision per render. Deriving the label from an observation-ignored
    /// clock is how you get a button that says "Tare" and does something else, because
    /// nothing re-renders it when the boundary passes.
    ///
    /// **`isLoadedForTare` is a coarse Bool, not the raw kilogram figure.** `currentKg`
    /// changes at sample rate (~80 Hz); `DeviceStore` publishes this flag change-guarded,
    /// the same shape as `isReadingLive`, so a render-path read of it cannot re-evaluate
    /// the whole button body 80×/s the way reading `currentKg` directly used to. The
    /// tap's own action closure may still read `currentKg` for the exact figure — a read
    /// inside a closure creates no observation dependency.
    static func tapDecision(phase: RunnerPhase, isReadingLive: Bool,
                            isLoadedForTare: Bool) -> TareTapDecision {
        guard isReadingLive else { return .wakeStream }
        guard phaseAllowsTare(phase) else { return .blocked }
        return isLoadedForTare ? .confirm : .tare
    }

    /// The exact age, checked at ACTION time, after the rendered decision. The observable
    /// flag lags by up to one watchdog tick; this closes that window in the safe
    /// direction — a tap that was drawn as "Tare" wakes instead if the samples have
    /// actually stopped. It can only ever downgrade, never authorize.
    ///
    /// **`maxAgeSeconds` defaults to `liveReadingMaxAgeSeconds`** — this constant itself
    /// stays the Tindeq's 0.3 s, untouched. A broadcast gauge's caller passes
    /// `DeviceStore.tareReadingMaxAge` instead, so the same number that decides the
    /// button's mode also decides what the tap does with it — the two disagreeing is
    /// what "oscillating back and forth" (Nuri, 2026-08-17) actually was.
    static func isSafeToTareNow(sampleAge: TimeInterval?,
                                maxAgeSeconds: TimeInterval = liveReadingMaxAgeSeconds) -> Bool {
        guard let sampleAge else { return false }
        return sampleAge <= maxAgeSeconds
    }

    /// The exact sample age is checked here too, not only at the tap. The alert can be open
    /// across the moment the samples stop — Siri is precisely that moment — and a reading
    /// that went stale mid-alert collapses to a false 0 kg, which would otherwise sail
    /// through the tolerance check and authorize a tare against a load nobody can see.
    ///
    /// Same `maxAgeSeconds` default as `isSafeToTareNow`, and the same reason: the
    /// constant stays fixed, the broadcast caller supplies its own bound.
    static func confirmationDecision(promptedKg: Double, currentKg: Double,
                                     promptedEpoch: UInt64, currentEpoch: UInt64,
                                     isConnected: Bool, sampleAge: TimeInterval?,
                                     phase: RunnerPhase,
                                     maxAgeSeconds: TimeInterval = liveReadingMaxAgeSeconds) -> TareConfirmationDecision {
        guard isConnected, phaseAllowsTare(phase), promptedEpoch == currentEpoch,
              let sampleAge, sampleAge <= maxAgeSeconds else {
            return .reject
        }
        return readingIsStable(promptedKg: promptedKg, currentKg: currentKg) ? .tare : .reask
    }

    static func disabledReason(for phase: RunnerPhase) -> String? {
        switch phase {
        case .paused(let inner): disabledReason(for: inner)
        case .working: String(localized: "Tare is unavailable during the pull.")
        case .releasing: String(localized: "Tare is unavailable while waiting for you to let go.")
        case .idle, .leadIn, .armed, .resting, .finished: nil
        }
    }

    /// What the BUTTON says while it is disabled — the same reason as `disabledReason`,
    /// cut to fit a half-width control.
    ///
    /// The reason has to be visible, not only spoken: a dimmed control with no
    /// explanation is the failure this whole change exists to correct, and swapping the
    /// label is the only way to say it here without a caption line that would shove the
    /// row below it down at every rep transition.
    static func disabledLabel(for phase: RunnerPhase) -> String? {
        switch phase {
        case .paused(let inner): disabledLabel(for: inner)
        case .working: String(localized: "Pulling")
        case .releasing: String(localized: "Let go")
        case .idle, .leadIn, .armed, .resting, .finished: nil
        }
    }
}

/// Pure policy for the runner's Pause/Resume and Skip controls — the same shape as
/// `TarePolicy` above, for the same reason: keeping the enabled/disabled decision free
/// of SwiftUI makes it directly testable.
///
/// **`.idle` is every session opened before the gauge has connected** (the screen reads
/// CONNECTING, indefinitely if it never answers). Before this policy existed, Pause and
/// both Skip buttons drew full `PressFeedbackButtonStyle` press-down feedback there and
/// silently did nothing: `SessionRunner.pause(at:)` returns `[]` for `.idle`, and
/// `endCurrentRep`/`skipSet` both guard `!phase.isPaused` — which additionally makes Skip
/// dead any time the session is paused. With chalk on your hands there is no way to tell
/// "nothing happened" from "the app is broken" — a disabled control has to SAY why, the
/// same rule `TarePolicy.disabledLabel` already follows.
enum RunnerControlPolicy {
    /// Pause/Resume is the SAME button throughout a pause — resuming is what makes it
    /// live again — so it stays enabled once paused. Only the pre-connect window
    /// disables it.
    static func pauseEnabled(for phase: RunnerPhase) -> Bool {
        if case .idle = phase { return false }
        return true
    }

    /// Skip is additionally dead while paused: `endCurrentRep`/`skipSet` both guard
    /// `!phase.isPaused`, because skipping a rep whose clock the climber cannot see is
    /// running would silently record an outcome for a hold that is not happening.
    static func skipEnabled(for phase: RunnerPhase) -> Bool {
        if case .idle = phase { return false }
        return !phase.isPaused
    }

    /// Full sentences, for the accessibility HINT — the buttons keep their identity
    /// labels while disabled. Swapping the label spent the two Skip buttons' names on
    /// the same word twice ("Paused" beside "Paused"), and the reason is already the
    /// largest text on the screen: the prompt renders PAUSED / CONNECTING in a heavy
    /// large title directly above these controls, so the visible explanation the house
    /// rule demands is on screen without the buttons repeating it.
    static func pauseDisabledReason(for phase: RunnerPhase) -> String? {
        pauseEnabled(for: phase) ? nil : String(localized: "Pause is unavailable while connecting.")
    }

    static func skipDisabledReason(for phase: RunnerPhase) -> String? {
        guard !skipEnabled(for: phase) else { return nil }
        if case .idle = phase { return String(localized: "Skip is unavailable while connecting.") }
        return String(localized: "Skip is unavailable while paused.")
    }
}
