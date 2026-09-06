// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit

// Widget-safe design subset — this file is in `Shared/`, so when a workout widget
// eventually exists it compiles into that target too. Nothing here may reference
// `.glassEffect` / `Glass` / `GlassEffectContainer` (unsupported in archived widget
// views). Glass chrome lives in Sources/UI/Style/GlassStyle.swift.
//
// Ported from Schengen Slice so the apps read as siblings: same bones (slate field,
// graphite interactive chrome, semantic ink, small-caps labels, house metrics),
// different signal hue — see `Accent` below.

extension Color {
    init(hex: String) {
        let s = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var rgb: UInt64 = 0
        Scanner(string: s).scanHexInt64(&rgb)
        self.init(
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255
        )
    }

    /// A colour that resolves differently in light vs dark appearance.
    static func adaptive(_ light: Color, _ dark: Color) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(dark) : UIColor(light) })
    }
}

/// Semantic "ink" for text/labels so the whole app flips together in dark mode.
enum Ink {
    static let primary   = Color.adaptive(Color(white: 0.18), Color(white: 0.93))
    static let secondary = Color.adaptive(Color(white: 0.30), Color(white: 0.74))
    static let tertiary  = Color.adaptive(Color(white: 0.45), Color(white: 0.60))
}

extension View {
    /// **Display tracking for the hero numerals.** Apple's rule is that tracking is
    /// size-specific: small text wants it slightly OPEN (which `CapsLabel` already does
    /// at +0.8), and large text wants it TIGHT, because letterforms read further and
    /// further apart as they grow. At 76 pt a default-tracked "21.7" is visibly loose —
    /// the digits float instead of reading as one number.
    ///
    /// Scaled from the point size rather than fixed, so it stays proportional at every
    /// Dynamic Type setting: −0.02 em is the standard display correction.
    func displayTracking(_ size: CGFloat) -> some View {
        tracking(size * -0.02)
    }
}

extension Font {
    /// The app's label voice: small-caps SF Pro — pairs with the hero's thin
    /// numerals for the instrument look. Use with `.tracking(0.8)`.
    static func labelCaps(_ size: CGFloat = 12) -> Font {
        // Maps point sizes onto text styles so every label scales with Dynamic Type.
        let style: Font.TextStyle = size <= 11 ? .caption2 : (size <= 12 ? .caption : .footnote)
        return .system(style, weight: .semibold).smallCaps()
    }
}

/// Slate bones + graphite interactive chrome, with exactly two signal hues:
/// **bleu** for "force is live" and **alarm red** for "attention here".
///
/// The house discipline (learned on Schengen Slice) is that a colour can only mean
/// something if it isn't also the app's baseline. So chrome stays `graphite` — the
/// app's ink, not a colour — which leaves the whole spectrum free for the two things
/// a training app actually has to say at arm's length, mid-hang, without reading a
/// word: *you are pulling hard enough* (bleu), and *something is wrong* (red).
enum Accent {
    /// The INTERACTIVE accent — tab tint, buttons, toggles, selection. Near-black in
    /// light mode, near-white in dark: it reads as ink rather than as a colour.
    static let graphite = Color.adaptive(Color(hex: "2B3038"), Color(hex: "E7EBF1"))
    /// Flat form for Liquid Glass tints (glass handles its own legibility).
    static let graphiteFlat = Color(hex: "2B3038")

    /// Bleu de France — the app's identity hue and the LIVE-FORCE signal: the force
    /// trace while engaged, the ring during a work phase, the "pull now" prompt.
    /// French name, French blue.
    static let bleu = Color.adaptive(Color(hex: "1E6FC4"), Color(hex: "5AA9F0"))
    static let bleuFlat = Color(hex: "318CE7")

    /// Moss — the LIGHT-INTENSITY signal, and green's only appearance in the palette.
    /// It exists for exactly one job: the routine card's rung saying "this one is
    /// easy on the fingers" (≤30 % of max, Nuri's ladder, 2026-08-17). Muted on
    /// purpose so it sits with slate and bleu rather than reading as a traffic
    /// light; anything else that wants green must argue here first.
    static let moss = Color.adaptive(Color(hex: "1F7A4A"), Color(hex: "58BE8B"))

