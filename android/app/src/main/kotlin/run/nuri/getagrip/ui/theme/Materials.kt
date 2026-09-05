// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** Drawing inputs also exercised by contrast tests, including combined overlay extremes. */
internal data class MineralField(
    val stops: List<Color>, val light: Color, val cloud: Color, val grainAlpha: Float,
)
internal fun mineralField(dark: Boolean) = MineralField(
    stops = if (dark) listOf(Color(0xFF24292F), Color(0xFF22272E), Color(0xFF1D222A))
        else listOf(Color(0xFFE8EBEF), Color(0xFFE2E6EB), Color(0xFFDDE1E7)),
    light = Color.White.copy(alpha = if (dark) .015f else .18f),
    cloud = Color(0xFF6A7B94).copy(alpha = .025f),
    grainAlpha = .035f,
)

/** One static mineral field for the entire app. No clocks, runtime blur or per-frame noise.
 * The fine tooth is a 192px repeating tile; brushes are cached until size/theme changes.
 * The isolated drawing layer keeps sensor invalidations out of the background display list.
 * Slate, paper grain and directional light carry the iOS identity using Android drawing APIs.
 */
@Composable
fun MineralBackground(content: @Composable () -> Unit) {
    val palette = LocalGripPalette.current
    val dark = palette == DarkPalette
    val grain = remember { mineralGrain() }
    val material = remember(dark) { mineralField(dark) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().graphicsLayer().drawWithCache {
            val field = Brush.verticalGradient(material.stops)
            val light = Brush.radialGradient(
                listOf(material.light, Color.Transparent),
                center = Offset(size.width * .22f, size.height * .12f),
                radius = size.maxDimension * .75f,
            )
            val cloud = Brush.radialGradient(
                listOf(material.cloud, Color.Transparent),
                center = Offset(size.width * .94f, size.height * .56f),
                radius = size.maxDimension * .52f,
            )
            val tooth = ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
            onDrawBehind {
                drawRect(field)
                drawRect(light)
                drawRect(cloud)
                drawRect(tooth, alpha = material.grainAlpha)
            }
        })
        content()
    }
}

private fun mineralGrain(): androidx.compose.ui.graphics.ImageBitmap {
    val random = Random(7319)
    val side = 192
    val pixels = IntArray(side * side) {
        val v = random.nextInt(256)
        android.graphics.Color.rgb(v, v, v)
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Opaque tonal material: light catches the rim, and elevation separates a working surface
 * from the stone field. Uses Material Surface so native ripple, clipping and semantics stay.
 * No backdrop sampling: this is intentionally cheap during a workout and on older GPUs.
 */
@Composable
fun InstrumentSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Metrics.radiusCard),
    color: Color = LocalGripPalette.current.card,
    contentColor: Color = LocalGripPalette.current.inkPrimary,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 1.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalGripPalette.current
    val rim = remember(palette) { BorderStroke(.75.dp, Brush.verticalGradient(
        listOf(Color.White.copy(alpha = if (palette == DarkPalette) .14f else .8f),
            palette.inkPrimary.copy(alpha = .06f)),
    )) }
    Surface(modifier, shape, color, contentColor, tonalElevation, shadowElevation, border ?: rim, content)
}
