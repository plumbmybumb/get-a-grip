// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// What the WHOLE watch face is painted, and why that is the face's main instrument.
///
/// On the wrist the face is glanced at, not read — mid-hang it faces the ceiling, and
/// between pulls it is a quarter-second look from a bench. With the wrist out of the
/// raise pose watchOS also dims it and redraws it once a second, and no app can hold full
/// brightness (Apple's own Workout app dims the same way; there is no API for it). A
/// colour survives all of that where a rolling digit does not: one redraw a second is
/// enough to show that the face went green, and a fill reads at arm's length through
/// reduced luminance. So the state ladder is the BACKGROUND (Nuri, 2026-09-19), the way
/// the phone runner's keyline changes colour, only over the entire screen.
///
/// The ladder, in Nuri's words: blue when you should pull, green when the clock is
/// running, red when you have to re-grip, gray while you rest, orange when the next pull
/// is a different grip. Two the words did not cover: amber for "less" — EASE OFF above the
/// band and LET GO at the release gate are the same instruction, and both are the
/// opposite of RE-GRIP's, so they cannot share red — and the rest gray for a pause, where
/// the word carries the difference.
///
/// This is the phone's `tint(_:)` ladder in structure (link down first, then the phase),
/// with two deliberate departures: ARMED is blue rather than amber, because on a wrist
/// the useful glance is "pull now" versus "it is counting", and those need two colours;
/// and `.idle` is never red, because CONNECTING on the first second of a session is not
/// an alarm.
enum WatchFaceMood: String, CaseIterable, Sendable, Equatable {
    /// Rest, set break, lead-in, idle, finished: nothing asked of you yet.
    case rest
    /// A pause, which you tapped. Same gray as a rest; the word says which.
    case paused
    /// The rest (or count-in) leading into a DIFFERENT grip — the one thing on a rest
    /// screen that is a change of instruction rather than a countdown.
    case newGrip
    /// Armed: the load is yours to take.
    case pull
    /// The rep's clock is running — above the threshold, or inside the target band.
    case holding
    /// Dropped mid-rep; the clock stopped and the fix is MORE load.
    case regrip
    /// Over the top of the band; the clock stopped and the fix is LESS load.
    case easeOff
    /// The hold is done, the release gate is waiting for you to come off the edge.
    case letGo
    /// The gauge is gone mid-session. Red like `regrip`: attention here.
    case linkDown

    /// - Parameters:
    ///   - linkIsDown: a MEASURED session whose gauge is disconnected or silent. A
    ///     gauge-free session passes false — there is nothing to be connected to.
    ///   - gripChangesNext: `RunnerSnapshot.gripChangesNext`, true only during a rest.
    ///   - newGrip: `RunnerSnapshot.newGripID != nil` — the displayed slot's grip differs
    ///     from the last recorded pull, which is what colours the count-in before it.
    static func resolve(phase: RunnerPhase, isDropped: Bool, isOverTarget: Bool,
                        linkIsDown: Bool, gripChangesNext: Bool, newGrip: Bool) -> WatchFaceMood {
        switch phase {
        // Before the first count-in the gauge is still being reached, and after the last
        // pull nothing depends on it: neither is a link "down".
        case .idle, .finished: return .rest
        default: break
        }
        if linkIsDown { return .linkDown }
        switch phase {
        case .idle, .finished: return .rest
        case .paused: return .paused
        case .leadIn: return newGrip ? .newGrip : .rest
        case .armed: return .pull
        case .working:
            if isDropped { return .regrip }
            if isOverTarget { return .easeOff }
            return .holding
        case .releasing: return .letGo
        case .resting: return gripChangesNext ? .newGrip : .rest
        }
    }
}

/// The face's colours per mood, as hex so the contrast is a TESTED number rather than an
/// eyeballed one (`WatchFaceMoodTests`), and so the watch target only has to turn a
/// string into a `Color`.
///
/// Two shades per mood, and the reason is Apple's: the system sets the Always On
/// brightness from the ratio of lit to dark pixels, and its guidance for large areas of
/// colour in that state is to use DIMMED colours. So the lit fill is for the raised
/// wrist, and the dimmed one — the same hue at a fraction of the light — is what the
/// face paints under reduced luminance, where every ink goes white. Both shades of every
/// mood clear 4.5:1 against their ink.
struct WatchFacePalette: Equatable, Sendable {
    /// `RRGGBB`, no hash.
    let fillHex: String
    /// White ink, or black. Black on the bright fills (green, orange, amber), white on
    /// the deep ones (blue, red, gray) — and always white when dimmed.
    let inkIsWhite: Bool

