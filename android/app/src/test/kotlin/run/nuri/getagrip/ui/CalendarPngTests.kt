// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.history.encodeCalendarPng
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarPngTests {
    @Test fun workerEncodingPreservesExportDimensionsAndTransparentPixels() = runBlocking {
        val source = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        source.setPixel(540, 540, Color.rgb(37, 121, 190))
        val png = assertNotNull(encodeCalendarPng(source))
        val decoded = assertNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        assertEquals(1080, decoded.width)
        assertEquals(1080, decoded.height)
        assertEquals(Color.TRANSPARENT, decoded.getPixel(0, 0))
        assertEquals(source.getPixel(540, 540), decoded.getPixel(540, 540))
    }
}
