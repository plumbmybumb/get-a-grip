// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.runner

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Matrix
import android.os.Build
import android.view.RoundedCorner
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.max

/** All coordinates are in the app window, as returned by WindowInsets. */
internal data class ReleaseCorner(
    val position: Int,
    val radius: Float,
    val centerX: Float,
    val centerY: Float,
)

internal class ReleaseWindowGeometry(
    val displayOutline: Path?,
    val corners: List<ReleaseCorner>,
    val displayFrame: ReleaseDisplayFrame? = null,
)

/** Logical display pixels, relative to the window. Never the smaller content/plot bounds. */
internal data class ReleaseDisplayFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun bounds() = RectF(left, top, left + width, top + height)
}

private val releaseCornerPositions = intArrayOf(
    RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
    RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT,
)

/** Called only when the cached drawing geometry is invalidated, never per sensor sample. */
internal fun releaseWindowGeometry(
    insets: WindowInsets?, displayFrame: ReleaseDisplayFrame? = null,
): ReleaseWindowGeometry {
    val displayOutline = if (Build.VERSION.SDK_INT >= 34) {
        // The platform may return a cached Path. Own a copy before translating or clipping.
        // An unset DisplayShape can contain an empty SVG specification. Some platform
        // implementations reject it instead of returning an empty Path; rounded corners
        // remain usable in that case. Do not suppress unrelated rendering failures.
        try { insets?.displayShape?.path?.let(::Path) }
        catch (_: IllegalArgumentException) { null }
    } else null
    val corners = releaseCornerPositions.asSequence().mapNotNull { position ->
        insets?.getRoundedCorner(position)?.takeIf { it.radius > 0 }?.let { corner ->
            val center = corner.center
            ReleaseCorner(position, corner.radius.toFloat(), center.x.toFloat(), center.y.toFloat())
        }
    }.toList()
    return ReleaseWindowGeometry(displayOutline, corners, displayFrame)
}

/**
 * Prefer Android 14's actual display path. Older versions expose a documented quarter-
 * circle approximation; preserve each circle's reported center, including partial windows.
 * AOSP can supply a default rectangular DisplayShape when an OEM omits its shape config,
 * so use its rounded-corner data in that case rather than erase the known curvature.
 */
internal fun releaseScreenOutline(
    width: Float,
    height: Float,
    windowX: Float,
    windowY: Float,
    geometry: ReleaseWindowGeometry,
): Path {
    if (width <= 0f || height <= 0f) return Path()
    val window = Path().apply { addRect(0f, 0f, width, height, Path.Direction.CW) }
    val display = geometry.displayOutline?.takeUnless { it.isEmpty }?.let { source ->
        val sourceBounds = RectF().also { source.computeBounds(it, true) }
        val frame = geometry.displayFrame?.takeIf { it.width > 0f && it.height > 0f }
        if (frame != null && !sourceBounds.isEmpty) {
            val scaleX = frame.width / sourceBounds.width()
            val scaleY = frame.height / sourceBounds.height()
            // A folded/partitioned display may expose a maximum window smaller than the
            // whole physical panel. That is a crop, not a new aspect ratio for its corners.
            // Only reconcile a resolution scale; use window-relative rounded corners for
            // contradictory shape dimensions instead of stretching the physical outline.
            if (!scaleX.isFinite() || !scaleY.isFinite() ||
                abs(scaleX - scaleY) > max(scaleX, scaleY) * 0.01f) return@let null
        }
        Path(source).apply {
            // Some OEMs expose a native-resolution DisplayShape at the wrong logical
            // scale (OnePlus Android 16: the right/bottom edges landed inside the app).
            // Fit the complete display path to the actual logical DISPLAY frame first;
            // fitting to the canvas would stretch curves in split-screen or inset views.
            // WindowInsets already shifts the path for a window offset. Normalizing its
            // whole extent also reconciles that offset before the local canvas crop.
            frame?.let {
                if (!sourceBounds.isEmpty && sourceBounds != frame.bounds()) {
                    transform(Matrix().apply {
                        setRectToRect(sourceBounds, frame.bounds(), Matrix.ScaleToFit.FILL)
                    })
                }
            }
            offset(-windowX, -windowY)
        }
    }
    if (display != null && (!display.isRect(RectF()) || geometry.corners.isEmpty())) {
        return display.apply { op(window, Path.Op.INTERSECT) }
    }

    val outline = Path(window)
    for (corner in geometry.corners) {
        val radius = corner.radius
        if (radius <= 0f) continue
        val x = corner.centerX - windowX
        val y = corner.centerY - windowY
        val quadrant = when (corner.position) {
            RoundedCorner.POSITION_TOP_LEFT -> RectF(x - radius, y - radius, x, y)
            RoundedCorner.POSITION_TOP_RIGHT -> RectF(x, y - radius, x + radius, y)
            RoundedCorner.POSITION_BOTTOM_RIGHT -> RectF(x, y, x + radius, y + radius)
            RoundedCorner.POSITION_BOTTOM_LEFT -> RectF(x - radius, y, x, y + radius)
            else -> continue
        }
        val reachesEdge = when (corner.position) {
            RoundedCorner.POSITION_TOP_LEFT -> quadrant.left <= 0f && quadrant.top <= 0f
            RoundedCorner.POSITION_TOP_RIGHT -> quadrant.right >= width && quadrant.top <= 0f
            RoundedCorner.POSITION_BOTTOM_RIGHT -> quadrant.right >= width && quadrant.bottom >= height
            RoundedCorner.POSITION_BOTTOM_LEFT -> quadrant.left <= 0f && quadrant.bottom >= height
            else -> false
        }
        // Bad OEM corner coordinates must not punch a quarter-circle hole inside the
        // graph. A physical corner can affect this canvas only at its corresponding edge.
        if (!reachesEdge) continue
        // Subtract just the portion outside this corner's circle. Intersecting the final
        // shape with the app bounds naturally creates straight edges at split-window seams.
        val outsideCorner = Path().apply { addRect(quadrant, Path.Direction.CW) }
        val circle = Path().apply { addCircle(x, y, radius, Path.Direction.CW) }
        outsideCorner.op(circle, Path.Op.DIFFERENCE)
        outline.op(outsideCorner, Path.Op.DIFFERENCE)
    }
    if (display != null) outline.op(display, Path.Op.INTERSECT)
    return outline
}
