// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import AVFoundation
import CoreHaptics
import Foundation

/// Plays what `SessionRunner` asks for — and nothing else.
///
/// The runner returns `RunnerCue`s and never plays one itself, so this is the only
/// place in the app where a workout makes a noise. Everything here is pure OUTPUT:
/// no method throws, no failure propagates, and a phone with a dead audio session or
/// no haptic hardware still runs the whole session in silence. A cue that cannot be
/// played is not a reason for a set to stop.
///
/// The tones are SYNTHESISED, not shipped: nine short buffers built once at `begin()`
/// cost ~2 ms and about 200 KB of Float32, which is cheaper than the smallest useful
/// audio asset and removes a whole class of "the file didn't make it into the bundle"
/// failures. They share one A-major frame so the cues read as a family; the alarm
/// deliberately does not, which is what makes it read as wrong.
@MainActor
final class CuePlayer {

    /// Honoured live — turning sound off mid-session also drops the audio session, so
    /// the user's music un-ducks instead of staying quiet for a silent workout.
    var soundEnabled: Bool = true {
        didSet {
            guard isRunning, soundEnabled != oldValue else { return }
            if soundEnabled { startAudio() } else { stopAudio() }
        }
    }

    /// Checked at play time rather than latched at `begin()`, for the same reason.
    var hapticsEnabled: Bool = true

    // MARK: Audio

    /// Mono Float32. The mixer converts to whatever the route actually wants, so the
    /// buffers never have to be rebuilt when AirPods appear.
    private let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var tones: [CueTone: AVAudioPCMBuffer] = [:]
    private var audioReady = false

    // MARK: Haptics

    /// False on every Simulator. Everything haptic below short-circuits on it rather
    /// than logging or throwing.
    private let hapticsSupported = CHHapticEngine.capabilitiesForHardware().supportsHaptics
    private var hapticEngine: CHHapticEngine?
    private var hapticEngineRunning = false

    // MARK: Lifecycle

    private var isRunning = false
    private var observers: [NSObjectProtocol] = []

    init() {}

    /// Activate the audio session and prepare both engines. Called ONCE per session:
    /// starting an `AVAudioEngine` costs tens of milliseconds and doing it per cue
    /// would put that latency between "go" and the first pull.
    func begin() {
        guard !isRunning else { return }
        isRunning = true
        buildTones()
        buildGraph()
        installObservers()
        startAudio()
        prepareHaptics()
        startHapticEngine()
    }

    /// Deactivate the audio session and stop the haptic engine. Also the ONE place
    /// observers are torn down — there is no `deinit` doing it behind this.
    func end() {
        guard isRunning else { return }
        isRunning = false
        removeObservers()
        stopAudio()
        hapticEngine?.stop()
        hapticEngineRunning = false
    }

    // MARK: - The one entry point

    /// Every case is spelled out and there is NO `default:` — a cue added to
    /// `RunnerCue` must be a compile error here rather than a cue that silently
    /// never sounds.
    func play(_ cue: RunnerCue) {
        switch cue {
        case .leadInTick(let seconds), .restTick(let seconds):
            // The most-heard cue in the app, and the one most able to ruin it: a beep
            // per second through a two-minute rest is unbearable, so only the last
            // three seconds speak at all. The final second is a higher pitch so "go"
            // is anticipated rather than merely announced.
            guard (1...3).contains(seconds) else { return }
            let last = seconds == 1
            sound(last ? .tickFinal : .tick)
            haptic(last ? .crispStrong : .crisp)

        case .armed:
            sound(.go)
            haptic(.ramp)

        case .repStarted:
            // Deliberately small: "go" already sounded, and this only confirms the
            // clock caught the pull. Something louder here would compete with it.
            sound(.tick)
            haptic(.crisp)

        case .repHalfway:
            sound(.halfway)
            haptic(.soft)

        case .repEnded(let completed):
            sound(completed ? .repComplete : .alarm)
            haptic(completed ? .crispStrong : .buzz)

        case .setCompleted:
            sound(.setComplete)
            haptic(.crispStrong)

        case .sessionCompleted:
            sound(.sessionComplete)
            haptic(.heavyDouble)

        case .dropoutWarning:
            // A dip is a warning, not a verdict — the rep is still alive, so this is
            // the quiet, short form of the same alarm that ends one.
            sound(.alarmSoft)
            haptic(.buzzSoft)

        case .connectionLost:
            sound(.alarm)
            haptic(.buzz)
        }
    }

