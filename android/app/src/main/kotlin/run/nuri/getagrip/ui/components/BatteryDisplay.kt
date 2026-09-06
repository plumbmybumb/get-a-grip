// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import kotlin.math.roundToInt

object BatteryDisplay {
    fun percentage(fraction: Double): Int =
        if (fraction.isFinite()) (fraction.coerceIn(0.0, 1.0) * 100).roundToInt() else 0
}
