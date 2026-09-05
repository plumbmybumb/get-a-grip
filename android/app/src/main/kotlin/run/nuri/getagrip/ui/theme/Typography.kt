// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Android's system face, with an instrument's tighter display rhythm. Body type remains
// comfortably spaced; no downloaded font, font swap or SF imitation.
private fun type(size: Int, leading: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = leading.sp,
        fontWeight = weight, letterSpacing = tracking.sp)

val GripTypography = Typography(
    displayLarge = type(57, 60, FontWeight.Light, -1.4f),
    displayMedium = type(45, 49, FontWeight.Light, -1f),
    displaySmall = type(36, 40, FontWeight.SemiBold, -.9f),
    headlineLarge = type(32, 37, FontWeight.SemiBold, -.7f),
    headlineMedium = type(28, 33, FontWeight.SemiBold, -.5f),
    headlineSmall = type(24, 29, FontWeight.SemiBold, -.4f),
    titleLarge = type(22, 27, FontWeight.Medium, -.3f),
    titleMedium = type(17, 23, FontWeight.SemiBold, -.15f),
    titleSmall = type(15, 21, FontWeight.Medium),
    bodyLarge = type(16, 24),
    bodyMedium = type(14, 21),
    bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.SemiBold, .1f),
    labelMedium = type(12, 16, FontWeight.Medium, .2f),
    labelSmall = type(11, 16, FontWeight.SemiBold, .5f),
)
