// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import kotlin.math.roundToInt

private enum class Reveal { Closed, Share, Delete }

/** A swipe reveals a button; no distance or fling velocity can execute an action.
 * Native horizontal drag arbitration leaves vertical list scrolling alone. Anchors
 * stop at the button width, so a second swipe cannot accidentally become a delete.
 */
@Composable
fun SwipeActionRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    deleteLabel: String = tr("Delete"),
    onShare: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalGripPalette.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val state = remember { AnchoredDraggableState(Reveal.Closed) }
    val deleteText = tr("Delete")
    val shareText = tr("Share")
    val exportLabel = tr("Export workout")
    val measurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.labelLarge
    val labelWidth = maxOf(measurer.measure(AnnotatedString(deleteText), textStyle).size.width,
        if (onShare != null) measurer.measure(AnnotatedString(shareText), textStyle).size.width else 0)
    val actionWidth = with(density) { labelWidth.toDp() + 36.dp }.coerceAtLeast(88.dp)
    val widthPx = with(density) { actionWidth.toPx() }
    SideEffect {
        state.updateAnchors(DraggableAnchors {
            Reveal.Closed at 0f
            Reveal.Delete at -widthPx
            if (onShare != null) Reveal.Share at widthPx
        })
    }
    fun close() { scope.launch { state.animateTo(Reveal.Closed, Motion.state(reduceMotion)) } }
    val offset = state.offset.takeIf { it.isFinite() } ?: 0f
    val fling = AnchoredDraggableDefaults.flingBehavior(state,
        positionalThreshold = { it * 0.6f }, animationSpec = Motion.momentum(reduceMotion))

    Box(modifier.fillMaxWidth().clipToBounds().semantics(mergeDescendants = true) {
        customActions = buildList {
            add(CustomAccessibilityAction(deleteLabel) { close(); onDelete(); true })
            onShare?.let { share -> add(CustomAccessibilityAction(exportLabel) { close(); share(); true }) }
        }
    }) {
        // Only the revealed side has a button or an accessibility target.
        if (offset != 0f) {
            val sharing = offset > 0f && onShare != null
            Box(Modifier.matchParentSize(), contentAlignment = if (sharing) Alignment.CenterStart else Alignment.CenterEnd) {
                Button(
                    onClick = { close(); if (sharing) onShare?.invoke() else onDelete() },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sharing) palette.graphite else palette.alarmFlat,
                        contentColor = if (sharing) palette.graphiteInverse else Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.width(actionWidth).fillMaxHeight(),
                ) { Text(if (sharing) shareText else deleteText, style = textStyle) }
            }
        }
        Box(Modifier.absoluteOffset { IntOffset((state.requireOffset() * if (rtl) -1 else 1).roundToInt(), 0) }
            .anchoredDraggable(state = state, orientation = Orientation.Horizontal, reverseDirection = rtl, flingBehavior = fling)
            .background(palette.field)) {
            content()
            if (offset != 0f) {
                // Tap the row itself to put it away. The parent still owns dragging.
                Box(Modifier.matchParentSize().clickable(onClick = ::close).clearAndSetSemantics {})
            }
        }
    }
}
