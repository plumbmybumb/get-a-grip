// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

#if !os(watchOS)
import AVFoundation
import CoreHaptics
import Foundation

extension CuePlayer: RunnerCuePlaying {
    func setDiagnosticSink(_ sink: @escaping (String) -> Void) { onDiagnostic = sink }
}

/// Plays what `SessionRunner` asks for — and nothing else.
///
/// Everything here is pure OUTPUT: no method throws, no failure propagates, and a phone
/// with a dead audio session or no haptic hardware still runs the whole session in
/// silence. A cue that cannot be played is not a reason for a set to stop.
///
/// The tones are SYNTHESISED, not shipped: built once at `begin()` for ~2 ms and ~200 KB,
/// with no bundle asset to go missing. They share one A-major frame so the cues read as
/// a family; the alarm does not, which is what makes it read as wrong.
@MainActor
final class CuePlayer {

    // MARK: Audio

    /// Mono Float32. The mixer converts to whatever the route actually wants, so the
    /// buffers never have to be rebuilt when AirPods appear.
    private let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)
    /// `var`, because a media-services reset invalidates every AVAudio object the app
    /// holds: the only recovery is new ones — see `handleMediaServicesReset`.
    private var engine = AVAudioEngine()
    private var player = AVAudioPlayerNode()
    private var tones: [CueTone: AVAudioPCMBuffer] = [:]
    private var audioReady = false
    /// An activation is in flight on `CueAudioSession`'s queue; a second one would only
    /// queue behind.
    private var audioStarting = false
    /// Which activation is the CURRENT one. Bumped whenever an in-flight activation stops
    /// being wanted (a media-services reset, `end()`), so its completion is ignored
    /// instead of clearing `audioStarting` for a newer one or starting an unwanted engine.
    private var audioGeneration = 0
    /// When audio was last (re)started, so a cue arriving to a dead engine can retry
    /// without turning every tick of a rest into an audio-session round trip.
    private var lastAudioAttempt: TimeInterval = -.infinity
    private static let audioRetryInterval: TimeInterval = 2

    // MARK: Haptics

    /// False on every Simulator; everything haptic short-circuits on it.
    private let hapticsSupported = CHHapticEngine.capabilitiesForHardware().supportsHaptics
    private var hapticEngine: CHHapticEngine?
    private var hapticEngineRunning = false

    // MARK: Lifecycle

    private var isRunning = false
    private var observers: [NSObjectProtocol] = []
    /// Separate from `observers`: it is bound to ONE engine object, and a media-services
    /// reset replaces the engine it was registered against.
    private var engineObserver: NSObjectProtocol?

    /// Where audio evidence goes — the session's diagnostics ring, via
    /// `setDiagnosticSink`. Optional: output never depends on being observed.
    private var onDiagnostic: ((String) -> Void)?

    init() {}

    /// Activate the audio session and prepare both engines, ONCE per session: starting
    /// an `AVAudioEngine` costs tens of milliseconds. The audio-session half runs OFF the
    /// main thread (`startAudio`) — `setCategory`/`setActive` are synchronous round trips
    /// to the audio server, called while the runner's cover is presenting.
    func begin() {
        guard !isRunning else { return }
        isRunning = true
        buildTones()
        // The graph is built AFTER the session is configured (in `startAudio`): preparing
        // an engine under the default `.soloAmbient` category creates it non-mixable.
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
        // An activation still in flight belongs to the session that just ended.
        audioGeneration += 1
        audioStarting = false
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
            // Only the last three seconds speak: a beep per second through a long rest is
            // unbearable. The final one is higher so "go" is anticipated.
            guard (1...3).contains(seconds) else { return }
            let last = seconds == 1
            sound(last ? .tickFinal : .tick)
            haptic(last ? .crispStrong : .crisp)

        case .armed:
            sound(.go)
            haptic(.ramp)

        case .repStarted:
            // Small: "go" already sounded; this only confirms the clock caught the pull.
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

    /// A short contrasting interval, one announcement per transition. No sound
    /// preference gates it: like every cue it mixes without ducking and fails silent.
    func gripChanged() { sound(.gripChange) }

    // MARK: - Audio session and graph

    /// Activate the session, then start the engine — the first half off the main thread,
    /// through `CueAudioSession`'s serial queue.
    private func startAudio() {
        guard isRunning, !audioStarting else { return }
        audioStarting = true
        audioGeneration += 1
        let generation = audioGeneration
        lastAudioAttempt = ProcessInfo.processInfo.systemUptime
        Task { @MainActor [weak self] in
            let report = await CueAudioSession.activate()
            // A stale activation says nothing about the current one: the flag and the
            // engine belong to whichever activation was started last.
            guard let self, generation == self.audioGeneration else { return }
            self.audioStarting = false
            self.onDiagnostic?(report.summary)
            // Ended while activating: `end()` queued the deactivation behind us.
            guard self.isRunning else { return }
            guard report.active else {
                // Haptics-only until a later cue retries. The session keeps running.
                self.audioReady = false
                return
            }
            self.buildGraph()
            self.startEngine()
            if report.otherAudioBefore { self.checkOtherAudioSurvived() }
        }
    }

    /// A podcast that was playing when the session started should STILL be playing a
    /// moment later. Apps pause on their own schedule, so this looks once, after the
    /// engine has had time to make any noise it was going to make.
    private func checkOtherAudioSurvived() {
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self, self.isRunning else { return }
            let still = await CueAudioSession.otherAudioPlaying()
            self.onDiagnostic?(still
                ? "other audio still playing 2 s after start"
                : "other audio STOPPED within 2 s of start")
        }
    }

    private func startEngine() {
        do {
            if !engine.isRunning { try engine.start() }
        } catch {
            audioReady = false
            return
        }
        if !player.isPlaying { player.play() }
        audioReady = true
    }

    private func stopAudio() {
        player.stop()
        engine.stop()
        audioReady = false
        CueAudioSession.deactivate()
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
        guard isRunning else { return }
        guard audioReady, engine.isRunning else {
            // **A dead engine is retried.** Interruptions often end without
            // `.shouldResume` (Siri, an alarm), so one phone call could otherwise silence
            // a workout for good. This cue is lost; the next one has a live engine.
            if !audioStarting,
               ProcessInfo.processInfo.systemUptime - lastAudioAttempt >= Self.audioRetryInterval {
                startAudio()
            }
            return
        }
        guard let buffer = tones[tone] else { return }
        // Queued, NOT `.interrupts`: the runner returns cues in batches (rep-end, set-end,
        // session-end together) and interrupting would leave only the last audible.
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

        // The audio server restarted: every AVAudio object is now a husk, and the workout
        // would otherwise run silent.
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.handleMediaServicesReset() }
        })

        installEngineObserver()
    }

    private func installEngineObserver() {
        guard engineObserver == nil else { return }
        engineObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.handleConfigurationChange() }
        }
    }

    private func removeObservers() {
        for token in observers { NotificationCenter.default.removeObserver(token) }
        observers.removeAll()
        if let engineObserver { NotificationCenter.default.removeObserver(engineObserver) }
        engineObserver = nil
    }

    private func handleInterruption(typeRaw: UInt?, optionsRaw: UInt?) {
        guard let typeRaw, let type = AVAudioSession.InterruptionType(rawValue: typeRaw) else { return }
        onDiagnostic?(type == .began ? "interrupted by another app" : "interruption ended")
        switch type {
        case .began:
            // Tear the graph down so a half-live engine cannot throw on the next cue. The
            // runner is untouched — a phone call must not cost the rep under way.
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

    /// AirPods connecting mid-set stops the engine and drops the player's connection;
    /// without the rebuild the rest of the session is silent.
    private func handleConfigurationChange() {
        guard isRunning else { return }
        buildGraph()
        startAudio()
    }

    /// Apple's documented recovery: throw every audio object away and build new ones. The
    /// tones are plain PCM buffers and survive; the engine, the player and the session
    /// configuration do not. The haptic engine has its own `resetHandler`.
    private func handleMediaServicesReset() {
        guard isRunning else { return }
        if let engineObserver { NotificationCenter.default.removeObserver(engineObserver) }
        engineObserver = nil
        engine = AVAudioEngine()
        player = AVAudioPlayerNode()
        audioReady = false
        // Retire any activation in flight to the dead server (see `audioGeneration`). No
        // `buildGraph()` here — `startAudio` builds it once configured, as in `begin()`.
        audioGeneration += 1
        audioStarting = false
        installEngineObserver()
        startAudio()
    }

    // MARK: - Haptics

    private func prepareHaptics() {
        // No haptic hardware (every Simulator) is a silent no-op.
        guard hapticsSupported, hapticEngine == nil else { return }
        hapticEngine = try? CHHapticEngine()
        // LOAD-BEARING for "never interrupt what the user is listening to".
        // `CHHapticEngine` by default participates in the audio session and can take it
        // over — pausing somebody's video without a single audio API call. Our tones go
        // through AVAudioEngine, so this engine plays haptics ONLY.
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
        guard hapticsSupported, let hapticEngine else { return }
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
            // A swell, not a knock: still felt through a hand closing on the edge.
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
#endif
