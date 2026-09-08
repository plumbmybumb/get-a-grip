// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.maxes.MaxHandPicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.ui.components.*
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButtonMarginTests {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedButtonsGiveFrenchLabelsConsistentInsetsAtTwiceTheTextSize() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                GetAGripTheme {
                    Column(Modifier.fillMaxSize().background(LocalGripPalette.current.field)
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton("Enregistrer et terminer", Modifier.testTag("primary")) {}
                        SecondaryButton("Scanner une routine partagée", Modifier.fillMaxWidth().testTag("secondary"),
                            icon = Icons.Outlined.QrCodeScanner) {}
                        Chip("Alterner à chaque tirage", true, Modifier.fillMaxWidth().testTag("chip")) {}
                        HoldToDiscardButton(Modifier.testTag("discard")) {}
                    }
                }
            }
        }
        for ((tag, label) in listOf("primary" to "Enregistrer et terminer",
            "secondary" to "Scanner une routine partagée", "chip" to "Alterner à chaque tirage",
            "discard" to "Hold to discard")) {
            compose.onNodeWithTag(tag).performScrollTo()
            assertPadded(tag, label)
        }
        compose.onNodeWithTag("primary").performScrollTo()
        capture("android-shared-controls-french-2x.png")
    }

    @Test fun holdingAndCancellingDoNotMoveOrResizeTheButtonWhenTheLabelWraps() {
        compose.mainClock.autoAdvance = false
        compose.setContent { GetAGripTheme {
            Box(Modifier.width(106.dp)) { HoldToEndButton(Modifier.testTag("hold")) {} }
        } }
        compose.mainClock.advanceTimeByFrame()
        val before = compose.onNodeWithTag("hold").fetchSemanticsNode().boundsInRoot
        assertPadded("hold", "Hold to end")
        compose.onNodeWithTag("hold").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        assertPadded("hold", "Keep holding…")
        assertEquals(before, compose.onNodeWithTag("hold").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("hold").performTouchInput { cancel() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(before, compose.onNodeWithTag("hold").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun scrollingFromTheEndButtonCancelsTheHoldButStationaryHoldStillWorks() {
        var ended = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { GetAGripTheme {
            Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                HoldToEndButton(Modifier.testTag("hold")) { ended++ }
                Spacer(Modifier.height(900.dp))
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("hold").performTouchInput {
            down(center)
            moveBy(Offset(0f, -22f), delayMillis = 200)
        }
        compose.mainClock.advanceTimeBy(1_100)
        compose.runOnIdle { assertEquals(0, ended, "A scroll over End must never abort the session") }
        compose.onNodeWithTag("hold").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("hold").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_100)
        compose.runOnIdle { assertEquals(1, ended, "A deliberate stationary hold still ends the session") }
        compose.onNodeWithTag("hold").performTouchInput { up() }
    }

    @Test fun longFrenchActionsMoveToWiderRowsBeforeSplittingWords() {
        val labels = listOf("Passer le tirage", "Passer la série", "Tenir pour finir")
        compose.setContent { GetAGripTheme {
            Column(Modifier.fillMaxWidth().padding(20.dp).background(LocalGripPalette.current.field)) {
                AdaptiveActionRow(labels.map { listOf(it) }) { index, cell ->
                    SecondaryButton(labels[index], cell.testTag("action-$index")) {}
                }
            }
        } }
        labels.forEachIndexed { index, label -> assertPadded("action-$index", label) }
        capture("android-french-action-margins.png")
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h1100dp-mdpi")
    fun frenchGripAndHandChoicesKeepCompleteLabelsAndPadding() {
        val previousLookup = L10n.lookup
        val context = ApplicationProvider.getApplicationContext<Context>()
        L10n.lookup = { context.tr(it) }
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    GetAGripTheme {
                        Column(Modifier.fillMaxSize().background(LocalGripPalette.current.field)
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            PositionChipRow(GripPosition.halfCrimp, Modifier.testTag("positions")) {}
                            HandModeChipRow(HandMode.alternateEachRep, Modifier.testTag("sequence")) {}
                            MaxHandPicker(Side.left, {}, Modifier.testTag("max-hand"))
                        }
                    }
                }
            }
            val groups = listOf("positions" to GripPosition.known.map { it.name },
                "sequence" to HandMode.entries.map { it.displayName },
                "max-hand" to listOf(context.tr("Both hands"), context.tr("Left hand"), context.tr("Right hand")))
            for ((group, labels) in groups) for (label in labels) {
                val control = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag(group)))
                control.performScrollTo()
                assertPadded(control, label, group)
            }
            compose.onNode(hasText(GripPosition.halfCrimp.name) and hasClickAction()).performScrollTo()
            capture("android-grip-hand-controls-french-large.png")
        } finally { L10n.lookup = previousLookup }
    }

    private fun assertPadded(tag: String, label: String) {
        assertPadded(compose.onNodeWithTag(tag), label)
    }

    private fun assertPadded(control: SemanticsNodeInteraction, label: String, group: String? = null) {
        val outer = control.fetchSemanticsNode().boundsInRoot
        val text = compose.onNode(hasText(label) and (group?.let { hasAnyAncestor(hasTestTag(it)) }
            ?: SemanticsMatcher("any group") { true }), useUnmergedTree = true)
        val inner = text.fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        val layout = layouts.single()
        // Text's semantics can retain the wider parent paragraph used for centering a
        // wrap-content label. Check the ink span and complete text, not that paragraph width.
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= inner.width + 1f,
                "$label has no clipped glyphs")
            assertTrue(layout.getLineBottom(line) <= inner.height + 1f, "$label has no clipped lines")
        }
        assertTrue(inner.left - outer.left >= 15.5f, "$label has left inset ${inner.left - outer.left}")
        assertTrue(outer.right - inner.right >= 15.5f, "$label has right inset ${outer.right - inner.right}")
        assertTrue(inner.top - outer.top >= 9.5f, "$label has top inset ${inner.top - outer.top}")
        assertTrue(outer.bottom - inner.bottom >= 9.5f, "$label has bottom inset ${outer.bottom - inner.bottom}")
    }

    private fun capture(name: String) {
        val path = File("../../build/review/margins/$name").canonicalFile
        path.parentFile?.mkdirs()
        path.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
