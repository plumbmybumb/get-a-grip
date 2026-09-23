// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

#if !os(watchOS)
import AVFoundation
import Foundation

/// The audio-SESSION half of `CuePlayer`: configuring, activating and deactivating
/// `AVAudioSession`, off the main thread.
///
/// `setCategory` and `setActive` are synchronous round trips to the audio server, so
/// every call goes through ONE serial queue — activation and deactivation alike — which
/// means a session ended and a new one begun in quick succession can never deactivate
/// the new one: the queue runs them in the order they were asked for. The engine and the
/// player stay on the main actor with `CuePlayer`, where every other use of them is.
enum CueAudioSession {
    /// What activation found and did — plain values, so it can cross off the session queue.
    struct ActivationReport: Sendable {
        var active: Bool
        var otherAudioBefore: Bool
        var mixable: Bool
        var error: String?

        var summary: String {
            guard active else { return "session failed to activate (\(error ?? "unknown"))" }
            return "session active, " + (mixable ? "mixable" : "NOT MIXABLE")
                + ", other audio " + (otherAudioBefore ? "playing" : "silent") + " at start"
        }
    }

    /// A `DispatchQueue` is Sendable, so there is nothing to protect.
    private static let queue = DispatchQueue(
        label: "run.nuri.getagrip.cue-audio-session", qos: .userInitiated)

    static func activate() async -> ActivationReport {
        await withCheckedContinuation { continuation in
            queue.async {
                let otherBefore = AVAudioSession.sharedInstance().isOtherAudioPlaying
                do {
                    // `.playback`, NOT `.ambient`: `.ambient` obeys the mute switch, and a
                    // phone face-down and muted on a mat is exactly where this app is used.
                    // `.mixWithOthers` and NOT `.duckOthers`. Ducking would dip whatever the
                    // user is listening to on every cue — roughly one cue every five seconds
                    // across a 21-minute session, which is a video that pulses for the entire
                    // workout. The complaint that motivated this ("Frez pauses my YouTube") is
                    // about background audio being disturbed, and a constant dip is a smaller
                    // version of the same disturbance. Our cues are short, distinct tones and
                    // carry over music at normal volume; the runner screen and the haptics say
                    // the same things anyway.
                    let session = AVAudioSession.sharedInstance()
                    try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
                    try session.setActive(true)
                    continuation.resume(returning: ActivationReport(
                        active: true, otherAudioBefore: otherBefore,
                        mixable: session.categoryOptions.contains(.mixWithOthers)))
                } catch {
                    continuation.resume(returning: ActivationReport(
                        active: false, otherAudioBefore: otherBefore, mixable: false,
                        error: (error as NSError).localizedDescription))
                }
            }
        }
    }

    /// `.notifyOthersOnDeactivation` is what tells other audio apps the session is over
    /// rather than leaving them to notice whenever the system next looks. Queued behind
    /// any activation still in flight.
    static func deactivate() {
        queue.async {
            try? AVAudioSession.sharedInstance().setActive(
                false, options: .notifyOthersOnDeactivation
            )
        }
    }

    static func otherAudioPlaying() async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async {
                continuation.resume(returning: AVAudioSession.sharedInstance().isOtherAudioPlaying)
            }
        }
    }
}
#endif