    /// A short contrasting interval, gated by the same live sound preference.
    /// Separate from the engine's measurement cues; one announcement per transition.
    func gripChanged() { sound(.gripChange) }

    // MARK: - Audio session and graph

    @discardableResult
    private func startAudio() -> Bool {
        guard soundEnabled else { return false }
        do {
            // `.playback`, NOT `.ambient`: `.ambient` obeys the mute switch, and a
            // phone face-down and muted on a mat is exactly where this app is used.
            // Ducking rather than interrupting leaves the user's music playing under
            // the cues instead of killing it for the length of a session.
            // `.mixWithOthers` and NOT `.duckOthers`. Ducking would dip whatever the
            // user is listening to on every cue — roughly one cue every five seconds
            // across a 21-minute session, which is a video that pulses for the entire
            // workout. The complaint that motivated this ("Frez pauses my YouTube") is
            // about background audio being disturbed, and a constant dip is a smaller
            // version of the same disturbance. Our cues are short, distinct tones and
            // carry over music at normal volume; the runner screen and the haptics say
            // the same things anyway.
            try AVAudioSession.sharedInstance().setCategory(
                .playback, mode: .default, options: [.mixWithOthers]
            )
            try AVAudioSession.sharedInstance().setActive(true)
            try engine.start()
        } catch {
            // Haptics-only from here. The session keeps running.
            audioReady = false
            return false
        }
        if !player.isPlaying { player.play() }
        audioReady = true
        return true
    }

    private func stopAudio() {
        player.stop()
        engine.stop()
        audioReady = false
        // `.notifyOthersOnDeactivation` is what un-ducks the user's music at the end
        // of the session rather than whenever the system next happens to notice.
        try? AVAudioSession.sharedInstance().setActive(
            false, options: .notifyOthersOnDeactivation
        )
    }

    /// Idempotent — `player.engine` is non-nil once attached, and re-`connect` on an
    /// already-connected node replaces the connection rather than duplicating it.
    /// Both matter because a route change has to be able to rebuild this in place.
    private func buildGraph() {
        guard let format else { return }
        if player.engine == nil { engine.attach(player) }
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.prepare()
    }

    private func buildTones() {
        guard tones.isEmpty, let format else { return }
        for tone in CueTone.allCases {
            tones[tone] = renderCue(tone.notes, format: format)
        }
    }

    private func sound(_ tone: CueTone) {
        guard soundEnabled, audioReady, engine.isRunning, let buffer = tones[tone] else { return }
        // Queued, NOT `.interrupts`: the runner returns cues in batches (a final rep
        // yields rep-end, set-end and session-end together) and interrupting would
        // leave only the last one audible.
        player.scheduleBuffer(buffer, at: nil, options: [], completionHandler: nil)
        if !player.isPlaying { player.play() }
    }

    // MARK: - System interruptions

