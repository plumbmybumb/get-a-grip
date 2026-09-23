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
    /// move.** `DeviceStore` forces `currentKg` to 0 when samples go stale with the link
    /// up (a Siri interruption does exactly this). Treating that false zero as "unloaded"
    /// would skip the confirmation and tare a climber still on the edge.
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

    /// **Armed ALLOWS taring** (Nuri, 2026-08-18): getting set up while the screen says
    /// PULL is the taring moment. Safe because nothing accrues during armed, a load ≥1 kg
    /// goes through the confirmation (which revalidates the PHASE, so a pull that engaged
    /// mid-alert rejects), and the tap re-reads the phase. `.working` and `.releasing`
    /// stay forbidden: one has the clock running, the other a hand still on the edge.
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
    /// Much tighter than `DeviceStore.isSignalFresh`, which can still say "fresh" ~1.5 s
    /// after the stream stopped — long enough to load the edge against a reading frozen
    /// at 0 kg. A tare is irreversible, so it gets its own bound.
    static let liveReadingMaxAgeSeconds = 0.3

    /// **Staleness is checked BEFORE the phase, and that order is the point.** Waking
    /// never tares, so it is safe in every phase — and the phases that forbid taring are
    /// exactly where a dead stream hurts most. Phase first would make waking impossible
    /// during a pull.
    ///
    /// `isReadingLive` is the OBSERVABLE liveness, so label, glyph, enabled state and tap
    /// all come from one decision per render; an observation-ignored clock would leave a
    /// button saying "Tare" and doing something else.
    ///
    /// **`isLoadedForTare` is a coarse Bool, not the raw kilogram figure** — see
    /// `DeviceStore.currentKg`.
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
    /// **`maxAgeSeconds` defaults to `liveReadingMaxAgeSeconds`** (the Tindeq's 0.3 s);
    /// callers pass `DeviceStore.tareReadingMaxAge`, so the button's mode and the tap
    /// read the same number — see there.
    static func isSafeToTareNow(sampleAge: TimeInterval?,
                                maxAgeSeconds: TimeInterval = liveReadingMaxAgeSeconds) -> Bool {
        guard let sampleAge else { return false }
        return sampleAge <= maxAgeSeconds
    }

    /// The exact sample age is checked here too: the alert can be open across the moment
    /// the samples stop (Siri), and a reading that went stale mid-alert collapses to a
    /// false 0 kg that would sail through the tolerance check.
    ///
    /// Same `maxAgeSeconds` default as `isSafeToTareNow`.
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
    /// The reason has to be visible, not only spoken, and swapping the label is the only
    /// way without a caption line that would move the row below at every rep transition.
    static func disabledLabel(for phase: RunnerPhase) -> String? {
        switch phase {
        case .paused(let inner): disabledLabel(for: inner)
        case .working: String(localized: "Pulling")
        case .releasing: String(localized: "Let go")
        case .idle, .leadIn, .armed, .resting, .finished: nil
        }
    }
}

/// Pure policy for the runner's Pause/Resume and Skip controls, testable without SwiftUI.
///
/// **`.idle` is every session opened before the gauge has connected** (CONNECTING).
/// There `SessionRunner.pause(at:)` returns `[]`, and Skip is also dead while paused, so
/// live-looking buttons silently did nothing. A disabled control has to SAY why.
enum RunnerControlPolicy {
    /// Pause/Resume is the SAME button throughout a pause — resuming is what makes it
    /// live again — so it stays enabled once paused. Only the pre-connect window
    /// disables it.
    static func pauseEnabled(for phase: RunnerPhase) -> Bool {
        if case .idle = phase { return false }
        return true
    }

    /// Skip is dead while paused: `endCurrentRep`/`skipSet` guard `!phase.isPaused`, or
    /// it would record an outcome for a hold that is not happening.
    static func skipEnabled(for phase: RunnerPhase) -> Bool {
        if case .idle = phase { return false }
        return !phase.isPaused
    }

    /// Full sentences, for the accessibility HINT — the buttons keep their labels while
    /// disabled, since the prompt above already shows PAUSED / CONNECTING in a large title.
    static func pauseDisabledReason(for phase: RunnerPhase) -> String? {
        pauseEnabled(for: phase) ? nil : String(localized: "Pause is unavailable while connecting.")
    }

    static func skipDisabledReason(for phase: RunnerPhase) -> String? {
        guard !skipEnabled(for: phase) else { return nil }
        if case .idle = phase { return String(localized: "Skip is unavailable while connecting.") }
        return String(localized: "Skip is unavailable while paused.")
    }
}
