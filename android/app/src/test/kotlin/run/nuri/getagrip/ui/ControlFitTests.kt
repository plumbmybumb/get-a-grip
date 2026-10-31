// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.builder.keepUnitsAttached
import run.nuri.getagrip.ui.components.AdaptiveActionRow
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.components.EqualCellsOrStack
import run.nuri.getagrip.ui.components.HouseSegmentedRow
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.balancedColumns
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// The 2026-10-31 large-text audit: a control's label never wraps or truncates inside its own
/// shape — the LAYOUT gives way instead (fewer columns, a stacked form).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlFitTests {
    @get:Rule val compose = createComposeRule()

    @Test fun chipRowsBalanceInsteadOfLeavingAnOrphan() {
        assertEquals(2, balancedColumns(4, 3)) // "1 2 3" + "4" became 2 × 2
        assertEquals(4, balancedColumns(4, 6))
        assertEquals(3, balancedColumns(6, 4)) // 3 + 3, not 4 + 2
        assertEquals(3, balancedColumns(5, 4)) // 3 + 2, not 4 + 1
        assertEquals(1, balancedColumns(3, 1))
        assertEquals(1, balancedColumns(0, 3))
    }

    @Test fun aWrappedCaptionNeverStrandsItsUnit() {
        val nbsp = ' '
        assertEquals("Routine target: 18–22${nbsp}%, so L${nbsp}3.5–4.5 · R${nbsp}4.0–5.0${nbsp}kg.",
            keepUnitsAttached("Routine target: 18–22 %, so L 3.5–4.5 · R 4.0–5.0 kg."))
        // Words that merely START with a unit's letters keep their ordinary spaces.
        assertEquals("a kgs b", keepUnitsAttached("a kgs b"))
    }

    @Test fun dockLabelsDropToFewerColumnsRatherThanWrapping() {
        val labels = listOf("Skip pull", "Skip set", "Hold to end")
        show(1.3f) {
            AdaptiveActionRow(labels.map { listOf(it) }) { index, cell ->
                SecondaryButton(labels[index], cell) {}
            }
        }
        labels.forEach { assertOneLine(it) }
    }

    @Test fun segmentsKeepTheirRowWhileTheLabelsFit() {
        val labels = listOf("Out of range", "Below range", "Never")
        show(1f) { HouseSegmentedRow(labels, 0) {} }
        labels.forEach { assertOneLine(it) }
        assertEquals(1, labels.map { top(it) }.distinct().size, "one row at standard text")
    }

    @Test fun segmentsBecomeWholeChipsWhenALabelWouldTruncate() {
        val labels = listOf("Out of range", "Below range", "Never")
        show(2f) { HouseSegmentedRow(labels, 0) {} }
        labels.forEach { assertOneLine(it) }
        assertTrue(labels.map { top(it) }.distinct().size > 1, "the chips wrapped to more rows")
    }

    @Test fun equalCellsStackWhenALabelWouldWrap() {
        show(1f, width = 300.dp) {
            EqualCellsOrStack { Chip("Kilograms", true) {}; Chip("Pounds", false) {} }
        }
        assertEquals(top("Kilograms"), top("Pounds"))
    }

    @Test fun equalCellsStackAtLargeText() {
        show(2f, width = 300.dp) {
            EqualCellsOrStack { Chip("Kilograms", true) {}; Chip("Pounds", false) {} }
        }
        assertOneLine("Kilograms")
        assertTrue(top("Pounds") > top("Kilograms"))
    }

    @Test fun bodyWeightTitleMovesAboveTheFieldRatherThanWrapping() {
        show(2f, width = 280.dp) {
            run.nuri.getagrip.ui.components.BodyWeightField(kilograms = null, onChange = {},
                titleStyle = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        }
        assertOneLine("Body weight")
    }

    @Test fun bodyWeightStaysOnOneRowAtStandardText() {
        show(1f, width = 280.dp) {
            run.nuri.getagrip.ui.components.BodyWeightField(kilograms = 72.0, onChange = {})
        }
        assertOneLine("Body weight")
        val title = compose.onNode(hasText("Body weight"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val unit = compose.onNode(hasText("kg"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(unit.top < title.bottom, "the field sits beside the title")
    }

    private fun show(fontScale: Float, width: Dp? = null, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GetAGripTheme {
                    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()) { content() }
                }
            }
        }
    }

    private fun top(label: String): Float =
        compose.onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun assertOneLine(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(label), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount, "$label stays on one line")
        assertFalse(layout.isLineEllipsized(0), "$label is not truncated")
        assertEquals(label.length, layout.getLineEnd(0))
    }
}
