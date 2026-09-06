// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.builder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates

/** Scroll destinations measured only when needed, independent of viewport movement. */
internal class BuilderScrollAnchors {
    private var content: LayoutCoordinates? = null
    private val targets = mutableMapOf<Any, LayoutCoordinates>()

    fun contentPlaced(coordinates: LayoutCoordinates) {
        content = coordinates
    }

    fun placed(key: Any, coordinates: LayoutCoordinates) {
        targets[key] = coordinates
    }

    fun offset(key: Any): Int? {
        val document = content?.takeIf { it.isAttached } ?: return null
        val target = targets[key]?.takeIf { it.isAttached } ?: return null
        return document.localPositionOf(target, Offset.Zero).y.toInt().coerceAtLeast(0)
    }
}
