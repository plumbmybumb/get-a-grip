// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// Four tappable bars — index to little — saying which fingers are on the edge, plus the
/// thumb's horizontal bar underneath.
///
/// It settles the app's worst ambiguity: "front 2" and "middle 2" are one word apart and two
/// grips, and a picture settles what a label flips a coin on. It composes any combination,
/// and under the no-emoji rule doubles as the app's iconography.
///
/// **ONE GLYPH, ONE RULE: full capsule at 22 : 38**, as `FingerGlyph` and `PalmHand` draw it —
/// the bar you tap while building must be the one hanging over the runner (Nuri, 2026-08-09).
@Composable
fun FingerPips(
    fingers: FingerSet,
    position: GripPosition,
    modifier: Modifier = Modifier,
    /// Drops the name line where the surface above already carries the grip name.
    showsName: Boolean = true,
    onChange: (FingerSet) -> Unit,
) {
    val palette = LocalGripPalette.current
    val haptics = LocalHapticFeedback.current

    // CLAMPED, the `ConsistencyCard` trap: uncapped, four bars plus the thumb want a whole
    // phone's width at the largest accessibility scale.
    val scale = LocalDensity.current.fontScale.coerceAtMost(1.4f)
    val width: Dp = (if (showsName) 34.dp else 28.dp) * scale
    val height: Dp = width * BAR_LENGTH_RATIO
    /// The tap target, not the drawing: a 34 dp bar is under the 44 dp floor.
    val hitWidth: Dp = maxOf(44.dp, width)

    /// A pinch IS thumb opposition, so the thumb is not a choice. `GripSpec` enforces it in the
    /// model, where a rule belongs: one living in a view is one the next view forgets.
    val locksThumb = position == GripPosition.pinch

    /// **The tick is TRIGGERED BY THE TAP, not by the value.** Keyed on `fingers`, choosing Pinch
    /// ticked twice: once in `PositionChipRow`, again when the model forced the thumb in. A tick
    /// has to name its cause.
    fun toggle(finger: FingerSet) {
        val next = FingerSelection.toggling(finger, fingers)
        if (next == fingers) return
        onChange(next)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (showsName) 8.dp else 6.dp)) {
        if (showsName) {
            // The canonical name, live, so picture and words are never two answers. A STATE change,
            // so `Motion.state` — see `Motion`.
    Crossfade(
        fingers.name,
        animationSpec = Motion.state(rememberReduceMotion()),
        label = "fingerName",
    ) { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
            }
        }

        Row {
            FingerSet.allFingers.forEachIndexed { index, finger ->
                val isOn = fingers.contains(finger)
                val shape = RoundedCornerShape(width / 2)
                Box(
                    Modifier
                        .size(hitWidth, height)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { toggle(finger) }
                        .semantics {
                            role = Role.Button
                            contentDescription =
                                L10n.tr(
                            "%s finger, %s",
                            NAMES[index],
                            L10n.tr(if (isOn) "included" else "not included"),
                        )
                            selected = isOn
                        },
                    // BOTTOM-aligned: the tips, which are ON the edge, share a line.
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .width(width)
                            .height(height * LENGTH_FACTOR[index])
                            .then(
                                if (isOn) {
                                    Modifier.background(palette.graphite, shape)
                                } else {
                                    // Stroked, not tinted: fill-vs-outline survives greyscale and Reduce Transparency.
                                    Modifier.border(1.dp, palette.inkTertiary.copy(alpha = 0.45f), shape)
                                },
                            ),
                    )
                }
            }
        }

        // The thumb as a horizontal bar under the fingers, where it sits in a pinch. Drawn vertical
        // it would be a short fifth finger, and the glyph must be read before any word.
        val thumbOn = fingers.hasThumb
        // The THICKEST digit, so thicker than a finger (a slim tab read as a mistake on the palm).
        val thumbThickness = width * 1.05f
        val thumbLength = hitWidth * 2 + 10.dp
        val thumbShape = RoundedCornerShape(thumbThickness / 2)
        Box(
            Modifier
                // The full pip-row width is the hit area.
                .size(hitWidth * 4 + 30.dp, maxOf(44.dp, thumbThickness))
                .then(
                    if (locksThumb) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { toggle(FingerSet.thumb) }
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription =
                        L10n.tr("Thumb, %s", L10n.tr(if (thumbOn) "included" else "not included"))
                    selected = thumbOn
                    if (locksThumb) disabled()
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .width(thumbLength)
                    .height(thumbThickness)
                    .then(
                        if (thumbOn) {
                            Modifier.background(
                                if (locksThumb) palette.graphite.copy(alpha = 0.55f) else palette.graphite,
                                thumbShape,
                            )
                        } else {
                            Modifier.border(1.dp, palette.inkTertiary.copy(alpha = 0.45f), thumbShape)
                        },
                    ),
            )
        }

        // **Said in WORDS**: the locked bar cannot say it by drawing. Disabling stops false press
        // feedback; this line stops "disabled" reading as "broken".
        if (locksThumb) {
            Text(
                tr("A pinch always includes the thumb."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
            )
        }
    }
}

/// 22 × 38 dp, straight off the palm. The radius rule alone was not enough: at the old 40 × 52
/// a full capsule came out a fat oval. A drawing is its proportions as much as its corners.
private const val BAR_LENGTH_RATIO = 38f / 22f

/// Both hand pickers keep a real finger on the edge; the thumb cannot replace it.
internal object FingerSelection {
    fun toggling(finger: FingerSet, fingers: FingerSet): FingerSet {
        val next = if (fingers.contains(finger)) fingers.subtracting(finger) else fingers.union(finger)
        return if (next.subtracting(FingerSet.thumb).isEmpty) fingers else next
    }
}

/// A hand's proportions, not a bar chart's. Applied to the DRAWN bar only.
private val LENGTH_FACTOR = listOf(0.86f, 1.0f, 0.94f, 0.80f)

/// Index, middle, ring, little — `FingerSet.allFingers` order, so drawing and token agree.
/// A `get()`, not a stored list — see `Tab`.
private val NAMES: List<String>
    get() = listOf(L10n.tr("Index"), L10n.tr("Middle"), L10n.tr("Ring"), L10n.tr("Little"))

@Preview(name = "FingerPips", showBackground = true, widthDp = 360)
@Composable
private fun FingerPipsPreview() {
    GetAGripTheme {
        var fingers by remember { mutableStateOf(FingerSet.frontTwo) }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            FingerPips(fingers, GripPosition.halfCrimp) { fingers = it }
            FingerPips(
                FingerSet.frontTwo.union(FingerSet.thumb),
                GripPosition.pinch,
            ) {}
        }
    }
}
