// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - Buttons

/// A glass pill button used for floating chrome.
///
/// The glass lives INSIDE the label, so press feedback scales the whole pill and the full
/// capsule is the hit target. `.glassEffect` wrapped AROUND a container holding the
/// Button swallows its touches.
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
    /// **Off for row-sized cards.** A card that scales on press drags its material backdrop
    /// out from under it, so those rows used `.plain` and had NO touch-down feedback.
    /// Opacity alone keeps the acknowledgement (audit, 2026-08-11).
    var scales: Bool = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion && scales ? 0.94 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            // Acknowledge the press in the first frame; only release settles. An 80 ms
            // ramp spends several frames catching up with a short tap.
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

    /// EXPLICIT: `Accent.graphite` inverts with the scheme (near-WHITE in dark) and the
    /// system's label choice does not follow, so `.glassProminent` rendered white on white —
    /// the ritual's one button, unreadable in dark mode. The label is always the opposite ink.
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
            // No HARD height: it would clip "Start second session" at accessibility
            // sizes instead of growing. Short titles still measure exactly
            // Metrics.buttonHeight.
            .actionLabelLayout(minHeight: Metrics.buttonHeight, fullWidth: true)
        }
        .buttonStyle(.glassProminent)
        .tint(tint)
    }
}

/// **Solid twins of the two glass buttons, for the one subtree the system lifts.**
///
/// Liquid Glass renders in its own compositing pass and ignores the hide the context-menu
/// lift performs on its source: every in-process layer vanished on cue while the glass
/// button floated alone over the transition (Nuri's device, 2026-08-18; not reproducible
/// in the Simulator). So a view carrying `.contextMenu` is built from in-process layers
/// only: same label rules and metrics, plain fills. Over the static field the twins are
/// near indistinguishable from the glass at rest.
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
