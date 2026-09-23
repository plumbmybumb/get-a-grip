// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.share

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.Metrics

/// A QR code drawn as a SCANNING SURFACE, not as chrome.
///
/// **White platter, dark modules, in BOTH schemes** — a camera reads it, and an adaptive fill
/// inverting in dark mode would not scan. **`FilterQuality.None`**, because smoothing hard
/// squares rounds module edges into grey and costs the decoder its contrast.
///
/// TRANSLATION NOTE (from `QRCodeView.swift`): CoreImage emits one pixel per module and iOS
/// blows it up 10×. ZXing's `BitMatrix` is the same, but the scale is COMPUTED here because
/// module counts vary tenfold with payload; `QrModules` is that arithmetic, JVM-tested.

/// Everything about a code's SIZE, with no Android in it, so the three rules that matter (quiet
/// zone, integer blow-up, bounded bitmap) are pinned by a test.
object QrModules {

    /// The spec's quiet zone, in modules per side (ZXing's default): finder patterns are located by
    /// their light surround, so without it a decoder cannot FIND the code. Separate from the
    /// platter's purely visual padding.
    const val quietZone = 4

    /// Modules across the whole drawn bitmap — the code plus its quiet zone on both sides.
    fun canvas(codeModules: Int): Int = maxOf(1, codeModules) + 2 * quietZone

    /// The INTEGER blow-up from one pixel per module to the drawn bitmap.
    ///
    /// A fractional scale gives adjacent modules unequal pixel counts, an uneven grid before any
    /// resampling. Flooring makes the bitmap slightly SMALLER; the unfiltered draw scales it up
    /// whole-module to whole-block.
    ///
    /// Clamped: never 0 (a dense code on a small target would be empty), never past `maximum` (a
    /// 4 KB payload is ~180 modules; unbounded is tens of megabytes for a square).
    fun scale(canvasModules: Int, targetPixels: Int, maximum: Int = 20): Int {
        if (canvasModules <= 0) return 1
        return (targetPixels / canvasModules).coerceIn(1, maxOf(1, maximum))
    }

    /// The bitmap's side in pixels. Exact, by construction — the scale is an integer.
    fun pixels(canvasModules: Int, scale: Int): Int = maxOf(1, canvasModules) * maxOf(1, scale)
}

/// The routine as a code. `payload` is `RoutineShare.url(draft)` verbatim — the code IS the
/// routine, never shortened or prettified.
@Composable
fun QrCodeView(
    payload: String,
    modifier: Modifier = Modifier,
    /// The DRAWN code, before the platter's own margin.
    size: Dp = 236.dp,
) {
    val density = LocalDensity.current
    val targetPixels = with(density) { size.roundToPx() }

    // Encoding off the first frame and the sheet's actions; a replaced payload cancels the old.
    val rendered = rememberQrImage(payload, targetPixels)
    val image = (rendered as? QrImage.Rendered)?.bitmap

    Box(
        modifier
            .clip(RoundedCornerShape(Metrics.radiusInner))
            // FIXED white, never `palette.card` (see the header). The hairline is fixed dark too: an
            // adaptive ink would vanish white-on-white.
            .background(Color.White)
            .border(1.dp, Color.Black.copy(alpha = 0.10f), RoundedCornerShape(Metrics.radiusInner))
            // The platter's margin, on TOP of the bitmap's quiet zone. Generous: on the slate field a
            // code drawn to its edge reads as texture, not a thing to point a camera at.
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (rendered == QrImage.Loading) {
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black.copy(alpha = 0.55f))
            }
        } else if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .clearAndSetSemantics { contentDescription = L10n.tr("QR code for this routine") },
                contentScale = ContentScale.Fit,
                // NEVER interpolate: this line decides whether the picture scans.
                filterQuality = FilterQuality.None,
            )
        } else {
            // A payload can outgrow any QR (the format allows 4 KB compressed; level M tops out near
            // 1.6 KB). A blank white square would read as a code that did not scan. Fixed ink.
            Text(
                tr("This code couldn't be drawn. The link below still works."),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.size(size),
            )
        }
    }
}

internal sealed interface QrImage {
    data object Loading : QrImage
    data class Rendered(val bitmap: ImageBitmap?) : QrImage
}

@Composable
internal fun rememberQrImage(
    payload: String,
    targetPixels: Int,
    render: suspend (String, Int) -> ImageBitmap? = { text, pixels -> renderQr(text, pixels) },
): QrImage {
    // Keyed by request, not produceState's previous value: a new routine must never flash the
    // previous routine's scannable code.
    val result = remember(payload, targetPixels) { mutableStateOf<QrImage>(QrImage.Loading) }
    LaunchedEffect(payload, targetPixels) {
        result.value = withContext(Dispatchers.Default) {
            QrImage.Rendered(render(payload, targetPixels))
        }
    }
    return result.value
}

/// Level **M** — 15 % recovery. "L" is denser, but a screenshotted, printed or angled code
/// needs the redundancy. Matches the iOS generator.
internal fun renderQr(payload: String, targetPixels: Int): ImageBitmap? = runCatching {
    if (payload.isEmpty()) return null
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to QrModules.quietZone,
        // The payload is ASCII either way; stated so no platform default changes the bytes.
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // 1 × 1 asks `QRCodeWriter` for its NATURAL size: one pixel per module, quiet zone included.
    // Asking for pixels would let ZXing floor the multiple and CENTRE the result — off-grid.
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 1, 1, hints)
    matrix.toBitmap(targetPixels)
}.getOrNull()

private fun BitMatrix.toBitmap(targetPixels: Int): ImageBitmap {
    val modules = width
    val scale = QrModules.scale(modules, targetPixels)
    val side = QrModules.pixels(modules, scale)
    val pixels = IntArray(side * side)
    for (y in 0 until side) {
        val row = y / scale
        val base = y * side
        for (x in 0 until side) {
            pixels[base + x] = if (this[x / scale, row]) DARK else LIGHT
        }
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/// Opaque black on opaque white, as literals: the modules never follow the colour scheme.
private const val DARK = 0xFF000000.toInt()
private const val LIGHT = 0xFFFFFFFF.toInt()
