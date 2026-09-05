// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original Android vector: a climber reaching along a rope, with bent legs braced out.
 * Matches the activity represented by iOS's figure.climbing, using a 24dp tab-icon grid.
 * Cached once; the native Icon supplies selection tint and accessibility stays on the tab.
 */
val ClimbingIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Climbing", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 1f)
            lineTo(12f, 22.5f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 6.5f)
            arcToRelative(1.85f, 1.85f, 0f, true, true, -3.7f, 0f)
            arcToRelative(1.85f, 1.85f, 0f, true, true, 3.7f, 0f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.7f,
            strokeLineCap = StrokeCap.Round) {
            moveTo(15.8f, 10f)
            lineTo(12.2f, 14.3f)
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            // Raised hand holds the rope; the other reaches down toward the harness.
            moveTo(15.8f, 10f)
            lineTo(12f, 6f)
            lineTo(12f, 4f)
            moveTo(16f, 10.3f)
            lineTo(17.2f, 13.5f)
            lineTo(12.5f, 17f)
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12.2f, 14.3f)
            lineTo(7.5f, 12f)
            lineTo(3.2f, 12f)
            moveTo(12.2f, 14.3f)
            lineTo(8f, 16.7f)
            lineTo(4.1f, 20.4f)
        }
    }.build()
}
