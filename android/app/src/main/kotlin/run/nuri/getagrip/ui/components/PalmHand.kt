// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import run.nuri.getagrip.ui.theme.LocalGripPalette
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/** The same inset moves the hand and reserves space for the workout beneath it.
 * displayCutout remains available while the status bar is hidden; statusBars does not.
 */
@Composable
fun cameraHandOffset(): Dp {
    val density = LocalDensity.current
    val cutoutBottom = WindowInsets.displayCutout.getTop(density) / density.density
    return PalmGeometry.additionalTopInsetDp(cutoutBottom).dp
}

/// Fingers clear the reported camera cutout, falling back to a centred position without one.
/// Android draws no artificial notch, capsule or plate around it. The same centred position
/// remains useful on phones with an off-centre camera and on screens without a cutout.
/// Pure black preserves the cutout relationship; ghost fingers keep a dark-mode hairline.
@Composable
fun PalmHand(
    grip: GripSpec,
    /// Which hand is pulling. **The whole hand mirrors with it** — see `PalmGeometry.isMirrored`.
    side: Side,
    modifier: Modifier = Modifier,
    /// Dimmed while resting, so the hand says "this is what's COMING" rather than "pull this
    /// now".
    isActive: Boolean = true,
    newGripID: String? = null,
    holdsGripCueForRest: Boolean = false,
    /// The spotlight tour's anchor. Passed IN rather than read from a composition local, so
    /// this drawing still knows nothing about a tour and stays previewable.
    tourAnchor: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val darkTheme = isSystemInDarkTheme()
    val changeColor = LocalGripPalette.current.armed
    val emphasis = rememberGripChangeEmphasis(newGripID, holdsGripCueForRest, reduceMotion)

    /// The hand currently DRAWN, which lags `side` by one beat while the thumb retracts.
    var shown by remember { mutableStateOf(side) }
    /// 0 = thumb fully out, 1 = fully drawn into the palm.
    val retract = remember { Animatable(0f) }

    // THE HAND SWAP: the thumb draws into the palm, the hand mirrors while it is hidden, and
    // it grows back out of the other side. Hiding the mirror inside the retraction is what
    // stops four bars visibly sliding past each other — the fingers are symmetric in
    // outline, so with the thumb gone the flip is invisible and the whole change reads as
    // one gesture.
    LaunchedEffect(side, reduceMotion) {
        if (shown == side) return@LaunchedEffect
        if (reduceMotion) {
            shown = side
            retract.snapTo(0f)
            return@LaunchedEffect
        }
        retract.animateTo(1f, tween(PalmGeometry.RETRACT_MILLIS, easing = EaseIn))
        shown = side
        retract.animateTo(0f, tween(PalmGeometry.EXTEND_MILLIS, easing = EaseOut))
    }

    val mirrored = PalmGeometry.isMirrored(shown)
    // STAGGERED from the thumb side inward, so a grip change ripples across the hand instead
    // of all four bars blinking at once. 45 ms is enough to read as a sequence and short
    // enough that the last finger is not visibly late.
    val onness = (0 until 4).map { slot ->
        val anatomical = PalmGeometry.anatomical(slot, shown)
        val on = grip.fingers.contains(FingerSet.allFingers[anatomical])
        animateFloatAsState(
            targetValue = if (on) 1f else 0f,
            animationSpec = if (reduceMotion) {
                tween(PalmGeometry.REDUCED_MILLIS)
            } else {
                tween(
                    durationMillis = PalmGeometry.FINGER_MILLIS,
                    delayMillis = slot * PalmGeometry.STAGGER_MILLIS,
                )
            },
            label = "finger$slot",
        )
    }
    val thumbOn by animateFloatAsState(
        targetValue = if (grip.fingers.hasThumb) 1f else 0f,
        animationSpec = tween(if (reduceMotion) PalmGeometry.REDUCED_MILLIS else PalmGeometry.FINGER_MILLIS),
        label = "thumb",
    )

    val activeAlpha = if (isActive || newGripID != null) 1f else PalmGeometry.RESTING_ALPHA
    val hairline = if (darkTheme) Color.White.copy(alpha = PalmGeometry.HAIRLINE_ALPHA) else Color.Transparent

    Canvas(
        modifier
            .fillMaxWidth()
            .padding(top = cameraHandOffset())
            .height(PalmGeometry.TOTAL_HEIGHT.dp)
            .then(tourAnchor)
            // Decoration over the status bar: it must never eat a touch, and TalkBack already
            // hears the grip from the runner's own line.
            .clearAndSetSemantics {},
    ) {
        val barWidth = PalmGeometry.BAR_WIDTH.dp.toPx()
        val pitch = PalmGeometry.PITCH.dp.toPx()
        val fingerTop = PalmGeometry.FINGER_TOP.dp.toPx()
        val strokeWidth = 1.dp.toPx()

        val originX = (size.width - PalmGeometry.handWidthDp().dp.toPx()) / 2f

        val ink = lerp(Color.Black, changeColor, emphasis.value)
        // Growth uses the existing 22dp header clearance; no layout or metric moves.
        scale(scale = if (reduceMotion) 1f else 1f + 0.25f * emphasis.value,
            pivot = Offset(size.width / 2f, fingerTop)) {
        for (slot in 0 until 4) {
            val anatomical = PalmGeometry.anatomical(slot, shown)
            val length = PalmGeometry.fingerLengthDp(anatomical).dp.toPx()
            val x = originX + slot * pitch
            val radius = CornerRadius(barWidth / 2f)
            val fill = onness[slot].value
            // ON is solid ink (orange during a change); OFF retains its 0.12 ghost. In DARK mode that
            // ghost is invisible against the field, so it also takes a quiet hairline —
            // which fingers are OFF is half the grip's meaning and has to survive the scheme.
            val alpha = PalmGeometry.OFF_ALPHA + fill * (1f - PalmGeometry.OFF_ALPHA)
            drawRoundRect(
                color = ink.copy(alpha = alpha * activeAlpha),
                topLeft = Offset(x, fingerTop),
                size = Size(barWidth, length),
                cornerRadius = radius,
            )
            if (hairline != Color.Transparent && fill < 1f) {
                drawRoundRect(
                    color = hairline.copy(alpha = hairline.alpha * (1f - fill)),
                    topLeft = Offset(x, fingerTop),
                    size = Size(barWidth, length),
                    cornerRadius = radius,
                    style = Stroke(width = strokeWidth),
                )
            }
        }

        if (thumbOn > 0f) {
            drawThumb(
                handLeft = originX,
                handWidth = PalmGeometry.handWidthDp().dp.toPx(),
                mirrored = mirrored,
                extension = (1f - retract.value) * thumbOn,
                alpha = activeAlpha,
                ink = ink,
                hairline = hairline,
                strokeWidth = strokeWidth,
            )
        }
        }
    }
}

