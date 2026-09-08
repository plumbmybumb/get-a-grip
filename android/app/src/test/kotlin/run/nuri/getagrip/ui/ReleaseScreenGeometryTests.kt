// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.view.RoundedCorner
import android.view.WindowInsets
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.runner.ReleaseCorner
import run.nuri.getagrip.ui.runner.ReleaseWindowGeometry
import run.nuri.getagrip.ui.runner.releaseScreenOutline
import run.nuri.getagrip.ui.runner.releaseWindowGeometry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleaseScreenGeometryTests {
    @Test
    @Config(sdk = [31, 35])
    fun platformCornersRetainCentersAndMissingCornersStaySquare() {
        val insets = WindowInsets.Builder()
            .setRoundedCorner(RoundedCorner.POSITION_TOP_LEFT,
                RoundedCorner(RoundedCorner.POSITION_TOP_LEFT, 40, 25, 40))
            .build()
        val geometry = releaseWindowGeometry(insets)
        assertEquals(listOf(ReleaseCorner(RoundedCorner.POSITION_TOP_LEFT, 40f, 25f, 40f)),
            geometry.corners)
        val region = region(outline(geometry, width = 100f, height = 200f))
        assertFalse(region.contains(0, 0))
        assertTrue(region.contains(0, 12), "The reported center is 25, not radius 40")
        assertTrue(region.contains(99, 0), "No top-right rounded corner was reported")
        assertTrue(region.contains(0, 199))
        assertTrue(region.contains(99, 199))
    }

    @Test
    @Config(sdk = [31, 35])
    fun missingInsetsAndZeroSizeAreHandledWithoutInventingCurves() {
        val geometry = releaseWindowGeometry(null)
        assertNull(geometry.displayOutline)
        assertTrue(geometry.corners.isEmpty())
        val region = region(outline(geometry))
        assertTrue(region.contains(0, 0))
        assertTrue(region.contains(119, 199))
        assertFalse(region.contains(120, 199))
        assertTrue(outline(geometry, width = 0f).isEmpty)
    }

    @Test fun unequalCornerRadiiProduceUnequalCurves() {
        val region = region(outline(ReleaseWindowGeometry(null, unequalCorners)))
        assertFalse(region.contains(0, 0))
        assertTrue(region.contains(10, 10))
        assertFalse(region.contains(110, 5))
        assertTrue(region.contains(100, 20))
        assertTrue(region.contains(0, 199), "Square bottom-left stays square")
        assertFalse(region.contains(119, 199))
        assertTrue(region.contains(112, 192))
    }

    @Test fun partialWindowUsesActualCornerCentersAndStraightWindowSeams() {
        val geometry = ReleaseWindowGeometry(null,
            listOf(ReleaseCorner(RoundedCorner.POSITION_TOP_LEFT, 40f, 40f, 40f)))
        val region = region(outline(geometry, width = 80f, height = 160f, x = 20f))
        assertFalse(region.contains(0, 0))
        assertTrue(region.contains(0, 8), "Cropping a curve must not move its center")
        assertTrue(region.contains(79, 0), "The new app-window seam is square")
        assertTrue(region.contains(0, 159))
        assertFalse(region.contains(-1, 100))
        assertFalse(region.contains(80, 100))
    }

    @Test fun nativeNonCircularOutlineWinsOverApproximateCornersAndIsNeverMutated() {
        val native = Path().apply {
            moveTo(30f, 0f)
            lineTo(120f, 0f)
            lineTo(120f, 200f)
            lineTo(0f, 200f)
            lineTo(0f, 60f)
            // A deliberately noncircular top-left corner, like a manufacturer outline.
            cubicTo(0f, 8f, 2f, 0f, 30f, 0f)
            close()
        }
        val originalBounds = RectF().also { native.computeBounds(it, true) }
        val geometry = ReleaseWindowGeometry(native, unequalCorners)
        val originalRegion = region(native)
        val result = region(outline(geometry))
        assertEquals(originalRegion, result, "Use the complete platform path verbatim")
        val shifted = region(outline(geometry, width = 90f, height = 150f, x = 10f, y = 20f))
        for (y in 0 until 150 step 7) for (x in 0 until 90 step 7) {
            assertEquals(originalRegion.contains(x + 10, y + 20), shifted.contains(x, y))
        }
        assertEquals(originalBounds, RectF().also { native.computeBounds(it, true) })
        assertEquals(originalRegion, region(native), "OS-owned geometry must survive all draws")
    }

    @Test fun rectangularPlatformFallbackKeepsReportedRoundedCorners() {
        val defaultDisplay = Path().apply { addRect(0f, 0f, 120f, 200f, Path.Direction.CW) }
        val fallback = region(outline(ReleaseWindowGeometry(null, unequalCorners)))
        val supplied = region(outline(ReleaseWindowGeometry(defaultDisplay, unequalCorners)))
        assertEquals(fallback, supplied)
    }

    @Test fun unsetPlatformDisplayShapeFallsBackWithoutThrowing() {
        // CONSUMED carries the platform's empty DisplayShape. Reading its Path used to
        // throw "Path string cannot be empty" in a fresh runner-only test process.
        // Use the consumed instance directly: its rounded-corner collection is null,
        // so copying it into a Builder then setting a corner is not a valid fixture.
        val geometry = releaseWindowGeometry(WindowInsets.CONSUMED)
        val result = region(outline(geometry))
        assertTrue(result.contains(0, 0))
        assertTrue(result.contains(10, 10))
        assertTrue(result.contains(119, 199))
    }

    @Test fun rotatedNativeOutlineAndResizedWindowAreCroppedInWindowCoordinates() {
        val portrait = Path().apply {
            addRoundRect(RectF(0f, 0f, 120f, 200f),
                floatArrayOf(20f, 20f, 40f, 40f, 12f, 12f, 0f, 0f), Path.Direction.CW)
        }
        val rotation = Matrix().apply { setRotate(90f); postTranslate(200f, 0f) }
        val landscape = Path(portrait).apply { transform(rotation) }
        val geometry = ReleaseWindowGeometry(landscape, emptyList())
        val full = region(outline(geometry, width = 200f, height = 120f))
        assertTrue(full.contains(0, 0), "The original square bottom-left rotated to top-left")
        assertFalse(full.contains(199, 0))
        assertFalse(full.contains(199, 119))
        assertFalse(full.contains(0, 119))
        val partial = region(outline(geometry, width = 100f, height = 120f, x = 50f))
        assertTrue(partial.contains(0, 0))
        assertTrue(partial.contains(99, 119))
        assertFalse(partial.contains(100, 119))
    }

    @Test fun innerStrokeTouchesAllEdgesWithSixPixelThicknessAndLeavesCenterClear() {
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        val path = outline(ReleaseWindowGeometry(null, unequalCorners))
        val canvas = Canvas(bitmap)
        canvas.clipPath(path)
        canvas.drawPath(path, Paint().apply {
            color = Color.rgb(255, 140, 0)
            style = Paint.Style.STROKE
            strokeWidth = 12f
            isAntiAlias = false
        })
        val orange = Color.rgb(255, 140, 0)
        for (offset in 0..5) {
            assertEquals(orange, bitmap.getPixel(60, offset))
            assertEquals(orange, bitmap.getPixel(60, 199 - offset))
            assertEquals(orange, bitmap.getPixel(offset, 100))
            assertEquals(orange, bitmap.getPixel(119 - offset, 100))
        }
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(60, 6))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(6, 100))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(60, 100))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0), "Do not paint outside the physical curve")
    }

    private fun outline(
        geometry: ReleaseWindowGeometry, width: Float = 120f, height: Float = 200f,
        x: Float = 0f, y: Float = 0f,
    ) = releaseScreenOutline(width, height, x, y, geometry)

    private fun region(path: Path) = Region().apply { setPath(path, Region(-1000, -1000, 2000, 2000)) }

    private val unequalCorners = listOf(
        ReleaseCorner(RoundedCorner.POSITION_TOP_LEFT, 20f, 20f, 20f),
        ReleaseCorner(RoundedCorner.POSITION_TOP_RIGHT, 40f, 80f, 40f),
        ReleaseCorner(RoundedCorner.POSITION_BOTTOM_RIGHT, 12f, 108f, 188f),
    )
}
