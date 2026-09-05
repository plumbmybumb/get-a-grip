// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue
import run.nuri.getagrip.ui.theme.DarkPalette
import run.nuri.getagrip.ui.theme.GripPalette
import run.nuri.getagrip.ui.theme.LightPalette
import run.nuri.getagrip.ui.theme.mineralField

/// **CONTRAST IS MEASURED, NEVER EYEBALLED.**
///
/// That rule is in the root `CLAUDE.md` because dark mode has now hidden two real failures
/// from a careful eye on the iOS app — a white-on-white primary button, and a routine card
/// whose rep counts sat at 3.7:1 while its hollow rings sat at 2.1:1. The method there is
/// screenshot pixels; this is the same arithmetic run against the palette's own literals, in
/// BOTH schemes, on every build.
///
/// It is a TEST rather than only a script (`android/scripts/measure_contrast.py`, which
/// reports the same numbers) because a token is one character away from failing and nothing
/// on screen says so. What the arithmetic cannot see is stated where it matters: a thin
/// stroke is mostly antialiased edge and measures well below its nominal ratio, so a 1–1.5 dp
/// line needs its colour a full step stronger than these numbers suggest — that half still
/// needs a real screenshot from the phone.
///
/// Floors are WCAG 2.1: 4.5:1 for body text, 3:1 for meaningful graphics.
class ContrastTests {

    private companion object {
        const val TEXT_FLOOR = 4.5
        const val GRAPHIC_FLOOR = 3.0
    }

