// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// The runner's two GLASS SURFACES and the wells inside them — shared because the panel
// and the dock are siblings by design, and a second copy of either value is how two
// siblings stop matching.

/// The glass vocabulary the runner's floating surfaces share.
enum RunnerGlass {
    /// A context-aware shadow: these surfaces float over a MOVING curve, not a plain
    /// field, and Apple's own rule for glass is a heavier shadow over busy content than
    /// over calm. Light enough that the glass still reads as thin.
    static let floatingShadowOpacity = 0.16

    /// Sheet radius, not card radius: a glass surface floating over content is the
    /// system's sheet vocabulary, and beside 56 pt capsules a 22 pt corner reads tight.
    /// ONE shape for the panel and the dock, so the two cannot drift into different
    /// radius families while both claiming to be siblings.
    static var surfaceShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: Metrics.radiusSheet, style: .continuous)
    }
}

extension View {
    /// The shadow both floating surfaces cast — see `RunnerGlass.floatingShadowOpacity`.
    func runnerFloatingShadow() -> some View {
        shadow(color: .black.opacity(RunnerGlass.floatingShadowOpacity), radius: 22, y: 10)
    }
}

extension View {
    /// A runner action's surface: its own glass capsule where it floats on the screen
    /// (the wide layout), a quiet ink well where it sits inside the dock — the same
    /// fill the house uses for an inset well, and never glass on glass.
    @ViewBuilder
    func runnerActionSurface(docked: Bool) -> some View {
        if docked {
            background(Capsule().fill(Ink.primary.opacity(0.05)))
        } else {
            accessibleGlass(nil, in: .capsule)
        }
    }
}

// MARK: - Dock actions, as views

/// A dock action as a VIEW — the same button `RunnerView.dockButton` draws for Pause and
/// the Skips (full width in its slot, glass inside the label when it floats, a quiet ink
/// well inside a dock), so a second screen with a dock is built from the runner's own
/// parts rather than a lookalike. The gauge screen is that second screen.
///
/// `enabled`/`disabledReason` dim AND disable, with the reason surfaced as the
/// accessibility hint; the label is never swapped for it.
///
/// `fillsRowHeight` is for a button INSIDE an `AdaptiveActionRow`, where the row bounds
/// it and it merely matches its neighbours. Standing alone in a `VStack` the same
/// `maxHeight: .infinity` claims every flexible point on the screen — the gauge's Stop
/// button measured half the display that way (2026-09-20) — so it is off by default.
struct DockButton: View {
    var title: String
    var systemImage: String? = nil
    var tint: Color = Ink.primary
    var enabled = true
    var disabledReason: String? = nil
    var docked = true
    var fillsRowHeight = false
    var action: () -> Void

    init(_ title: String, systemImage: String? = nil, tint: Color = Ink.primary,
         enabled: Bool = true, disabledReason: String? = nil, docked: Bool = true,
         fillsRowHeight: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.tint = tint
        self.enabled = enabled
        self.disabledReason = disabledReason
        self.docked = docked
        self.fillsRowHeight = fillsRowHeight
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(.subheadline, weight: .semibold))
            .foregroundStyle(enabled ? tint : Ink.tertiary.opacity(0.5))
            .actionLabelLayout(fullWidth: true, fillsRowHeight: fillsRowHeight)
            .runnerActionSurface(docked: docked)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .disabled(!enabled)
        .accessibilityHint(disabledReason ?? "")
    }
}

/// The dock's one TINTED item — a tinted well with the tint's own legible ink on it,
/// never a solid fill with white on it, so it reads as the dock's primary action
/// without becoming a second kind of surface. Bleu to start a measurement, alarm to
/// stop one.
///
/// `GlassTint`, not a bare `Color`: its `text` is the ink already chosen to stay
/// legible over translucent surfaces. The first cut put the accent itself on a 16 %
/// well and MEASURED 2.5:1 in dark mode and 2.6:1 for bleu in light — under the 3:1
/// floor for a label this size — where this pair clears 4.5:1 (2026-09-20).
struct DockTintedButton: View {
    var title: String
    var systemImage: String? = nil
    var tint: GlassTint
    var enabled = true
    var action: () -> Void

    init(_ title: String, systemImage: String? = nil, tint: GlassTint, enabled: Bool = true,
         action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.tint = tint
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(.subheadline, weight: .semibold))
            .foregroundStyle(enabled ? tint.text : Ink.tertiary.opacity(0.5))
            // Never `fillsRowHeight`: this one stands alone under the row — see
            // `DockButton` for the half-a-screen it grew to otherwise.
            .actionLabelLayout(fullWidth: true)
            .background(Capsule().fill((tint.glass ?? Ink.primary).opacity(enabled ? 0.22 : 0.08)))
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .disabled(!enabled)
    }
}
