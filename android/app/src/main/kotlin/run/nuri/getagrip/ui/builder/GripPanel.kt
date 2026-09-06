// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.cameraHandOffset
import run.nuri.getagrip.ui.components.PalmGeometry
import run.nuri.getagrip.ui.components.PositionChipRow
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.DarkPalette
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// **THE GRIP PICKER HANGS OFF THE TOP OF THE SCREEN**, with the app's own drawn palm.
///
/// Nuri's idea (2026-08-11), and the runner's own trick made interactive: black capsules
/// hanging under a black panel that runs flush to y = 0, so the cutout — whatever shape
/// this phone's is — sits inside the black and the whole thing reads as one hand.
///
/// **The fingers are the control.** Black with a hairline outline while they are off the
/// edge, solid white when they are on: exactly the reading the runner gives you at arm's
/// length. Nothing else in the app says "which fingers" as fast as this does.
///
/// TRANSLATION NOTE: iOS hangs this off the Dynamic Island, which is a guaranteed 126 ×
/// 37.33 pt black capsule at a known position. Android has no such guarantee — punch-hole
/// here, pill there, nothing at all on a tablet — so the panel supplies its own black, the
/// same decision `PalmHand` already made for the runner. Geometry still MIRRORS
/// `PalmGeometry` and must keep mirroring it: same capsule radius rule, same index-to-little
/// length ratios, same 6 dp clearance. Two deliberate departures, both from iOS:
///
/// - **The finger PITCH is 44 dp, not the runner's 30.** The runner keeps its bars tight
///   because it is a drawing nobody touches; a 30 dp pitch is an illegal tap target.
/// - **The thumb is a HORIZONTAL BAR under the fingers**, exactly as `FingerPips` draws it.
///   Angled off the palm's side like the runner's, it read as a stray pill — the runner can
///   angle it because it is attached to the palm, and at a 44 dp pitch this hand is wider
///   than the palm is.
///
/// **An OVERLAY on the builder, not another presentation.** The state lives at the
/// builder's root (`editingSet`), and the panel binds straight into that set's grip.
@Composable
fun GripPanel(
    grip: GripSpec,
    modifier: Modifier = Modifier,
    onChange: (GripSpec) -> Unit,
    onClose: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    var shown by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }

    // **The status bar is HIDDEN, not re-themed.** The panel runs to y = 0 and dark status
    // glyphs on black vanish; forcing a dark scheme instead propagates to the WINDOW —
    // measured on iOS 2026-08-11, the builder behind turned dark and STAYED dark after
    // dismissal. Hiding the bar for the few seconds this is open costs a clock and leaks
    // nothing. The window also has to draw INTO the cutout, or the panel starts below it
    // and the black is severed exactly where the eye needs it continuous.
    PanelWindowChrome()

    BackHandler(enabled = true) { onClose() }

    Box(modifier.fillMaxSize()) {
        // Darker than a normal scrim: the unselected fingers are BLACK, and they have to
        // read against whatever routine is behind them.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (shown) 0.72f else 0f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                )
                .semantics {
                    role = Role.Button
                    contentDescription = L10n.tr("Close the grip picker")
                },
        )

        AnimatedVisibility(
            visible = shown,
            enter = slideInVertically(tween(if (reduceMotion) 0 else 260)) { -it } +
                fadeIn(tween(if (reduceMotion) 200 else 260)),
            exit = slideOutVertically(tween(if (reduceMotion) 0 else 200)) { -it } +
                fadeOut(tween(if (reduceMotion) 200 else 200)),
        ) {
            // Scoped, never a window-wide scheme: every adaptive token resolves dark inside
            // the card so the ink comes out near-white on black, and nothing leaks.
            CompositionLocalProvider(LocalGripPalette provides DarkPalette) {
                Box(Modifier.fillMaxWidth()) {
                    PanelCard(grip, onChange, onClose)
                    GripPanelHand(grip) { onChange(grip.withFingers(it)) }
                }
            }
        }
    }
}

