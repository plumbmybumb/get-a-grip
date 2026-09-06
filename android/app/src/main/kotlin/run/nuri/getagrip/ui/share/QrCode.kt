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
/// Two things follow from that and neither is negotiable. **The platter is white and the
/// modules are dark in BOTH colour schemes** — a code is read by a camera, and contrast is
/// the whole feature; an adaptive fill that inverted in dark mode would hand someone a
/// picture that no longer scans. And the bitmap is drawn with `FilterQuality.None`, because
/// a QR is a bitmap of hard squares: any smoothing rounds module edges into grey and costs
/// a decoder the very contrast it is looking for.
///
/// TRANSLATION NOTE (from `Sources/UI/Components/QRCodeView.swift`): CoreImage's generator
/// emits one PIXEL per module and iOS blows it up 10× so the downstream resample has ten
/// source pixels per module to choose from. ZXing hands back the same one-pixel-per-module
/// `BitMatrix`, so the same trick applies — but the scale is COMPUTED here rather than
/// fixed at 10, because the module count varies by an order of magnitude with the payload
/// and a fixed multiplier either wastes memory on a small code or under-samples a big one.
/// `QrModules` is that arithmetic, pure and tested on the JVM.

/// Everything about a code's SIZE, with no bitmap and no Android in it — so the three rules
/// that actually matter (the quiet zone exists, the blow-up is an integer, the bitmap is
/// bounded) are pinned by a test rather than by looking at a screenshot.
object QrModules {

    /// The white margin the QR specification itself asks for, in modules, on every side. A
    /// code with no quiet zone is not "a tight code", it is a code a decoder cannot FIND:
    /// the finder patterns are located by their light surround. Four is the spec's own
    /// number and ZXing's default; it is stated here because the drawn platter's padding is
    /// a separate, purely visual margin and the two must not be confused for each other.
    const val quietZone = 4

    /// Modules across the whole drawn bitmap — the code plus its quiet zone on both sides.
    fun canvas(codeModules: Int): Int = maxOf(1, codeModules) + 2 * quietZone

    /// The INTEGER blow-up from one pixel per module to the drawn bitmap.
    ///
    /// Integer is the whole point: at a fractional scale two adjacent modules land on a
    /// different number of pixels, so the grid the decoder is measuring against is uneven
    /// before any resampling has even happened. Flooring means the bitmap is a little
    /// SMALLER than the target, which the drawn size then scales up — with no filtering, so
    /// a whole module still maps to a whole block.
    ///
    /// Clamped at both ends: never 0 (a dense code against a small target would otherwise
    /// render an empty bitmap), and never past `maximum`, since a 4 KB payload's code is
    /// already ~180 modules wide and an unbounded multiplier is how a share sheet allocates
    /// tens of megabytes to draw a square.
    fun scale(canvasModules: Int, targetPixels: Int, maximum: Int = 20): Int {
        if (canvasModules <= 0) return 1
        return (targetPixels / canvasModules).coerceIn(1, maxOf(1, maximum))
    }

    /// The bitmap's side in pixels. Exact, by construction — the scale is an integer.
    fun pixels(canvasModules: Int, scale: Int): Int = maxOf(1, canvasModules) * maxOf(1, scale)
}

/// The routine as a code. `payload` is `RoutineShare.url(draft)` verbatim — the code IS the
/// routine, so nothing here shortens, wraps or prettifies it.
@Composable
fun QrCodeView(
    payload: String,
    modifier: Modifier = Modifier,
    /// The DRAWN code, before the platter's own margin.
    size: Dp = 236.dp,
) {
    val density = LocalDensity.current
    val targetPixels = with(density) { size.roundToPx() }

    // Encoding and bitmap allocation must not hold up the sheet's first frame or its
    // Cancel/Share actions. A replaced payload starts fresh and cancels old publication.
    val rendered = rememberQrImage(payload, targetPixels)
    val image = (rendered as? QrImage.Rendered)?.bitmap

    Box(
        modifier
            .clip(RoundedCornerShape(Metrics.radiusInner))
            // FIXED white, never `palette.card`: see the header. The hairline is a fixed
            // dark too — an adaptive ink would resolve near-white on white and the edge
            // would vanish exactly where the surface needs one.
            .background(Color.White)
            .border(1.dp, Color.Black.copy(alpha = 0.10f), RoundedCornerShape(Metrics.radiusInner))
            // The platter's own margin, on TOP of the four-module quiet zone baked into the
            // bitmap. Generous rather than minimal: this platter sits on the app's slate
            // field, where a code drawn to its own edge reads as a texture rather than as a
            // thing to point a camera at.
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
                // NEVER interpolate. See the header — this is the one line that decides
                // whether the picture is scannable.
                filterQuality = FilterQuality.None,
            )
        } else {
            // Generation failing is not expected for a routine of ordinary size, but a
            // payload can outgrow what a QR can hold at all (the share format allows 4 KB
            // compressed; a level-M code tops out near 1.6 KB). A blank white square would
            // read as a code that simply did not scan. Fixed ink, same reason as the
            // hairline.
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
    // Remember by request rather than reusing produceState's previous value: a newly
    // selected routine must never flash the previous routine's scannable code.
    val result = remember(payload, targetPixels) { mutableStateOf<QrImage>(QrImage.Loading) }
    LaunchedEffect(payload, targetPixels) {
        result.value = withContext(Dispatchers.Default) {
            QrImage.Rendered(render(payload, targetPixels))
        }
    }
    return result.value
}

/// Level **M** — 15 % recovery. "L" would fit a longer payload into fewer modules, but a
/// code that gets shared as a screenshot, printed, or read across a room at an angle needs
/// the redundancy more than it needs the density. Same setting as the iOS generator, so the
/// two apps produce codes of the same robustness.
internal fun renderQr(payload: String, targetPixels: Int): ImageBitmap? = runCatching {
    if (payload.isEmpty()) return null
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to QrModules.quietZone,
        // The payload is a scheme plus base64url, so it is ASCII either way; stated so the
        // writer never guesses a platform default that would change the bytes a scanner
        // reads back.
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // Width and height of 1 ask `QRCodeWriter` for its NATURAL size: it takes the max of the
    // request and the code's own module count, so this returns exactly one pixel per module
    // with the quiet zone already included. Asking for pixels here instead would hand the
    // scaling to ZXing, which floors the multiple and then CENTRES the result inside the
    // requested rectangle — an off-grid code, which is precisely what must not happen.
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

/// Opaque black on opaque white, as literals. The modules are the measurement; nothing
/// about them may follow the colour scheme.
private const val DARK = 0xFF000000.toInt()
private const val LIGHT = 0xFFFFFFFF.toInt()