    private func installObservers() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default

        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            // `Notification` is not Sendable, so the raw values are read HERE and only
            // the integers cross onto the actor.
            let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let options = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt
            Task { @MainActor in self?.handleInterruption(typeRaw: type, optionsRaw: options) }
        })

        observers.append(center.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.handleConfigurationChange() }
        })
    }

    private func removeObservers() {
        for token in observers { NotificationCenter.default.removeObserver(token) }
        observers.removeAll()
    }

    private func handleInterruption(typeRaw: UInt?, optionsRaw: UInt?) {
        guard let typeRaw, let type = AVAudioSession.InterruptionType(rawValue: typeRaw) else { return }
        switch type {
        case .began:
            // The system has already silenced us; tearing the graph down keeps a
            // half-live engine from throwing on the next cue. The runner is untouched —
            // a phone call must not cost the rep that is under way.
            player.stop()
            engine.stop()
            audioReady = false
        case .ended:
            guard isRunning,
                  AVAudioSession.InterruptionOptions(rawValue: optionsRaw ?? 0).contains(.shouldResume)
            else { return }
            startAudio()
        @unknown default:
            break
        }
    }

    /// AirPods connecting mid-set stops the engine and drops the player's connection.
    /// Without the rebuild the rest of the session is silent, which looks exactly like
    /// a broken app rather than a route change.
    private func handleConfigurationChange() {
        guard isRunning, soundEnabled else { return }
        buildGraph()
        startAudio()
    }

    // MARK: - Haptics

    private func prepareHaptics() {
        // No haptic hardware — every Simulator — is a silent no-op. Not an error, not
        // a log: it is the expected state of half the machines this runs on.
        guard hapticsSupported, hapticEngine == nil else { return }
        hapticEngine = try? CHHapticEngine()
        // LOAD-BEARING for "never interrupt what the user is listening to".
        // `CHHapticEngine` is built for haptics-AND-audio patterns, so by default it
        // participates in the audio session and can take it over — which is exactly how
        // a training app ends up pausing somebody's YouTube video without ever calling a
        // single audio API for it. We render our own tones through AVAudioEngine, so this
        // engine plays haptics ONLY and stays out of the session entirely.
        hapticEngine?.playsHapticsOnly = true
        // We own the lifetime through begin/end, so auto-shutdown would only add
        // start latency to the first tick after a long rest.
        hapticEngine?.isAutoShutdownEnabled = false
        hapticEngine?.resetHandler = { [weak self] in
            Task { @MainActor in self?.hapticEngineDidStop() }
        }
        hapticEngine?.stoppedHandler = { [weak self] _ in
            Task { @MainActor in self?.hapticEngineDidStop() }
        }
    }

    private func startHapticEngine() {
        guard hapticsSupported, let hapticEngine else { return }
        do {
            try hapticEngine.start()
            hapticEngineRunning = true
        } catch {
            hapticEngineRunning = false
        }
    }

    /// Reclaim the engine only while a session is live — `end()` stopping it is not a
    /// fault to recover from, and clearing `isRunning` first is what tells them apart.
    private func hapticEngineDidStop() {
        hapticEngineRunning = false
        guard isRunning else { return }
        startHapticEngine()
    }

    private func haptic(_ kind: CueHaptic) {
        guard hapticsEnabled, hapticsSupported, let hapticEngine else { return }
        if !hapticEngineRunning { startHapticEngine() }
        guard hapticEngineRunning else { return }
        do {
            let pattern = try CHHapticPattern(
                events: kind.events, parameterCurves: kind.parameterCurves
            )
            try hapticEngine.makePlayer(with: pattern).start(atTime: CHHapticTimeImmediate)
        } catch {
            // Swallowed on purpose: see the type's note on output never failing inward.
        }
    }
}

// MARK: - Tones

/// File-scope rather than nested, so `CaseIterable`'s `allCases` witnesses a
/// nonisolated requirement instead of inheriting `CuePlayer`'s actor.
private enum CueTone: CaseIterable {
    case tick, tickFinal, go, halfway, repComplete, setComplete, sessionComplete
    case alarm, alarmSoft, gripChange

    /// A4 / C#5 / E5 / A5 — one chord's worth of pitches, so seven cues are tellable
    /// apart by interval rather than by volume, which is what survives a gym.
    var notes: [CueNote] {
        let a4 = 440.0, cs5 = 554.37, e5 = 659.25, a5 = 880.0
        switch self {
        case .tick:
            return [CueNote(hz: [a4], seconds: 0.06, amplitude: 0.30, decay: 0.30)]
        case .tickFinal:
            return [CueNote(hz: [e5], seconds: 0.07, amplitude: 0.45, decay: 0.30)]
        case .go:
            // Rising, because it means START. A falling figure for the same event read
            // as "done" to everyone who heard it.
            return [CueNote(hz: [a4], seconds: 0.09, amplitude: 0.55, decay: 0.50),
                    CueNote(hz: [a5], seconds: 0.12, amplitude: 0.60, decay: 0.50)]
        case .gripChange:
            return [CueNote(hz: [e5], seconds: 0.10, amplitude: 0.55, decay: 0.30),
                    CueNote(hz: [cs5], seconds: 0.14, amplitude: 0.55, decay: 0.30)]
        case .halfway:
            return [CueNote(hz: [e5], seconds: 0.11, amplitude: 0.40)]
        case .repComplete:
            return [CueNote(hz: [a5], seconds: 0.16, amplitude: 0.55)]
        case .setComplete:
            // Falling and soft-edged: a set ending is a rest earned, not an alert.
            return [CueNote(hz: [a5], seconds: 0.10, amplitude: 0.45, attack: 0.10, decay: 0.50),
                    CueNote(hz: [e5], seconds: 0.14, amplitude: 0.45, attack: 0.10, decay: 0.50)]
        case .sessionComplete:
            // The one cue allowed past 250 ms — it is heard once, and it is the only
            // thing telling someone with their eyes shut that they are finished.
            return [CueNote(hz: [a4], seconds: 0.15, amplitude: 0.50, decay: 0.50),
                    CueNote(hz: [cs5], seconds: 0.15, amplitude: 0.50, decay: 0.50),
                    CueNote(hz: [e5], seconds: 0.22, amplitude: 0.55, decay: 0.60)]
        case .alarm:
            // A minor second, low, sounded together: the beating is unpleasant BY
            // CONSTRUCTION, and unpleasant is the whole message.
            return [CueNote(hz: [138.59, 146.83], seconds: 0.22, amplitude: 0.55,
                            attack: 0.03, decay: 0.90)]
        case .alarmSoft:
            return [CueNote(hz: [138.59, 146.83], seconds: 0.13, amplitude: 0.35,
                            attack: 0.03, decay: 0.90)]
        }
    }
}

