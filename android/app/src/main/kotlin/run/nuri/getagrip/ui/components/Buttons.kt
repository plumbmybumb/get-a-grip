// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// **Press-down feedback is INSTANT; only the release springs.**
///
/// The iOS `PressFeedbackButtonStyle` animates asymmetrically and the reason is measured,
/// not aesthetic: with a symmetric 0.25 s spring the dip was still ramping when most taps
/// ended, so every button in the app read as slightly laggy — not because anything was
/// slow, but because the acknowledgement was.
///
/// `scales = false` for row-sized cards: a card that scales on press drags its own
/// material backdrop out from under it, so those rows get the opacity dip alone.
///
/// TRANSLATION NOTE: SwiftUI expresses this as a `ButtonStyle` receiving
/// `configuration.isPressed`; Compose has no such hook, so the same asymmetry rides a
/// Modifier fed by the button's own `InteractionSource`. The release curve is
/// `Motion.state`: a tap releases without drag momentum, so the return has no overshoot.
/// Animated values are read in the graphics layer, never during composition.
@Composable
fun Modifier.pressFeedback(
    interactionSource: InteractionSource,
    scales: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val scale = animateFloatAsState(
        targetValue = if (pressed && scales && !reduceMotion) PRESS_SCALE else 1f,
        animationSpec = if (pressed) {
            // Acknowledge the press without spending several frames ramping into it.
            snap()
        } else {
            Motion.state(reduceMotion)
        },
        label = "pressScale",
    )
    // The dip in opacity is NOT conditional on reduce motion: it is the acknowledgement
    // itself, and someone who asked for less movement still has to see the tap register.
    val dim = animateFloatAsState(
        targetValue = if (pressed) PRESS_ALPHA else 1f,
        animationSpec = if (pressed) {
            snap()
        } else {
            Motion.state(reduceMotion)
        },
        label = "pressAlpha",
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        alpha = dim.value
    }
}

private const val PRESS_SCALE = 0.975f
private const val PRESS_ALPHA = 0.92f

/// The filled primary action.
///
/// **The label ink is EXPLICIT and it has to be.** `palette.graphite` inverts with the
/// colour scheme — near-black in light, near-WHITE in dark — and on iOS the system's own
/// label choice did not follow it, which rendered the one control the whole ritual hangs
/// off as white-on-white in dark mode. `graphiteInverse` is that opposite ink, and it is
/// right for the tinted variants too (white on bleu in light, near-black on the lighter
/// dark-mode bleu), exactly as the iOS button's adaptive pair is.
@Composable
fun PrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = LocalGripPalette.current.graphite,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = tint,
            contentColor = palette.graphiteInverse,
            disabledContainerColor = tint.copy(alpha = 0.35f),
            disabledContentColor = palette.graphiteInverse.copy(alpha = 0.6f),
        ),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = modifier
            .fillMaxWidth()
            // A MINIMUM, not a hard height: a long title at a large text size must grow
            // the button rather than be clipped. Short titles still measure exactly
            // `Metrics.buttonHeight`.
            .heightIn(min = Metrics.buttonHeight)
            .pressFeedback(interactionSource),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/// The clear secondary action: a tonal capsule with a hairline edge, hugging its label.
@Composable
fun SecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentColor: Color = LocalGripPalette.current.inkPrimary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.card,
            contentColor = contentColor,
            disabledContainerColor = palette.card,
            disabledContentColor = palette.inkTertiary,
        ),
        // Tonal surfaces replace glass here, so the card needs an edge to read as a
        // control rather than as a patch of background.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = modifier
            .defaultMinSize(minHeight = Metrics.fieldHeight)
            .heightIn(min = Metrics.fieldHeight)
            .pressFeedback(interactionSource),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/// The app's label voice: the quiet all-caps line that names a block.
///
/// TRANSLATION NOTE: iOS draws it in SF Pro SMALL CAPS. Android's default font carries no
/// small-caps feature, and faking one by mixing two sizes is worse than plain uppercase —
/// so the letterforms differ from iOS by design while the tracking (+0.8) and the tertiary
/// ink, which are what make it read as a label rather than as text, are identical.
@Composable
fun CapsLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalGripPalette.current.inkTertiary,
) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = color,
    )
}
