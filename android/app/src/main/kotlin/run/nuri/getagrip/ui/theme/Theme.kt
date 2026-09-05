// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/// Material 3 wearing the app's own palette. Dynamic colour is deliberately not offered:
/// the palette IS the identity, and the two signal hues only mean something because
/// graphite stays ink (root CLAUDE.md, "Design system").
@Composable
fun GetAGripTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.graphite, onPrimary = palette.graphiteInverse,
            secondary = palette.calm, onSecondary = palette.graphiteInverse,
            tertiary = palette.bleu, onTertiary = palette.graphiteInverse,
            error = palette.alarm, onError = palette.graphiteInverse,
            background = palette.field, onBackground = palette.inkPrimary,
            surface = palette.field, onSurface = palette.inkPrimary,
            surfaceVariant = palette.card, onSurfaceVariant = palette.inkSecondary,
            surfaceContainerLowest = palette.field, surfaceContainerLow = palette.field,
            surfaceContainer = palette.card, surfaceContainerHigh = palette.card,
            surfaceContainerHighest = palette.card,
            outline = palette.inkTertiary, outlineVariant = palette.inkTertiary.copy(alpha = 0.35f),
        )
    } else {
        lightColorScheme(
            primary = palette.graphite, onPrimary = palette.graphiteInverse,
            secondary = palette.calm, onSecondary = palette.graphiteInverse,
            tertiary = palette.bleu, onTertiary = palette.graphiteInverse,
            error = palette.alarm, onError = palette.graphiteInverse,
            background = palette.field, onBackground = palette.inkPrimary,
            surface = palette.field, onSurface = palette.inkPrimary,
            surfaceVariant = palette.card, onSurfaceVariant = palette.inkSecondary,
            surfaceContainerLowest = palette.card, surfaceContainerLow = palette.field,
            surfaceContainer = palette.card, surfaceContainerHigh = palette.card,
            surfaceContainerHighest = palette.card,
            outline = palette.inkTertiary, outlineVariant = palette.inkTertiary.copy(alpha = 0.35f),
        )
    }
    // **THE CEILING — the twin of iOS's `dynamicTypeSize(...accessibility3)`.**
    //
    // Android's font scale is a continuous slider and OEM skins push it further than AOSP
    // does: the Realme reaches 2.0, and a phone set past that renders this app's own three
    // HARD CAPS (the consistency dot, the runner's hero numeral, the palm) as the only things
    // that still fit while every sentence around them is clipped. Clamped at the ROOT, once,
    // so no screen has to remember: everything below sees at most 2.0 and lays out for it.
    //
    // It is a CEILING, not a size — every scale under it passes through untouched, which is
    // the whole point. The three hard caps stay: they exist because a repeated cell
    // multiplies (`ConsistencyCard`'s 14 dots) or because a fixed-height hero cannot grow at
    // all, and neither of those is answered by a global bound.
    //
    // Nothing else in the density is touched — `density` itself is passed straight through,
    // so a large DISPLAY size (which is a density change, not a font-scale one) still works
    // exactly as the platform intends.
    val density = LocalDensity.current
    val clamped = remember(density) {
        if (density.fontScale <= MAX_FONT_SCALE) {
            density
        } else {
            Density(density.density, MAX_FONT_SCALE)
        }
    }
    CompositionLocalProvider(
        LocalGripPalette provides palette,
        LocalDensity provides clamped,
    ) {
        MaterialTheme(colorScheme = scheme, typography = GripTypography) {
            MotionPreferences { MineralBackground(content) }
        }
    }
}

/// The app-wide font-scale ceiling. 2.0 is where AOSP's own accessibility slider tops out and
/// what the pinned phone reaches; iOS's `.accessibility3` is the same idea one notch below its
/// own maximum. Documented in `android/CLAUDE.md`.
const val MAX_FONT_SCALE = 2.0f