/// The thumb: a bar of the same thickness as a finger, hanging off the palm's side at 26°.
///
/// **Sized against a FINGER rather than as a stub** — a thumb is the thickest digit on a
/// hand, so a thin tab beside four fat bars reads as a mistake. **Shallow, not diagonal**:
/// a steeper thumb read as a detached pill lying at an angle. DETACHED, with the same 6 dp
/// clearance the fingers have — a thumb welded to the palm is the one part of a hand nobody
/// draws that way.
private fun DrawScope.drawThumb(
    handLeft: Float,
    handWidth: Float,
    mirrored: Boolean,
    extension: Float,
    alpha: Float,
    ink: Color,
    hairline: Color,
    strokeWidth: Float,
) {
    val length = PalmGeometry.THUMB_LENGTH.dp.toPx() * extension.coerceIn(0f, 1f)
    if (length <= 0.5f) return
    val thickness = PalmGeometry.THUMB_THICKNESS.dp.toPx()
    val pivotX = if (mirrored) handLeft + handWidth + 6.dp.toPx() else handLeft - 6.dp.toPx()
    val pivotY = PalmGeometry.THUMB_PIVOT_Y.dp.toPx()
    val degrees = PalmGeometry.thumbAngleDegrees(mirrored)

    // Anchored at the palm end so the rotation swings the TIP away and the root stays where
    // the geometry above put it; scaling along its own length toward that root is what makes
    // it disappear at the knuckle on a hand swap rather than shrinking to a dot.
    rotate(degrees = degrees, pivot = Offset(pivotX, pivotY)) {
        val left = if (mirrored) pivotX else pivotX - length
        drawRoundRect(
            color = ink.copy(alpha = alpha),
            topLeft = Offset(left, pivotY - thickness / 2f),
            size = Size(length, thickness),
            cornerRadius = CornerRadius(thickness / 2f),
        )
        if (hairline != Color.Transparent) {
            drawRoundRect(
                color = hairline,
                topLeft = Offset(left, pivotY - thickness / 2f),
                size = Size(length, thickness),
                cornerRadius = CornerRadius(thickness / 2f),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

/// The hand's arithmetic, with no Canvas in it — so mirroring, the finger positions and the
/// thumb's angle can be asserted in a JVM test.
///
/// **All dp, all measured against `IslandHand`** and deliberately kept in step with it: the
/// same 22 × 38 capsule, the same index-to-little length ratios, the same full-capsule
/// radius, the same 6 dp clearance, the same 26° thumb.
object PalmGeometry {

    const val BAR_WIDTH = 22f
    const val BAR_LENGTH = 38f

    /// 22 + 8, the island hand's own gap. This is the RUNNER's hand — a drawing nobody
    /// touches — so it keeps the tight pitch. `GripIslandPanel`'s interactive twin uses 44,
    /// because a control needs a legal tap target and a drawing does not.
    const val BAR_GAP = 8f
    const val PITCH = BAR_WIDTH + BAR_GAP

    /// Reserve a small top-centre camera region, with the fingers starting at 36dp.
    /// This is a shared placement, not a claim about one phone's exact cutout geometry.
    const val FINGER_TOP = 36f

    const val CAMERA_GAP = 6f

    fun additionalTopInsetDp(cutoutBottomDp: Float): Float =
        if (cutoutBottomDp <= 0f) 0f else maxOf(0f, cutoutBottomDp + CAMERA_GAP - FINGER_TOP)

    const val THUMB_LENGTH = 38f
    const val THUMB_THICKNESS = 22f
    const val THUMB_ANGLE = 26f
    const val THUMB_PIVOT_Y = FINGER_TOP + 4f

    /// A hand's proportions, INDEX → LITTLE. Mirrored along with the fingers, so the middle
    /// finger is longest whichever way round the hand is drawn.
    val LENGTH_FACTOR = listOf(0.86f, 1.0f, 0.94f, 0.80f)

    /// How far down the screen the longest finger reaches — the clearance the runner's
    /// content has to start below. 36 + 38 = 74.
    const val TOTAL_HEIGHT = FINGER_TOP + BAR_LENGTH

    const val OFF_ALPHA = 0.12f
    const val RESTING_ALPHA = 0.4f
    const val HAIRLINE_ALPHA = 0.18f

    const val RETRACT_MILLIS = 160
    const val EXTEND_MILLIS = 240
    const val FINGER_MILLIS = 220
    const val STAGGER_MILLIS = 45
    const val REDUCED_MILLIS = 200

    fun handWidthDp(): Float = BAR_WIDTH * 4 + BAR_GAP * 3

    /// **Facing a LEFT palm, the thumb is on the right — and so the index finger is the
    /// RIGHTMOST bar, not the leftmost.** Moving only the thumb was the bug: with the thumb
    /// switched but the fingers left alone, a front-2 grip rendered on the little side and
    /// read as back-2 (Nuri spotted it, 2026-08-09). The whole hand mirrors.
    ///
    /// `both` mirrors too, matching iOS: a two-handed pull is drawn as a left palm rather
    /// than left blank.
    fun isMirrored(side: Side): Boolean = side != Side.right

    /// `slot` is the position ON SCREEN, left to right. Which finger lives there depends on
    /// the hand.
    fun anatomical(slot: Int, side: Side): Int = if (isMirrored(side)) 3 - slot else slot

    fun fingerLengthDp(anatomical: Int): Float = BAR_LENGTH * LENGTH_FACTOR[anatomical]

    /// Positive swings the tip clockwise — away from a palm whose thumb is on the RIGHT,
    /// which is the mirrored (left-hand) case.
    fun thumbAngleDegrees(mirrored: Boolean): Float = if (mirrored) THUMB_ANGLE else -THUMB_ANGLE

    fun thumbAngleDegrees(side: Side): Float = thumbAngleDegrees(isMirrored(side))

    /// Left edge of the bar in `slot`, given the width it is centred in.
    fun fingerXDp(slot: Int, containerWidthDp: Float): Float =
        (containerWidthDp - handWidthDp()) / 2f + slot * PITCH
}

@Preview(name = "Palm · left half crimp", showBackground = true, heightDp = 140)
@Composable
private fun PalmHandLeftPreview() {
    GetAGripTheme(darkTheme = false) {
        PalmHand(grip = GripSpec(20, FingerSet.frontTwo, GripPosition.halfCrimp), side = Side.left)
    }
}

@Preview(name = "Palm · right pinch · dark", showBackground = true, heightDp = 140)
@Composable
private fun PalmHandRightDarkPreview() {
    GetAGripTheme(darkTheme = true) {
        PalmHand(grip = GripSpec(20, FingerSet.four, GripPosition.pinch), side = Side.right)
    }
}
