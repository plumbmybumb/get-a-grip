// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/** The measured floating bar, including the system gesture inset. Scroll containers
 * put this INSIDE their content padding so content can pass behind the rounded bar;
 * fixed controls use it as bottom padding to remain reachable above the bar.
 * Zero outside the tab host, including previews and full-screen training flows.
 */
val LocalFloatingTabBarInset = compositionLocalOf { 0.dp }
