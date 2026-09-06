// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RoutineShare
import run.nuri.getagrip.ui.share.QrImage
import run.nuri.getagrip.ui.share.rememberQrImage
import run.nuri.getagrip.ui.share.renderQr
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrResponsivenessTests {
    @get:Rule val compose = createComposeRule()

    @Test fun shareActionsRemainResponsiveAndReplacementCancelsTheOldCode() {
        var payload by mutableStateOf("first")
        var observed: QrImage? = null
        val firstStarted = AtomicBoolean()
        val firstCancelled = AtomicBoolean()
        val offMain = AtomicBoolean()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val firstBitmap = ImageBitmap(2, 2)
        val secondBitmap = ImageBitmap(3, 3)
        compose.setContent {
            val result = rememberQrImage(payload, 750) { text, _ ->
                offMain.set(Looper.myLooper() != Looper.getMainLooper())
                if (text == "first") {
                    firstStarted.set(true)
                    try { firstGate.await() } finally { firstCancelled.set(true) }
                    firstBitmap
                } else {
                    secondGate.await()
                    secondBitmap
                }
            }
            SideEffect { observed = result }
            BasicText("Next routine", Modifier.clickable { payload = "second" })
        }
        compose.waitUntil(5_000) { firstStarted.get() }
        compose.runOnIdle { assertSame(QrImage.Loading, observed) }
        // This remains actionable while encoding has not finished.
        compose.onNodeWithText("Next routine").performClick()
        compose.waitUntil(5_000) { firstCancelled.get() }
        compose.runOnIdle {
            assertSame(QrImage.Loading, observed)
            secondGate.complete(Unit)
        }
        compose.waitUntil(5_000) { observed is QrImage.Rendered }
        compose.runOnIdle {
            assertTrue(offMain.get())
            assertSame(secondBitmap, (observed as QrImage.Rendered).bitmap)
            firstGate.complete(Unit)
        }
        compose.runOnIdle { assertSame(secondBitmap, (observed as QrImage.Rendered).bitmap) }
    }

    @Test fun generatedRoutineAndDenseCodesStillDecodeExactly() {
        val payloads = listOf(
            assertNotNull(RoutineShare.url(RoutineDraft.starter)),
            "https://nuri.run/routine#" + "abcdefghij".repeat(100),
        )
        payloads.forEach { payload ->
            val bitmap = assertNotNull(renderQr(payload, 750)).asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val luminance = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance)))
            assertEquals(payload, decoded.text)
        }
    }
}
