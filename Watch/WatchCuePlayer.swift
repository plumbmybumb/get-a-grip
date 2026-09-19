// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import WatchKit

/// The wrist is the CUE CHANNEL. Mid-hang the watch faces the ceiling, so nothing on its
/// screen can be read while it matters; what the wrist can always do is tap. Every cue
/// the engine emits becomes one of the system's own haptics, chosen so the beats a
/// climber has to feel with their eyes shut are the ones that differ most: a swell to
/// start, a knock to stop, a double for done.
///
/// No audio. The watch's speaker is a poor place for a metronome, and the phone's tones
/// already say the same things when the phone is the one running the session.
@MainActor
final class WatchCuePlayer: RunnerCuePlaying {
    private let device = WKInterfaceDevice.current()

    func begin() {}
    func end() {}

    /// Every case spelled out and NO `default:` — a cue added to `RunnerCue` must be a
    /// compile error here rather than a beat the wrist silently misses.
    func play(_ cue: RunnerCue) {
        switch cue {
        case .leadInTick(let seconds), .restTick(let seconds):
            // Only the last three seconds, like the phone: a tap per second through a
            // two-minute rest is a wrist you learn to ignore. The last one rises.
            guard (1...3).contains(seconds) else { return }
            device.play(seconds == 1 ? .directionUp : .click)
        case .armed:
            device.play(.start)
        case .repStarted:
            device.play(.click)
        case .repHalfway:
            device.play(.directionUp)
        case .repEnded(let completed):
            device.play(completed ? .success : .failure)
        case .setCompleted:
            device.play(.success)
        case .sessionCompleted:
            device.play(.notification)
        case .dropoutWarning:
            device.play(.retry)
        case .connectionLost:
            device.play(.failure)
        }
    }

    func gripChanged() { device.play(.directionDown) }
}