/// One note of a cue: the partials sounded together, how long, and how loud.
private struct CueNote {
    var hz: [Double]
    var seconds: Double
    var amplitude: Double
    /// Attack as a fraction of the note. Fast, but never zero — a hard edge on a sine
    /// is an audible click through a phone speaker.
    var attack: Double = 0.02
    /// Exponential decay constant as a fraction of the note. Smaller is drier.
    var decay: Double = 0.35
}

/// Renders the notes end to end into one buffer, so a whole figure is a single
/// scheduled buffer and cannot be pulled apart by scheduling jitter.
private func renderCue(_ notes: [CueNote], format: AVAudioFormat) -> AVAudioPCMBuffer? {
    let rate = format.sampleRate
    let counts = notes.map { max(1, Int(($0.seconds * rate).rounded())) }
    let total = counts.reduce(0, +)
    guard total > 0,
          let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(total)),
          let channel = buffer.floatChannelData?[0]
    else { return nil }
    buffer.frameLength = AVAudioFrameCount(total)

    // ~4 ms of release on every note. The exponential tail never quite reaches zero,
    // and a truncated sine clicks.
    let releaseFrames = max(1, Int(0.004 * rate))
    var cursor = 0
    for (note, count) in zip(notes, counts) {
        let attackFrames = max(1, Int(note.attack * note.seconds * rate))
        for i in 0..<count {
            let t = Double(i) / rate
            var value = 0.0
            for hz in note.hz { value += sin(2 * .pi * hz * t) }
            value /= Double(note.hz.count)
            let rise = min(1, Double(i) / Double(attackFrames))
            let fall = exp(-t / (note.seconds * note.decay))
            let release = min(1, Double(count - i) / Double(releaseFrames))
            channel[cursor + i] = Float(value * note.amplitude * rise * fall * release)
        }
        cursor += count
    }
    return buffer
}

// MARK: - Haptics

private enum CueHaptic {
    case crisp, crispStrong, soft, ramp, buzz, buzzSoft, heavyDouble

    var events: [CHHapticEvent] {
        switch self {
        case .crisp:       return [Self.transient(intensity: 0.6, sharpness: 0.8)]
        case .crispStrong: return [Self.transient(intensity: 1.0, sharpness: 0.75)]
        case .soft:        return [Self.transient(intensity: 0.45, sharpness: 0.35)]
        case .ramp:
            // A swell, not a knock: "go" is a state you enter, and a ramp is still
            // felt through a hand that is already closing on the edge.
            return [CHHapticEvent(eventType: .hapticContinuous, parameters: [
                CHHapticEventParameter(parameterID: .hapticIntensity, value: 0.7),
                CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.4),
            ], relativeTime: 0, duration: Self.rampSeconds)]
        case .buzz, .buzzSoft:
            let heavy = self == .buzz
            return [CHHapticEvent(eventType: .hapticContinuous, parameters: [
                CHHapticEventParameter(parameterID: .hapticIntensity, value: heavy ? 0.9 : 0.55),
                CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.15),
            ], relativeTime: 0, duration: heavy ? 0.28 : 0.14)]
        case .heavyDouble:
            return [Self.transient(intensity: 1.0, sharpness: 0.6, at: 0),
                    Self.transient(intensity: 1.0, sharpness: 0.6, at: 0.16)]
        }
    }

    var parameterCurves: [CHHapticParameterCurve] {
        guard self == .ramp else { return [] }
        return [CHHapticParameterCurve(parameterID: .hapticIntensityControl, controlPoints: [
            .init(relativeTime: 0, value: 0.2),
            .init(relativeTime: Self.rampSeconds * 0.7, value: 0.8),
            .init(relativeTime: Self.rampSeconds, value: 1.0),
        ], relativeTime: 0)]
    }

    private static let rampSeconds: TimeInterval = 0.32

    private static func transient(intensity: Float, sharpness: Float,
                                  at time: TimeInterval = 0) -> CHHapticEvent {
        CHHapticEvent(eventType: .hapticTransient, parameters: [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: intensity),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness),
        ], relativeTime: time)
    }
}
