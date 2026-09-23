// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// What the WHOLE watch face is painted, and why that is the face's main instrument.
///
/// The face is glanced at, not read, and off the raise pose watchOS dims it and redraws
/// once a second (no app can prevent that). A colour survives both where a rolling digit
/// does not, so the state ladder is the BACKGROUND (Nuri, 2026-09-19).
///
/// Blue: pull. Green: the clock is running. Red: re-grip. Gray: rest (and pause, where the
/// word differs). Orange: the next pull is a different grip. Amber: "less" — EASE OFF and
/// LET GO are one instruction, the opposite of RE-GRIP's, so they cannot share red.
///
/// Structurally the phone's `tint(_:)` ladder (link down first, then phase), except ARMED
/// is blue not amber — on a wrist the glance is "pull now" versus "counting" — and
/// `.idle` is never red, since CONNECTING in a session's first second is not an alarm.
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
/// Two shades per mood: Apple's Always On guidance is DIMMED colours for large areas, so
/// the lit fill is for the raised wrist and the dimmed one for reduced luminance, where
/// every ink goes white. Both shades of every mood clear 4.5:1 against their ink.
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
        // Shared hues come from `AccentHex`, not a second copy: blue and red mean the
        // same thing on both screens and must not drift apart.
        case (.pull, false):                          return .init(fillHex: AccentHex.bleu, inkIsWhite: true)
        case (.pull, true):                           return .init(fillHex: "123F70", inkIsWhite: true)
        case (.holding, false):                       return .init(fillHex: "2EBF5C", inkIsWhite: false)
        case (.holding, true):                        return .init(fillHex: "135E2E", inkIsWhite: true)
        case (.regrip, false), (.linkDown, false):    return .init(fillHex: AccentHex.alarm, inkIsWhite: true)
        case (.regrip, true), (.linkDown, true):      return .init(fillHex: "711717", inkIsWhite: true)
        // Its own literal, not `StatusTint.armed` (FF9800): the phone's amber is chrome
        // on slate, this floods the face and was tuned with its dimmed twin. Not a stale copy.
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
/// Clocks roll and measurements snap — except under reduced luminance, where a once-a-second
/// redraw catches the roll mid-flight as a smear (Nuri, 2026-09-19), and in Low Power Mode,
/// where the refresh rate is capped. In both the number cuts.
enum NumeralRoll {
    static func rolls(luminanceReduced: Bool, lowPower: Bool) -> Bool {
        !luminanceReduced && !lowPower
    }
}
