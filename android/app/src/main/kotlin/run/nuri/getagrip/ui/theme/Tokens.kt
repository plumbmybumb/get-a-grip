// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The design tokens, ported 1:1 from Shared/DesignTokens.swift. Same bones as the iOS
// app: slate field, graphite interactive chrome, semantic ink, and exactly two signal
// hues — bleu for "force is live", alarm red for "attention here". The house discipline
// is that a colour can only mean something if it is not also the baseline, which is
// why chrome stays graphite (ink, not a hue) and why DYNAMIC COLOUR IS OFF: a
// wallpaper-derived palette would spend the whole spectrum on decoration.

/// Everything the screens read. Resolved once per colour scheme in `GetAGripTheme`.
@Immutable
data class GripPalette(
    /// Semantic "ink" for text so the whole app flips together in dark mode.
    val inkPrimary: Color,
    val inkSecondary: Color,
    /// The quietest ink, and it still has to be READABLE — footnotes and captions are
    /// sentences. Measured at 4.72:1 on the field and 5.53:1 on a card in light, 4.73:1 and
    /// 5.60:1 in dark (`scripts/measure_contrast.py --tokens`). It is still not for numbers
    /// that carry the protocol: that is a hierarchy rule, not a contrast one.
    val inkTertiary: Color,
    /// The INTERACTIVE accent — tabs, buttons, selection. Near-black in light, near-white
    /// in dark: it reads as ink rather than as a colour, so it can never be a fill without
    /// its explicit inverse.
    val graphite: Color,
    val graphiteInverse: Color,
    /// Bleu de France — the identity hue and the LIVE-FORCE signal.
    val bleu: Color,
    /// Light-intensity signal only (a routine rung at ≤ 30 % of max).
    val moss: Color,
    /// RESERVED for attention: dropout, disconnect, destructive. Never chrome. This is the
    /// INK — it is measured against the field and the card, and it is not what a red surface
    /// is filled with.
    val alarm: Color,
    /// The alarm as a FILL, under white text — the swipe-to-delete backdrop.
    ///
    /// **Fixed in both schemes, exactly like iOS's `Accent.alarmFlat`**, and that is the
    /// whole point: `alarm` has to LIGHTEN in dark mode to stay legible as ink, and white on
    /// that lightened red measures 3.40:1 — under the 4.5:1 floor on the one surface whose
    /// only job is to say DELETE. Two jobs, two tokens; a literal cannot move with the
    /// scheme, so this ratio cannot either. (Same reasoning as `RestBadge`'s amber capsule
    /// with its fixed dark ink.)
    val alarmFlat: Color,
    /// Runner phases: steel while calm, amber while waiting on you.
    val calm: Color,
    val armed: Color,
    /// The ground, the card on it, and the inset well on the card.
    val field: Color,
    val card: Color,
    val well: Color,
)

/// The deep red both schemes fill with. Named once so the two palettes cannot drift — and
/// declared ABOVE them, because top-level properties initialise in declaration order and
/// so it reads as the literal it is: white on it measures 5.62:1.
private val ALARM_FLAT = Color(0xFFC62828)

val LightPalette = GripPalette(
    inkPrimary = Color(0xFF2E2E2E),
    inkSecondary = Color(0xFF4D4D4D),
    inkTertiary = Color(0xFF5B5B5B),
    graphite = Color(0xFF2B3038),
    graphiteInverse = Color.White,
    bleu = Color(0xFF1E6FC4),
    moss = Color(0xFF1F7A4A),
    alarm = Color(0xFFB42020),
    alarmFlat = ALARM_FLAT,
    calm = Color(0xFF5F7086),
    armed = Color(0xFFFF9800),
    field = Color(0xFFDDE1E7),
    card = Color(0xFFF0F2F6),
    well = Color(0x0D2E2E2E),
)

val DarkPalette = GripPalette(
    inkPrimary = Color(0xFFEDEDED),
    inkSecondary = Color(0xFFBDBDBD),
    inkTertiary = Color(0xFFA0A0A0),
    graphite = Color(0xFFE7EBF1),
    graphiteInverse = Color(0xFF1B1F25),
    bleu = Color(0xFF5AA9F0),
    moss = Color(0xFF58BE8B),
    alarm = Color(0xFFF3786F),
    alarmFlat = ALARM_FLAT,
    calm = Color(0xFF9FAEC2),
    armed = Color(0xFFFF9800),
    field = Color(0xFF24292F),
    card = Color(0xFF2F353D),
    well = Color(0x0DEDEDED),
)

val LocalGripPalette = staticCompositionLocalOf { LightPalette }

/** Orange used as text needs a darker light-mode ink than the bright cue outline.
 * Matches the readable armed text pair used by the iOS grip-change description. */
val GripPalette.armedText: Color
    get() = if (this == DarkPalette) Color(0xFFFFB37A) else Color(0xFFBF360C)

/// House metrics (points → dp, one to one).
object Metrics {
    val radiusSheet = 32.dp
    val radiusCard = 22.dp
    val radiusInner = 16.dp
    val spacing = 22.dp
    val hPadding = 20.dp
    val maxContentWidth = 440.dp
    val fieldHeight = 54.dp
    val buttonHeight = 56.dp
    val buttonHorizontalPadding = 16.dp
    val buttonVerticalPadding = 10.dp
    val controlMinHeight = 48.dp
}

/// THE MOTION LADDER — every animation in the app comes from here. Three curves, never
/// an ad-hoc one (see the iOS rationale in Shared/DesignTokens.swift).
object Motion {
    // FINITE, not just `AnimationSpec`: `expandVertically`/`fadeIn` and every other
    // `EnterTransition` require a spec that is guaranteed to END, and both branches of every
    // curve here already are one. Widening the declared type is what lets a disclosure take
    // the ladder instead of Compose's unguarded 400 ms default.
    /// The default, critically damped: disclosures, selection, a row appearing. Overshoot
    /// on something that merely appeared is decoration.
    fun <T> state(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /// After a flick or a drag release, and ONLY then — overshoot is something motion
    /// earns by having had momentum.
    fun <T> momentum(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    /// A live sensor value settling: far shorter than any transition, because it is
    /// tracking a signal, and a readout that overshoots is lying about a measurement.
    fun <T> live(): FiniteAnimationSpec<T> = tween(durationMillis = 120, easing = LinearOutSlowInEasing)

    /// A cumulative measured bar needs constant travel between radio batches. Easing
    /// each packet to a stop makes even a frame-driven bar appear to move in steps.
    fun <T> measuredProgress(): FiniteAnimationSpec<T> = tween(durationMillis = 200, easing = LinearEasing)

    /// A grip change earns one clear beat of attention, with no bounce or metric movement.
    const val GRIP_CHANGE_HOLD_MILLIS = 1_200L
    const val GRIP_CHANGE_RISE_MILLIS = 180L
    fun <T> gripChangePulse(): FiniteAnimationSpec<T> = tween(durationMillis = 300, easing = FastOutSlowInEasing)
    fun <T> gripChangeIn(): FiniteAnimationSpec<T> = tween(durationMillis = GRIP_CHANGE_RISE_MILLIS.toInt(), easing = LinearOutSlowInEasing)
    fun <T> gripChangeOut(): FiniteAnimationSpec<T> = tween(durationMillis = 300, easing = FastOutSlowInEasing)

    /// Reduce Motion: a cross-fade-length ease with no travel and no overshoot.
    private fun <T> reduced(): FiniteAnimationSpec<T> = tween(durationMillis = 200, easing = FastOutSlowInEasing)
}
