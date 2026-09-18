// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - Buttons

/// A glass pill button used for floating chrome.
///
/// The glass lives INSIDE the button label so the press feedback scales the whole
/// pill and the full capsule is the hit target. Wrapping `.glassEffect` AROUND a
/// container that holds the Button instead swallows its touches.
struct GlassPillButton<Label: View>: View {
    var tint: GlassTint = .neutral
    let action: () -> Void
    @ViewBuilder var label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .foregroundStyle(tint.text)
                .actionLabelLayout()
                .glassEffect(tint.style, in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}

/// Instant touch-down acknowledgment: sheets take a beat to present, and without
/// immediate visual response the tap feels dropped.
struct PressFeedbackButtonStyle: ButtonStyle {
    /// **Off for row-sized cards.** A card that scales on press drags its own material
    /// backdrop out from under it — which is why those rows used `.plain` and so had NO
    /// touch-down feedback at all. Opacity alone solves the backdrop problem without
    /// throwing away the acknowledgement (audit, 2026-08-11).
    var scales: Bool = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion && scales ? 0.94 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            // Acknowledge the press in the first rendered frame. Only release settles;
            // an 80 ms ramp spends several frames catching up with a short tap.
            .animation(configuration.isPressed
                        ? nil
                        : Motion.state(reduceMotion),
                       value: configuration.isPressed)
    }
}

/// Filled primary action: full-width prominent Liquid Glass, tinted.
struct PrimaryGlassButton: View {
    var title: String
    var systemImage: String? = nil
    var tint: Color = Accent.graphite
    var action: () -> Void

    @ScaledMetric(relativeTo: .headline) private var textSize: CGFloat = 19

    /// EXPLICIT, because the tint inverts with the colour scheme and the system's own
    /// label choice does not follow it. `Accent.graphite` is near-black in light mode
    /// and near-WHITE in dark, so `.glassProminent` rendered a white pill with white
    /// text — the single button the whole ritual hangs off, unreadable in dark mode.
    /// The label is simply the opposite: white on the dark fill, near-black on the light one.
    private var labelColor: Color {
        .adaptive(Color(hex: "FFFFFF"), Color(hex: "1B1F25"))
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: textSize, weight: .semibold))
            .foregroundStyle(labelColor)
            // A HARD height clips the label instead of the button growing: "Start
            // second session" runs past one line at accessibility sizes, and the one
            // control the whole ritual hangs off must never render as a cut-off word.
            // Short titles still measure exactly Metrics.buttonHeight.
            .actionLabelLayout(minHeight: Metrics.buttonHeight, fullWidth: true)
        }
        .buttonStyle(.glassProminent)
        .tint(tint)
    }
}

/// **Solid twins of the two glass buttons, for the one subtree the system lifts.**
///
/// Liquid Glass renders in its own compositing pass and does not honor the hide the
/// context-menu lift performs on its source: hold the routine card down and every
/// in-process layer vanished on cue while the glass button stayed floating alone over
/// the transition (Nuri's device, 2026-08-18) — the sloppiest thing an interaction can
/// look like, and unreproducible in the Simulator, which composites glass differently.
/// So a view that carries `.contextMenu` must be built from NOTHING but in-process
/// layers: same label rules, same metrics, plain fills. Over the static slate field
/// the glass buttons read as their tint anyway; side by side the twins are near
/// indistinguishable at rest and differ only in the refraction nobody sees on a card
/// they are long-pressing.
struct SolidPrimaryButton: View {
    var title: String
    var systemImage: String? = nil
    var tint: Color = Accent.graphite
    var action: () -> Void

    @ScaledMetric(relativeTo: .headline) private var textSize: CGFloat = 19

    /// Same rule as `PrimaryGlassButton`: the tint inverts with the scheme, so the
    /// label is explicitly the opposite ink.
    private var labelColor: Color {
        .adaptive(Color(hex: "FFFFFF"), Color(hex: "1B1F25"))
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: textSize, weight: .semibold))
            .foregroundStyle(labelColor)
            .actionLabelLayout(minHeight: Metrics.buttonHeight, fullWidth: true)
            .background(tint, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}

/// The secondary twin: `SecondaryGlassButton`'s geometry on a soft solid fill.
struct SolidSecondaryButton: View {
    var title: String
    var systemImage: String? = nil
    var action: () -> Void

    @ScaledMetric(relativeTo: .headline) private var textSize: CGFloat = 17

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: textSize, weight: .semibold))
            .foregroundStyle(Ink.primary)
            .actionLabelLayout(minHeight: Metrics.fieldHeight)
            .background(Color.adaptive(Color(hex: "FFFFFF"), Color(hex: "343A44")),
                        in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}

/// Clear secondary action: a glass capsule that hugs its label.
struct SecondaryGlassButton: View {
    var title: String
    var systemImage: String? = nil
    var tint: Color? = nil
    var action: () -> Void

    @ScaledMetric(relativeTo: .headline) private var textSize: CGFloat = 17

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: textSize, weight: .semibold))
            .actionLabelLayout(minHeight: Metrics.fieldHeight)
        }
        .buttonStyle(.glass)
        .tint(tint)
    }
}