    /// RESERVED for alarm: dropout below threshold mid-rep, destructive actions,
    /// connection lost. Never chrome — the moment red is decorative it stops working.
    static let alarm = Color.adaptive(Color(hex: "C62828"), Color(hex: "F0574C"))
    static let alarmFlat = Color(hex: "C62828")
}

/// Runner state → hue. The one place the whole app agrees on what a phase looks
/// like, so the ring, the trace, the hand prompt and any future widget escalate
/// together instead of drifting apart.
enum StatusTint {
    /// Calm: resting, set break, idle. Steel — present but not shouting.
    static let calm = Color.adaptive(Color(hex: "5F7086"), Color(hex: "9FAEC2"))
    /// Armed and waiting for you to take the load.
    static let armed = Color(hex: "FF9800")
    /// Engaged — force is above threshold and the clock is running.
    static let engaged = Accent.bleu
    /// Dropped below threshold during a rep, or the device disconnected.
    static let alarm = Accent.alarm
}

/// THE MOTION LADDER — every animation in the app comes from here.
///
/// Before this there were six durations (0.22, 0.24, 0.25, 0.26, 0.28, 0.30) doing one
/// job, and the Reduce Motion ternary was copy-pasted at 22 call sites. Nothing chose
/// those numbers; they drifted. Apple's own bar is that every timing value is a
/// deliberate choice you can defend, so there are now three, and each says what it is
/// for (audited against *Designing Fluid Interfaces*, 2026-08-05).
enum Motion {
    /// **The default, and critically damped — `bounce: 0`.** Disclosures, selection,
    /// a row appearing, a page turned by a button: state changes that no gesture threw.
    /// Overshoot on something that merely appeared reads as decoration; Apple reserves
    /// bounce for motion a hand actually put momentum into. 0.30 s is the fast end of
    /// the 0.3–0.4 s response band Apple ships for repositioning.
    static func state(_ reduceMotion: Bool) -> Animation {
        reduceMotion ? reduced : .smooth(duration: 0.30)
    }

    /// **After a flick, a drag release or a throw** — and ONLY then. `.snappy` carries
    /// `bounce: 0.15`, which is the overshoot a moving thing has earned by moving.
    static func momentum(_ reduceMotion: Bool) -> Animation {
        reduceMotion ? reduced : .snappy(duration: 0.30)
    }

    /// A LIVE SENSOR VALUE settling — the kg readouts, the force bar. Deliberately far
    /// shorter than any transition, and deliberately bounce-free: this is tracking a
    /// signal, not transitioning between states, and a number that overshoots the load
    /// on the gauge is lying about a measurement.
    static let live = Animation.smooth(duration: 0.12)

    /// Cumulative measured work travels linearly between radio batches. Match Android:
    /// settle within 200 ms, never predict beyond the last received measurement.
    static let measuredProgress = Animation.linear(duration: 0.20)

    /// A changed grip gets one clear beat of attention, matching Android without bounce.
    static let gripChangeRiseMilliseconds = 180
    static let gripChangeHoldMilliseconds = 1_200
    static let gripChangePulseMilliseconds = 300
    static let gripChangePulse = Animation.easeInOut(duration: 0.30)
    static let gripChangeIn = Animation.easeOut(duration: Double(gripChangeRiseMilliseconds) / 1_000)
    static let gripChangeOut = Animation.easeInOut(duration: 0.30)

    /// Reduce Motion: the same beat, as a cross-fade with no travel and no overshoot —
    /// gentler, not absent. Comprehension still needs *something* to change.
    static let reduced = Animation.easeInOut(duration: 0.2)
}

/// House metrics (shared with Number Bubble / Schengen Slice).
enum Metrics {
    static let radiusSheet: CGFloat = 32
    static let radiusCard: CGFloat = 22
    static let radiusInner: CGFloat = 16
    static let spacing: CGFloat = 22
    /// 20, not 32: the standard iOS leading margin, so content lines up with the
    /// system large title and toolbar rather than sitting inset from them.
    static let hPadding: CGFloat = 20
    static let maxContentWidth: CGFloat = 440
    static let fieldHeight: CGFloat = 54
    static let buttonHeight: CGFloat = 56
}