    /// One sRGB channel to linear light. The 0.03928 knee is WCAG's own.
    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    /// Flatten a translucent foreground onto an opaque background — source-over, in sRGB,
    /// because that is what the GPU does for a normal blend.
    private fun over(fg: Color, bg: Color): Color = Color(
        red = fg.red * fg.alpha + bg.red * (1 - fg.alpha),
        green = fg.green * fg.alpha + bg.green * (1 - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
    )

    private fun ratio(fg: Color, bg: Color): Double {
        val a = luminance(over(fg, bg))
        val b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun assertContrast(label: String, fg: Color, bg: Color, floor: Double) {
        val r = ratio(fg, bg)
        assertTrue(r >= floor - 1e-6, "$label measured %.2f:1, floor %.1f:1".format(r, floor))
    }

    private fun schemes(): List<Pair<String, GripPalette>> =
        listOf("light" to LightPalette, "dark" to DarkPalette)

    // MARK: - Text

    /// The three inks against both grounds. **Tertiary is the one that moved** (2026-09-04):
    /// it shipped at 0xFF737373 / 0xFF999999 and measured 3.61:1 and 4.34:1 — below the floor
    /// on surfaces that carry real footnotes. It is still not for numbers that carry the
    /// protocol, but that is a hierarchy rule, not a licence to be unreadable.
    @Test
    fun everyInkClearsTheTextFloorOnBothGrounds() {
        for ((name, p) in schemes()) {
            for ((inkName, ink) in listOf(
                "inkPrimary" to p.inkPrimary,
                "inkSecondary" to p.inkSecondary,
                "inkTertiary" to p.inkTertiary,
            )) {
                assertContrast("$name $inkName on field", ink, p.field, TEXT_FLOOR)
                assertContrast("$name $inkName on card", ink, p.card, TEXT_FLOOR)
            }
        }
    }

    @Test
    fun textAndLiveSignalRemainReadableAcrossTheTexturedField() {
        for ((name, p) in schemes()) {
            val material = mineralField(p == DarkPalette)
            // All gradient endpoints and overlay extrema. Checking their combinations is
            // conservative: the radial lights do not peak in the same physical position.
            for (stop in material.stops) for (light in listOf(Color.Transparent, material.light)) {
                for (cloud in listOf(Color.Transparent, material.cloud)) {
                    for (grain in listOf(Color.Black, Color.White)) {
                        val ground = over(grain.copy(alpha = material.grainAlpha), over(cloud, over(light, stop)))
                        for (ink in listOf(p.inkPrimary, p.inkSecondary, p.inkTertiary, p.graphite, p.alarm)) {
                            assertContrast("$name text on mineral field", ink, ground, TEXT_FLOOR)
                        }
                        assertContrast("$name live signal on mineral field", p.bleu, ground, GRAPHIC_FLOOR)
                    }
                }
            }
        }
    }

    /// **`Accent.graphite` INVERTS with the colour scheme**, which is why it can never be a
    /// fill without an explicit label colour — the iOS bug this rule came from was a primary
    /// button rendering white-on-white in dark mode. Both directions are pinned here: graphite
    /// as ink on both grounds, and its inverse as the label on a graphite pill.
    @Test
    fun graphiteReadsAsInkAndItsInverseReadsOnTheFill() {
        for ((name, p) in schemes()) {
            assertContrast("$name graphite on field", p.graphite, p.field, TEXT_FLOOR)
            assertContrast("$name graphite on card", p.graphite, p.card, TEXT_FLOOR)
            assertContrast("$name graphite pill label", p.graphiteInverse, p.graphite, TEXT_FLOOR)
        }
    }

    /// Alarm is text — "the gauge dropped", "delete this". **It moved with tertiary**: light
    /// measured 4.28:1 on the field and dark 3.64:1 on a card.
    @Test
    fun alarmInkClearsTheTextFloor() {
        for ((name, p) in schemes()) {
            assertContrast("$name alarm on field", p.alarm, p.field, TEXT_FLOOR)
            assertContrast("$name alarm on card", p.alarm, p.card, TEXT_FLOOR)
        }
    }

    /// **`alarmFlat` is the alarm as a FILL, and it is a fixed literal in both schemes.**
    /// `alarm` has to LIGHTEN in dark mode to stay legible as ink, and white on that lightened
    /// red measures 3.40:1 — under the floor on the one surface whose only job is to say
    /// DELETE. Two jobs, two tokens; a literal cannot move with the scheme, so this ratio
    /// cannot either. (iOS spells the same split `Accent.alarm` / `Accent.alarmFlat`.)
    @Test
    fun whiteOnTheDeleteBackdropClearsTheTextFloorInBothSchemes() {
        for ((name, p) in schemes()) {
            assertContrast("$name white on alarmFlat", Color.White, p.alarmFlat, TEXT_FLOOR)
        }
        assertTrue(
            LightPalette.alarmFlat == DarkPalette.alarmFlat,
            "alarmFlat must be one literal, or the ratio moves with the scheme",
        )
    }

    // MARK: - Graphics

    /// Bleu is the LIVE-FORCE signal: the trace, the work ring, the pull prompt. A mark that
    /// carries information owes 3:1.
    @Test
    fun theLiveForceSignalClearsTheGraphicsFloor() {
        for ((name, p) in schemes()) {
            assertContrast("$name bleu on field", p.bleu, p.field, GRAPHIC_FLOOR)
            assertContrast("$name bleu on card", p.bleu, p.card, GRAPHIC_FLOOR)
        }
    }

    /// Moss (the light-intensity rung) and calm (the steel ring) are both marks that mean
    /// something.
    @Test
    fun theRungAndTheSteelRingClearTheGraphicsFloor() {
        for ((name, p) in schemes()) {
            assertContrast("$name moss on card", p.moss, p.card, GRAPHIC_FLOOR)
            assertContrast("$name calm on card", p.calm, p.card, GRAPHIC_FLOOR)
        }
    }

    /// **Amber is a FILL, never small text**, and this is the pair that says so: the rest
    /// badge's fixed dark ink on its amber capsule. Amber as INK on the light field measures
    /// 2.59:1 and cannot be fixed by darkening the token without breaking this pair, which is
    /// why every amber cue also changes its WORD ("New grip" / "Next", "PAUSED", "LET GO") —
    /// the cue survives greyscale on its own. `armed` is carried unchanged from iOS.
    @Test
    fun theAmberCapsuleCarriesItsFixedInk() {
        val badgeInk = Color(0xFF1B1F25)
        for ((name, p) in schemes()) {
            assertContrast("$name rest badge ink on armed", badgeInk, p.armed, TEXT_FLOOR)
        }
        assertTrue(
            LightPalette.armed == DarkPalette.armed,
            "armed is one literal in both schemes, so the badge's ratio cannot move",
        )
    }

    // MARK: - Surfaces

    /// **The inset well is a STEP, not a contrast ratio.** It exists to be a quiet move off
    /// the card in BOTH schemes — visible enough to frame a diagram, far too quiet to read as
    /// a control. Pinned as a bounded step so a future edit cannot turn it into a second card
    /// or into nothing at all.
    @Test
    fun theInsetWellIsAQuietStepOffTheCardInBothSchemes() {
        for ((name, p) in schemes()) {
            val step = ratio(p.well, p.card)
            assertTrue(step > 1.02, "$name well is invisible against the card (%.3f:1)".format(step))
            assertTrue(step < 1.35, "$name well reads as a second surface (%.3f:1)".format(step))
        }
    }

    /// The card must be a legible step off the ground it sits on, or a page of cards reads as
    /// one flat sheet.
    @Test
    fun theCardIsAVisibleStepOffTheField() {
        for ((name, p) in schemes()) {
            val step = ratio(p.card, p.field)
            assertTrue(step > 1.05, "$name card does not separate from the field (%.3f:1)".format(step))
        }
    }
}
