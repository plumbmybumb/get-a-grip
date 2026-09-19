// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - Glass styling (app-only)
//
// Ported from Schengen Slice (itself ported from Number Bubble) so the apps read as
// siblings. Chrome (buttons, fields, pills) uses Apple's real Liquid Glass
// (`.glassEffect`, `.glass` / `.glassProminent`) on iOS 26 — subtle and refractive,
// not a custom "shiny" gradient. The background stays a custom content layer so the
// glass has soft, shifting colour to refract. Widget-safe tokens (Ink, StatusTint,
// Metrics, Color helpers) live in Shared/DesignTokens.swift — never redefined here.
//
// Split by concern: `AppBackground.swift` is the slate field the glass refracts,
// this file is the glass itself (tints, Reduce Transparency, field chrome), and
// `Buttons.swift` is every button built on it.

// MARK: - Glass tint tokens

/// Semantic colour for a glass control: the Liquid Glass tint hue (nil = clear) and
/// a foreground colour that stays legible over translucent glass.
struct GlassTint {
    var glass: Color?
    var text: Color

    static let neutral = GlassTint(glass: nil, text: Ink.primary)
    /// The default for chrome: ink, not a colour.
    static let graphite = GlassTint(glass: Accent.graphiteFlat,
                                    text: .adaptive(Color(hex: "23272E"), Color(hex: "EDF1F6")))
    /// Live force / connected device.
    static let bleu = GlassTint(glass: Accent.bleuFlat,
                                text: .adaptive(Color(hex: "10508F"), Color(hex: "9FCEFA")))
    /// Attention: dropout, disconnect, destructive.
    static let alarm = GlassTint(glass: Accent.alarmFlat,
                                 text: .adaptive(Color(hex: "8E1B1B"), Color(hex: "FFA79E")))
    /// Armed / waiting.
    static let armed = GlassTint(glass: Color(hex: "FF9800"),
                                 text: .adaptive(Color(hex: "BF360C"), Color(hex: "FFB37A")))

    /// The Liquid Glass value: tinted + interactive (press illumination/scale).
    var style: Glass {
        if let c = glass { return .regular.tint(c.opacity(0.55)).interactive() }
        return .regular.interactive()
    }
}

// MARK: - Accessible glass

/// Liquid Glass that honours Reduce Transparency.
///
/// With the setting on, glass has to become genuinely opaque, not merely thicker —
/// legibility over a busy backdrop drops below WCAG AA otherwise.
struct AccessibleGlass<S: Shape>: ViewModifier {
    var shape: S
    var tint: Color?
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        if reduceTransparency {
            content
                .background(Color(uiColor: .secondarySystemBackground), in: shape)
                .overlay(shape.stroke(Ink.tertiary.opacity(0.45), lineWidth: 1))
        } else if let tint {
            content.glassEffect(.regular.tint(tint), in: shape)
        } else {
            content.glassEffect(.regular, in: shape)
        }
    }
}

extension View {
    func accessibleGlass(_ tint: Color? = nil, in shape: some Shape) -> some View {
        modifier(AccessibleGlass(shape: shape, tint: tint))
    }
}

extension View {
    /// **A lit rim** — the glass vocabulary on a surface that cannot be glass. The
    /// routine card stays a flat fill (Liquid Glass breaks the context-menu lift — see
    /// `RoutineCard`), so it borrows glass's edge instead: a hairline that catches the
    /// light at the top-leading corner and fades toward the bottom-trailing one. Ink in
    /// light mode, where a white rim on a near-white card would vanish; light in dark.
    func glassRim(in shape: some InsettableShape) -> some View {
        modifier(GlassRim(shape: shape))
    }
}

private struct GlassRim<S: InsettableShape>: ViewModifier {
    var shape: S
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        content.overlay {
            shape.strokeBorder(
                LinearGradient(colors: scheme == .dark
                                   ? [Color.white.opacity(0.32), Color.white.opacity(0.04)]
                                   : [Ink.primary.opacity(0.16), Ink.primary.opacity(0.03)],
                               startPoint: .topLeading, endPoint: .bottomTrailing),
                lineWidth: 1)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

extension View {
    /// Glass capsule field chrome: wrap a field's content (icon + TextField, a value
    /// label, …). Content supplies its own font/ink; this owns padding, the house
    /// field height, and the capsule.
    func glassFieldChrome() -> some View {
        self
            .padding(.horizontal, Metrics.buttonHorizontalPadding)
            .padding(.vertical, Metrics.buttonVerticalPadding)
            .frame(minHeight: Metrics.fieldHeight)
            .glassEffect(.regular, in: .capsule)
    }
}
