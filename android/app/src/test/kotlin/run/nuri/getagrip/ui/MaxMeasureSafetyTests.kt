// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxMeasurementResult
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.maxes.MaxMeasureScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaxMeasureSafetyTests {
    @get:Rule val compose = createComposeRule()

    private class World {
        val client = RecordingProgressorClient()
        val clock = FakeClock()
        val device = DeviceStore(client = client, scope = inertScope(), clock = clock)
        private var sampleIndex = 0
        init { client.setState(ProgressorConnectionState.Connected) }
        fun sample(kg: Double) {
            sampleIndex += 1
            clock.uptime = 10.0 + sampleIndex / 80.0
            clock.wall = 1_000.0 + sampleIndex / 80.0
            client.emit(ProgressorEvent.Sample(ForceSample(kg, (sampleIndex * 12_500).toUInt())))
        }
    }

    private fun show(
        world: World,
        side: Side = Side.left,
        largeText: Boolean = false,
        onClose: () -> Unit = {},
        onSave: suspend (List<MaxMeasurementResult>) -> TemplateStore.MaxSaveReceipt? = { null },
    ) {
        WeightUnits.current = WeightUnit.kg
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDeviceStore provides world.device,
                LocalDensity provides Density(density.density, fontScale = if (largeText) 2f else 1f),
            ) {
                GetAGripTheme(darkTheme = false) {
                    MaxMeasureScreen(GripSpec(), initialSide = side, onSave = onSave, onClose = onClose)
                }
            }
        }
    }

    private fun receipt(values: List<MaxMeasurementResult>) = TemplateStore.MaxSaveReceipt(
        values = values.map { TemplateStore.MaxSave(GripSpec(), it.side, it.kg, it.source) },
        percentMoves = emptyList(), rescaleOffers = emptyList(),
    )

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(50)
    }

    private fun pull(world: World, kg: Double) {
        click("max.measure.start")
        compose.runOnIdle { world.sample(kg) }
        click("max.measure.finish")
    }

    private fun handValue(side: String, value: String) {
        compose.onNodeWithTag("max.measure.$side")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    @Test fun compactPhoneShowsHandsAndStartTogetherAndCanSaveOnlyLeft() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, side = Side.right, onSave = { result = it; receipt(it) })
        compose.onNodeWithTag("max.measure.left").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("max.measure.right").assertIsDisplayed()
        compose.onNodeWithText("Both hands").assertDoesNotExist()
        compose.onNodeWithTag("max.measure.start").assertIsDisplayed()
        captureReview("android-ready.png")
        pull(world, 31.5)
        handValue("left", "31.5 kg, ready to save")
        handValue("right", "Not measured")
        compose.onNodeWithTag("max.measure.save").performScrollTo().assertIsDisplayed()
        captureReview("android-done.png")
        click("max.measure.save")
        compose.runOnIdle { assertEquals(listOf(MaxMeasurementResult(Side.left, 31.5)), result) }
    }

    @Test fun oneVisitKeepsDistinctHandsAndLocksOwnershipDuringEachPull() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, onSave = { result = it; receipt(it) })
        click("max.measure.start")
        compose.onNodeWithTag("max.measure.left").performScrollTo().assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("max.measure.right").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("max.measure.left").assertIsSelected()
        compose.runOnIdle { world.sample(31.5) }
        click("max.measure.finish")
        handValue("right", "Not measured")
        click("max.measure.other")
        pull(world, 42.5)
        handValue("left", "31.5 kg, ready to save")
        handValue("right", "42.5 kg, ready to save")
        compose.onNodeWithTag("max.measure.left").performScrollTo()
        captureReview("android-both-captured.png")
        click("max.measure.save")
        compose.runOnIdle {
            assertEquals(listOf(MaxMeasurementResult(Side.left, 31.5), MaxMeasurementResult(Side.right, 42.5)), result)
        }
    }

    @Test fun largeTextCanReachBothHandsAndMeasurementActions() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, side = Side.right, largeText = true, onSave = { result = it; receipt(it) })
        click("max.measure.left")
        pull(world, 23.0)
        click("max.measure.save")
        compose.runOnIdle { assertEquals(listOf(MaxMeasurementResult(Side.left, 23.0)), result) }
    }

    @Test fun reconnectInvalidatesTarePromptAndLostSignalKeepsPeakAccessible() {
        val world = World()
        world.device.startStreaming(StreamStartCause.initial)
        world.sample(5.0)
        show(world)
        click("max.measure.tare")
        compose.onNodeWithText("Zero the gauge?").assertIsDisplayed()
        compose.runOnIdle {
            world.client.setState(ProgressorConnectionState.Disconnected(null))
            world.client.setState(ProgressorConnectionState.Connected)
            world.device.startStreaming(StreamStartCause.reconnect)
            world.sample(5.0)
        }
        compose.onNodeWithText("Tare").performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { assertEquals(0, world.client.commands.count { it == ProgressorCommand.tare }) }
        compose.onNodeWithText("Zero the gauge?").assertDoesNotExist()
        click("max.measure.start")
        compose.runOnIdle {
            world.sample(10.0)
            world.client.setState(ProgressorConnectionState.Disconnected(null))
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithContentDescription("10.0 kilograms, your hardest pull. No live reading").assertExists()
        compose.onNodeWithTag("max.measure.finish").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("max.measure.save").performScrollTo().assertIsDisplayed()
    }

    @Test fun selectingAnUnmeasuredHandAfterDisconnectStillSavesOnlyTheCapturedHand() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, onSave = { result = it; receipt(it) })
        pull(world, 42.0)
        compose.runOnIdle { world.client.setState(ProgressorConnectionState.Disconnected(null)) }
        click("max.measure.right")
        handValue("right", "Not measured")
        handValue("left", "42.0 kg, ready to save")
        compose.onNodeWithTag("max.measure.save").assertIsEnabled()
        click("max.measure.save")
        compose.runOnIdle { assertEquals(listOf(MaxMeasurementResult(Side.left, 42.0)), result) }
    }

    @Test fun failedRetryKeepsBothThePreviousHeroAndOtherHand() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, onSave = { result = it; receipt(it) })
        pull(world, 31.5)
        click("max.measure.other")
        pull(world, 42.5)
        click("max.measure.left")
        click("max.measure.retry")
        pull(world, 0.4)
        handValue("left", "31.5 kg, ready to save")
        handValue("right", "42.5 kg, ready to save")
        compose.onNodeWithContentDescription("31.5 kilograms, your hardest pull. No live reading").assertDoesNotExist()
        compose.onNodeWithContentDescription("31.5 kilograms, your hardest pull").assertExists()
        click("max.measure.save")
        compose.runOnIdle {
            assertEquals(listOf(MaxMeasurementResult(Side.left, 31.5), MaxMeasurementResult(Side.right, 42.5)), result)
        }
    }

    @Test fun pendingSaveLocksActionsAndFailureKeepsBothValuesForRetry() {
        val world = World()
        val firstSave = CompletableDeferred<TemplateStore.MaxSaveReceipt?>()
        val submissions = mutableListOf<List<MaxMeasurementResult>>()
        show(world, onSave = {
            submissions += it
            if (submissions.size == 1) firstSave.await() else receipt(it)
        })
        pull(world, 23.0)
        click("max.measure.other")
        pull(world, 27.0)
        click("max.measure.save")
        compose.onNodeWithTag("max.measure.save").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("max.measure.retry").assertIsNotEnabled()
        compose.onNodeWithTag("max.measure.left").assertIsNotEnabled()
        compose.onNodeWithTag("max.measure.cancel").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            firstSave.complete(null)
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("max.measure.saveFailed").performScrollTo().assertIsDisplayed()
        handValue("left", "23.0 kg, ready to save")
        handValue("right", "27.0 kg, ready to save")
        click("max.measure.save")
        compose.runOnIdle {
            assertEquals(2, submissions.size)
            assertEquals(submissions[0], submissions[1])
        }
    }

    @Test fun correctionCancelAndToolbarApplyKeepOwnershipAndRawPeak() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, onSave = { result = it; receipt(it) })
        pull(world, 31.5)
        click("max.measure.other")
        pull(world, 42.5)
        click("max.measure.adjust")
        compose.onNodeWithContentDescription("Left hand, 31.5 kg. Double tap to type a value.")
            .performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("49.2")
        compose.onNodeWithTag("max.adjust.cancel").performClick()
        handValue("left", "31.5 kg, ready to save")
        click("max.measure.adjust")
        compose.onNodeWithContentDescription("Left hand, 31.5 kg. Double tap to type a value.")
            .performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("29.3")
        // No inline Done: Apply must commit the still-focused field first.
        compose.onNodeWithTag("max.adjust.apply").performClick()
        compose.mainClock.advanceTimeBy(100)
        handValue("left", "29.3 kg, ready to save")
        handValue("right", "42.5 kg, ready to save")
        click("max.measure.left")
        compose.onNodeWithContentDescription("31.5 kilograms, your hardest pull").assertExists()
        click("max.measure.save")
        compose.runOnIdle {
            assertEquals(listOf(MaxMeasurementResult(Side.left, 29.3, MaxSource.manual),
                                MaxMeasurementResult(Side.right, 42.5)), result)
        }
    }

    @Test fun bothHandsTogetherRequiresItsExplicitEntryMode() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, side = Side.both, onSave = { result = it; receipt(it) })
        compose.onNodeWithText("Both hands together").assertIsDisplayed()
        compose.onNodeWithTag("max.measure.left").assertDoesNotExist()
        compose.onNodeWithTag("max.measure.right").assertDoesNotExist()
        pull(world, 62.0)
        click("max.measure.save")
        compose.runOnIdle { assertEquals(listOf(MaxMeasurementResult(Side.both, 62.0)), result) }
    }

    @Test fun cancellationDuringPullNeverSubmitsAndReleasesTheStream() {
        val world = World()
        val visible = mutableStateOf(true)
        var submissions = 0
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides world.device) {
                GetAGripTheme {
                    if (visible.value) MaxMeasureScreen(GripSpec(),
                        onSave = { submissions += 1; receipt(it) }, onClose = { visible.value = false })
                }
            }
        }
        click("max.measure.start")
        compose.runOnIdle { world.sample(30.0) }
        compose.onNodeWithTag("max.measure.cancel").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertEquals(0, submissions)
            assertNull(world.device.onTracePoint)
            assertFalse(world.device.isStreaming)
            assertTrue(ProgressorCommand.stopWeightMeasurement in world.client.commands)
        }
    }

    private fun captureReview(name: String) {
        val output = java.io.File("../../build/review/max-hand/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
