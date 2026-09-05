// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.PalmGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The drawn hand's arithmetic, with no Canvas in it.
///
/// Every rule here was a real bug on iOS first, and each of them is invisible in a
/// screenshot taken with the wrong hand selected — which is exactly how the mirroring one
/// shipped and had to be spotted by eye (Nuri, 2026-08-09).
class PalmGeometryTests {

    /// **Facing a LEFT palm, the thumb is on the right — and so the index finger is the
    /// RIGHTMOST bar, not the leftmost.** Moving only the thumb was the bug: with the thumb
    /// switched but the fingers left alone, a front-2 grip rendered on the little-finger side
    /// and read as back-2. The whole hand mirrors.
    @Test
    fun aLeftPalmPutsTheIndexFingerOnTheRight() {
        assertTrue(PalmGeometry.isMirrored(Side.left))
        assertEquals(3, PalmGeometry.anatomical(slot = 0, side = Side.left))
        assertEquals(0, PalmGeometry.anatomical(slot = 3, side = Side.left))
    }

    @Test
    fun aRightPalmPutsTheIndexFingerOnTheLeft() {
        assertTrue(!PalmGeometry.isMirrored(Side.right))
        assertEquals(0, PalmGeometry.anatomical(slot = 0, side = Side.right))
        assertEquals(3, PalmGeometry.anatomical(slot = 3, side = Side.right))
    }

    /// A two-handed pull is drawn as a left palm rather than left blank — the same fallback
    /// iOS takes, so a `both` rep never renders a hand with no thumb side at all.
    @Test
    fun bothHandsMirrorsLikeALeftPalm() {
        assertEquals(
            PalmGeometry.isMirrored(Side.left),
            PalmGeometry.isMirrored(Side.both),
        )
    }

    /// The thumb swings AWAY from the palm on whichever side it is on, so the sign has to
    /// follow the mirroring. Both hands get the same 26°.
    @Test
    fun theThumbAngleFlipsSignWithTheHand() {
        assertEquals(PalmGeometry.THUMB_ANGLE, PalmGeometry.thumbAngleDegrees(Side.left))
        assertEquals(-PalmGeometry.THUMB_ANGLE, PalmGeometry.thumbAngleDegrees(Side.right))
    }

    /// **Shallow, not diagonal.** A steeper thumb read as a detached pill lying at an angle,
    /// and on iOS it is the status bar that forces the choice — anything leaving from higher
    /// up runs into the cellular and wifi glyphs. Low and shallow passes safely underneath.
    @Test
    fun theThumbIsShallow() {
        assertTrue(PalmGeometry.THUMB_ANGLE < 45f)
    }

    /// The four bars are CENTRED in whatever width they are given, at a constant pitch — the
    /// island's own 22 + 8, kept because this is a drawing nobody touches. (The builder's
    /// interactive twin uses 44, because a control needs a legal tap target.)
    @Test
    fun theFourBarsAreCentredAtTheIslandsOwnPitch() {
        assertEquals(30f, PalmGeometry.PITCH)
        assertEquals(112f, PalmGeometry.handWidthDp())

        val width = 400f
        val first = PalmGeometry.fingerXDp(0, width)
        val last = PalmGeometry.fingerXDp(3, width)
        assertEquals((width - 112f) / 2f, first, 1e-4f)
        // The right edge of the last bar mirrors the left edge of the first.
        assertEquals(width - first, last + PalmGeometry.BAR_WIDTH, 1e-3f)
        assertEquals(PalmGeometry.PITCH, PalmGeometry.fingerXDp(1, width) - first, 1e-4f)
    }

    /// **A hand's proportions: middle longest, little shortest.** Flat bars read as a
    /// barcode; these read as a hand at a glance, and the same four factors are shared by
    /// `FingerGlyph`, `HandMark` and the app icon.
    @Test
    fun theMiddleFingerIsTheLongestAndTheLittleFingerTheShortest() {
        val lengths = (0 until 4).map { PalmGeometry.fingerLengthDp(it) }
        assertEquals(lengths.max(), lengths[1])
        assertEquals(lengths.min(), lengths[3])
        assertEquals(listOf(0.86f, 1.0f, 0.94f, 0.80f), PalmGeometry.LENGTH_FACTOR)
    }

    /// **22 : 38, the island's own capsule proportion**, and a full capsule radius. One glyph,
    /// one rule — a bar can then never read as a dot.
    @Test
    fun theBarKeepsTheIslandsCapsuleProportion() {
        assertEquals(22f, PalmGeometry.BAR_WIDTH)
        assertEquals(38f, PalmGeometry.BAR_LENGTH)
        assertTrue(PalmGeometry.BAR_LENGTH / PalmGeometry.BAR_WIDTH > 1.5f)
    }

    /// The reserved header contains every finger and leaves the camera region clear.
    @Test
    fun theHandReachesExactlyItsDeclaredClearance() {
        assertTrue(PalmGeometry.FINGER_TOP >= 30f)
        val longest = (0 until 4).maxOf { PalmGeometry.fingerLengthDp(it) }
        assertEquals(PalmGeometry.FINGER_TOP + longest, PalmGeometry.TOTAL_HEIGHT)
        assertTrue(PalmGeometry.THUMB_PIVOT_Y > PalmGeometry.FINGER_TOP)
    }
    @Test
    fun theReportedCameraAlwaysClearsTheFingers() {
        // Pixel 9 emulator reports 142px at 420dpi. The 36dp guess overlapped its mask.
        for (cutoutBottom in listOf(142f / 2.625f, 72f, 96f)) {
            val fingersTop = PalmGeometry.FINGER_TOP + PalmGeometry.additionalTopInsetDp(cutoutBottom)
            assertTrue(fingersTop >= cutoutBottom + PalmGeometry.CAMERA_GAP)
        }
    }

    @Test
    fun screensWithoutACutoutKeepTheFallbackPosition() {
        assertEquals(0f, PalmGeometry.additionalTopInsetDp(0f))
        assertEquals(0f, PalmGeometry.additionalTopInsetDp(20f))
    }

}