    static func colours(for mood: WatchFaceMood, dimmed: Bool) -> WatchFacePalette {
        switch (mood, dimmed) {
        case (.rest, false), (.paused, false):        return .init(fillHex: "3A424D", inkIsWhite: true)
        case (.rest, true), (.paused, true):          return .init(fillHex: "22272E", inkIsWhite: true)
        case (.newGrip, false):                       return .init(fillHex: "F26B0A", inkIsWhite: false)
        case (.newGrip, true):                        return .init(fillHex: "8A3D00", inkIsWhite: true)
        // The shared hues come from the engine's `AccentHex`, not from a second copy
        // of the same six characters: blue means "pull" and red means "attention" on
        // both screens, and a face that drifted a shade off the app would be saying so
        // about a signal that is meant to be one.
        case (.pull, false):                          return .init(fillHex: AccentHex.bleu, inkIsWhite: true)
        case (.pull, true):                           return .init(fillHex: "123F70", inkIsWhite: true)
        case (.holding, false):                       return .init(fillHex: "2EBF5C", inkIsWhite: false)
        case (.holding, true):                        return .init(fillHex: "135E2E", inkIsWhite: true)
        case (.regrip, false), (.linkDown, false):    return .init(fillHex: AccentHex.alarm, inkIsWhite: true)
        case (.regrip, true), (.linkDown, true):      return .init(fillHex: "711717", inkIsWhite: true)
        // DELIBERATELY its own literal, not `StatusTint.armed` (FF9800). The phone's
        // amber is CHROME on a slate field; this one floods the entire face, and it was
        // tuned as a pair with the dimmed twin below it. Both clear 4.5:1 on black — the
        // difference is the surface, not the arithmetic, so it is a real decision rather
        // than a stale copy.
        case (.easeOff, false), (.letGo, false):      return .init(fillHex: "FFB300", inkIsWhite: false)
        case (.easeOff, true), (.letGo, true):        return .init(fillHex: "8A6100", inkIsWhite: true)
        }
    }

    /// WCAG relative luminance of an `RRGGBB` string; nil when it is not one. Here rather
    /// than in the test so the palette's own contract can be checked wherever it is read.
    static func relativeLuminance(hex: String) -> Double? {
        guard hex.count == 6, let rgb = UInt32(hex, radix: 16) else { return nil }
        func channel(_ value: UInt32) -> Double {
            let c = Double(value) / 255
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((rgb >> 16) & 0xFF)
             + 0.7152 * channel((rgb >> 8) & 0xFF)
             + 0.0722 * channel(rgb & 0xFF)
    }

    /// Contrast of the ink against the fill, WCAG 2 arithmetic.
    var contrastRatio: Double {
        guard let fill = Self.relativeLuminance(hex: fillHex) else { return 0 }
        let ink: Double = inkIsWhite ? 1 : 0
        return (max(fill, ink) + 0.05) / (min(fill, ink) + 0.05)
    }
}

/// Whether a clock numeral may ROLL — `RollingNumeral`'s slide, never
/// `.contentTransition(.numericText())`, which blurs on the CPU — or must cut straight to
/// the next digit.
///
/// The house rule is that clocks roll and measurements snap. Two states take the roll
/// away from a clock too: reduced luminance, where watchOS redraws once a second and a
/// digit-by-digit roll is caught mid-flight as a smear ("looks sloppy and hurts", Nuri,
/// 2026-09-19); and Low Power Mode, on either device, where the phone caps the refresh
/// rate and the watch has just told you it is rationing. In both the number cuts.
enum NumeralRoll {
    static func rolls(luminanceReduced: Bool, lowPower: Bool) -> Bool {
        !luminanceReduced && !lowPower
    }
}