@Composable
private fun PanelCard(
    grip: GripSpec,
    onChange: (GripSpec) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalGripPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Color.Black,
                // Rounded at the BOTTOM only. The top is square and flush with the screen
                // edge, so the panel and whatever cutout is up there are one black object.
                RoundedCornerShape(bottomStart = 42.dp, bottomEnd = 42.dp),
            )
            // Clears the hand hanging above it.
            .padding(top = HAND_CLEARANCE + cameraHandOffset(), bottom = 20.dp)
            .padding(horizontal = Metrics.hPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The grip, said in one line — the sentence that proves the hand above means what
        // you think it does.
        Text(
            tr("%d mm · %s · %s", grip.edgeMM, grip.fingers.name, grip.position.name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
            maxLines = 1,
        )

        CapsLabel(tr("GRIP"))
        PositionChipRow(grip.position) { onChange(grip.withPosition(it)) }

        IntValueRow(
            title = tr("Edge"),
            value = grip.edgeMM,
            range = 4..45,
            unit = tr("mm"),
            // The SLIDER-equivalent span is what you reach for; the typed LIMIT keeps the
            // generous storage bound, so 22 mm and a 60 mm rail both stay expressible.
            limit = GripSpec.edgeRange,
            control = ValueControl.Dial(
                listOf(6.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 45.0),
            ),
        ) { onChange(grip.withEdgeMM(it)) }

        // Said in WORDS, mirroring `FingerPips` — the locked thumb bar dims and refuses
        // taps with nothing else on screen to say why, and this is the primary place a grip
        // gets edited. A control that looks live and refuses the tap is the exact failure
        // the hit-target rule exists to prevent.
        if (grip.position == GripPosition.pinch) {
            Text(
                tr("A pinch always includes the thumb."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkTertiary,
            )
        }

        Text(
            tr("Done"),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(top = 2.dp)
                .fillMaxWidth()
                .height(46.dp)
                .background(palette.inkPrimary, RoundedCornerShape(23.dp))
                .clickable(onClick = onClose, role = Role.Button)
                .padding(vertical = 12.dp),
        )
    }
}

/// The tappable hand: four fingers hanging off the panel's black, and the thumb's
/// horizontal bar under them.
@Composable
private fun GripPanelHand(
    grip: GripSpec,
    onFingersChange: (FingerSet) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    /// A pinch IS thumb opposition, so under it the thumb is not a choice — `GripSpec`
    /// enforces the same rule in the model.
    val locksThumb = grip.position == GripPosition.pinch

    /// A set with no fingers on the edge is not a grip, so tapping the last engaged bar is
    /// a no-op — and gets no tick either, because confirming a refusal is how feedback
    /// stops meaning anything.
    fun toggle(finger: FingerSet) {
        val fingers = grip.fingers
        val next = run.nuri.getagrip.ui.components.FingerSelection.toggling(finger, fingers)
        if (next == fingers) return
        onFingersChange(next)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    val cameraOffset = cameraHandOffset()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val mid = maxWidth / 2
        val handWidth = PITCH * 3 + BAR_WIDTH
        val originX = mid - handWidth / 2

        FingerSet.allFingers.forEachIndexed { index, finger ->
            val isOn = grip.fingers.contains(finger)
            val length = BASE_LENGTH * PalmGeometry.LENGTH_FACTOR[index]
            Box(
                Modifier
                    // ALL FOUR HIT BOXES THE SAME HEIGHT AND TOP-ALIGNED. Centring each
                    // capsule in its own box floated the little finger in mid-air and read
                    // as four unrelated pills. Fingers hang from a palm: they share a top
                    // edge and differ at the tip.
                    .offset(x = originX + PITCH * index, y = FINGERS_TOP + cameraOffset)
                    .size(PITCH, ROW_HEIGHT)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { toggle(finger) }
                    .semantics {
                        role = Role.Button
                        contentDescription =
                            L10n.tr(
                                "%s finger, %s",
                                FINGER_NAMES[index],
                                L10n.tr(if (isOn) "included" else "not included"),
                            )
                        selected = isOn
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier
                        .width(BAR_WIDTH)
                        .height(length)
                        .then(
                            if (isOn) {
                                Modifier.background(Color.White, RoundedCornerShape(BAR_WIDTH / 2))
                            } else {
                                Modifier.border(
                                    1.5.dp,
                                    Color.White.copy(alpha = 0.5f),
                                    RoundedCornerShape(BAR_WIDTH / 2),
                                )
                            },
                        ),
                )
            }
        }

        val thumbOn = grip.fingers.hasThumb
        Box(
            Modifier
                .offset(x = originX, y = THUMB_TOP + cameraOffset)
                .size(THUMB_WIDTH, 44.dp)
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
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(THUMB_WIDTH)
                    .height(THUMB_THICKNESS)
                    .then(
                        if (thumbOn) {
                            Modifier.background(
                                if (locksThumb) Color.White.copy(alpha = 0.55f) else Color.White,
                                RoundedCornerShape(THUMB_THICKNESS / 2),
                            )
                        } else {
                            Modifier.border(
                                1.5.dp,
                                Color.White.copy(alpha = 0.5f),
                                RoundedCornerShape(THUMB_THICKNESS / 2),
                            )
                        },
                    ),
            )
        }
    }
}

/// Portrait-agnostic, but it must draw INTO the cutout and hide the status bar, or the
/// panel's black stops short of the top and the illusion is severed at exactly the junction
/// the eye needs continuous. Both are restored on the way out.
@Composable
private fun PanelWindowChrome() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        val previousCutout = window.attributes.layoutInDisplayCutoutMode
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        // TRANSIENT by swipe, never sticky-hidden: the clock is one pull away for anyone
        // who wants it.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = previousBehavior
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = previousCutout
            }
        }
    }
}

// Geometry, mirroring `PalmGeometry` except where a CONTROL needs more room than a drawing.
private val BAR_WIDTH: Dp = 26.dp

/// 44 dp centres. The hit target IS the pitch, so the fingers cannot overlap and none of
/// them is under the floor.
private val PITCH: Dp = 44.dp
private val BASE_LENGTH: Dp = 46.dp

/// The panel's own black is the palm, so the fingers hang from the same place the runner's
/// do: the palm's bottom plus the clearance every part of that hand keeps.
private val FINGERS_TOP: Dp = PalmGeometry.FINGER_TOP.dp

/// ONE box height for all four, so they share a top edge and the hit areas line up.
private val ROW_HEIGHT: Dp = 46.dp
private val THUMB_WIDTH: Dp = 70.dp
private val THUMB_THICKNESS: Dp = 26.dp
private val THUMB_TOP: Dp = FINGERS_TOP + ROW_HEIGHT + 6.dp

/// Everything above this belongs to the hand.
private val HAND_CLEARANCE: Dp = THUMB_TOP + 44.dp + 12.dp

/// A `get()`, not a stored list — same note as `FingerPips.NAMES`.
private val FINGER_NAMES: List<String>
    get() = listOf(L10n.tr("Index"), L10n.tr("Middle"), L10n.tr("Ring"), L10n.tr("Little"))

@Preview(name = "GripPanel", showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun GripPanelPreview() {
    GetAGripTheme {
        var grip by remember { mutableStateOf(GripSpec(20, FingerSet.frontTwo, GripPosition.halfCrimp)) }
        GripPanel(grip, onChange = { grip = it }, onClose = {})
    }
}
