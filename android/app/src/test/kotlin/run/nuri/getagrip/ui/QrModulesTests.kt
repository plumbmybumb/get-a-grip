// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.ui.share.QrModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// A QR code's SIZE arithmetic, with no bitmap in it.
///
/// All three rules here are about whether the picture can still be read by a camera, which
/// is not something a screenshot answers: a code with no quiet zone and a code with one look
/// nearly identical on screen and only one of them scans.
class QrModulesTests {

    /// **The quiet zone is four modules and it is not decoration.** The finder patterns are
    /// located by their light surround, so a code drawn to its own edge is not a tight code
    /// — it is one a decoder cannot find at all. Four is the QR specification's own number.
    @Test
    fun theQuietZoneIsFourModulesOnEverySide() {
        assertEquals(4, QrModules.quietZone)
        // 21 is the smallest possible code (version 1); 21 + 4 + 4 = 29.
        assertEquals(29, QrModules.canvas(21))
        assertEquals(125, QrModules.canvas(117))
    }

    /// A nonsense module count still produces a drawable canvas rather than a zero-sized
    /// bitmap — the encoder is the thing that decides a payload is impossible, and it does
    /// that by throwing, not by returning an empty matrix.
    @Test
    fun theCanvasIsNeverEmpty() {
        assertTrue(QrModules.canvas(0) > 0)
        assertTrue(QrModules.canvas(-5) > 0)
    }

    /// **The blow-up is an INTEGER, and it floors.** At a fractional scale two adjacent
    /// modules land on a different number of pixels, so the grid a decoder measures against
    /// is uneven before any resampling has happened. Flooring makes the bitmap slightly
    /// smaller than the target, which the drawn size then scales up with no filtering — so a
    /// whole module still maps to a whole block.
    @Test
    fun theScaleIsAFlooredInteger() {
        assertEquals(8, QrModules.scale(canvasModules = 29, targetPixels = 240))
        // 240 / 125 = 1.92 → 1, never 2: rounding up would draw a bitmap wider than asked
        // for, and the size proposed to the image is what it gets squeezed back into.
        assertEquals(1, QrModules.scale(canvasModules = 125, targetPixels = 240))
        assertEquals(6, QrModules.scale(canvasModules = 125, targetPixels = 750))
    }

    /// **Never zero.** A dense code against a small target would otherwise multiply out to a
    /// bitmap with no pixels in it — an empty square, which reads as a code that simply did
    /// not scan rather than as a bug.
    @Test
    fun theScaleNeverFallsToZero() {
        assertEquals(1, QrModules.scale(canvasModules = 189, targetPixels = 100))
        assertEquals(1, QrModules.scale(canvasModules = 29, targetPixels = 0))
        assertEquals(1, QrModules.scale(canvasModules = 0, targetPixels = 500))
    }

    /// **And never unbounded.** The share format allows a 4 KB payload; an uncapped
    /// multiplier against a large target is how a share sheet allocates tens of megabytes to
    /// draw one square.
    @Test
    fun theScaleIsCapped() {
        assertEquals(20, QrModules.scale(canvasModules = 29, targetPixels = 100_000))
        assertEquals(4, QrModules.scale(canvasModules = 29, targetPixels = 100_000, maximum = 4))
    }

    /// The bitmap's side is exact by construction — which is only true because the scale is
    /// an integer, and is the reason nothing downstream has to round.
    @Test
    fun thePixelSizeIsTheCanvasTimesTheScale() {
        assertEquals(232, QrModules.pixels(29, 8))
        assertEquals(750, QrModules.pixels(125, 6))
        // The drawn bitmap always covers the whole canvas, quiet zone included.
        val canvas = QrModules.canvas(117)
        val scale = QrModules.scale(canvas, 750)
        assertEquals(canvas * scale, QrModules.pixels(canvas, scale))
    }
}
