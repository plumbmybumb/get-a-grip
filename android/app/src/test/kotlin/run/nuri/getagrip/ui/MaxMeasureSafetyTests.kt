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
import run.nuri.getagrip.ui.maxes.LiveMaxSession
import run.nuri.getagrip.ui.maxes.MaxMeasureScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The max VISIT (Android twin of the iOS `MaxesFlowUITests` visit flows and the rules in
/// `MaxMeasureView`): it reads on open with no Start, every pull is an attempt on the
/// selected hand, and the review picks, moves, deletes, corrects and saves.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaxMeasureSafetyTests {
    @get:Rule val compose = createComposeRule()

    private class World(connected: Boolean = true) {
        val client = RecordingProgressorClient()
        val clock = FakeClock()
        val device = DeviceStore(client = client, scope = inertScope(), clock = clock)
        private var sampleIndex = 0
        init { if (connected) client.setState(ProgressorConnectionState.Connected) }
        fun sample(kg: Double) {
            sampleIndex += 1
            clock.uptime = 10.0 + sampleIndex / 80.0
            clock.wall = 1_000.0 + sampleIndex / 80.0
            client.emit(ProgressorEvent.Sample(ForceSample(kg, (sampleIndex * 12_500).toUInt())))
        }
        /// A pull at `kg`, then long enough off the edge (1.125 s of device time) to log it.
        fun pull(kg: Double) {
            repeat(4) { sample(kg) }
            repeat(90) { sample(0.2) }
        }
    }

    private fun show(
        world: World,
        side: Side = Side.left,
        largeText: Boolean = false,
        savedMax: (Side) -> Double? = { null },
        session: LiveMaxSession? = null,
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
                    if (session != null) {
                        MaxMeasureScreen(GripSpec(), initialSide = side, onSave = onSave, onClose = onClose,
                            session = session, savedMax = savedMax)
                    } else {
                        MaxMeasureScreen(GripSpec(), initialSide = side, onSave = onSave, onClose = onClose,
                            savedMax = savedMax)
                    }
                }
            }
        }
    }

    private fun receipt(values: List<MaxMeasurementResult>) = TemplateStore.MaxSaveReceipt(
        values = values.map { TemplateStore.MaxSave(GripSpec(), it.side, it.kg, it.source) },
        percentMoves = emptyList(), rescaleOffers = emptyList(),
    )

    private fun click(tag: String) {
        // Scrolled into view where the layout scrolls (large text); most of the visit does not.
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(50)
    }

    private fun pull(world: World, kg: Double) {
        compose.runOnIdle { world.pull(kg) }
        compose.mainClock.advanceTimeBy(50)
    }

    private fun handValue(side: String, value: String) {
        compose.onNodeWithTag("max.measure.$side")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    private fun saving(side: String, text: String) {
        compose.onNodeWithTag("max.review.saving.$side").assertTextEquals(text)
    }

    @Test fun theVisitReadsOnOpenWithNoStartAndLogsEveryPull() {
        val world = World()
        show(world)
        compose.runOnIdle {
            assertTrue(world.device.isStreaming, "The visit reads the moment it opens")
            assertTrue(ProgressorCommand.startWeightMeasurement in world.client.commands)
            assertTrue(world.device.onTracePoint != null)
        }
        compose.onNodeWithTag("max.measure.start").assertDoesNotExist()
        compose.onNodeWithContentDescription("No pull yet").assertExists()
        compose.onNodeWithTag("max.measure.save").assertIsNotEnabled()
        captureReview("android-visit-open.png")
        pull(world, 30.0)
        pull(world, 34.0)
        pull(world, 32.0)
        handValue("left", "34.0 kilograms, best of 3 pulls")
        handValue("right", "Not measured")
        compose.onNodeWithContentDescription("Last pull 32.0 kilograms").assertExists()
        compose.onNodeWithText("Review 3 pulls").assertIsDisplayed().assertIsEnabled()
        captureReview("android-visit-three-pulls.png")
    }

    @Test fun eachHandKeepsItsHardestPullAndSavesBoth() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, onSave = { result = it; receipt(it) })
        pull(world, 37.0)
        pull(world, 39.0)
        click("max.measure.right")
        compose.onNodeWithTag("max.measure.right").assertIsSelected()
        compose.onNodeWithContentDescription("No pull yet").assertExists()
        pull(world, 42.0)
        handValue("left", "39.0 kilograms, best of 2 pulls")
        handValue("right", "42.0 kilograms, best of 1 pull")
        click("max.measure.save")
        saving("left", "Saves 39.0 kg.")
        saving("right", "Saves 42.0 kg.")
        compose.onNodeWithTag("max.review.save").assertTextContains("Save maxes")
        captureReview("android-review.png")
        click("max.review.save")
        compose.runOnIdle {
            assertEquals(listOf(MaxMeasurementResult(Side.left, 39.0), MaxMeasurementResult(Side.right, 42.0)), result)
        }
    }

    @Test fun handsAndTareLockWhileAPullIsUnderWay() {
        val world = World()
        show(world)
        compose.runOnIdle { repeat(4) { world.sample(28.0) } }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithTag("max.measure.left").assertIsNotEnabled()
        compose.onNodeWithTag("max.measure.right").assertIsNotEnabled()
        compose.onNodeWithTag("max.measure.tare").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Pulling, 28.0 kilograms so far").assertExists()
        compose.runOnIdle { repeat(90) { world.sample(0.2) } }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithTag("max.measure.right").assertIsEnabled()
        compose.onNodeWithTag("max.measure.tare").assertIsEnabled()
        handValue("left", "28.0 kilograms, best of 1 pull")
    }

    @Test fun reviewPicksMovesAndDeletesBeforeSaving() {
        val world = World()
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        var result: List<MaxMeasurementResult>? = null
        show(world, session = session, onSave = { result = it; receipt(it) })
        pull(world, 30.0)
        pull(world, 35.0)
        pull(world, 33.0)
        click("max.measure.save")
        val ids = session.snapshot.log.attempts.map { it.id }
        saving("left", "Saves 35.0 kg.")
        // A deliberate pick keeps a lower pull.
        click("max.review.pull.${ids[0]}")
        compose.onNodeWithTag("max.review.pull.${ids[0]}").assertIsSelected()
        saving("left", "Saves 30.0 kg.")
        // The hardest was pulled with the wrong hand selected: move it across.
        click("max.review.menu.${ids[1]}")
        compose.onNodeWithText("Move to right hand").performClick()
        compose.mainClock.advanceTimeBy(50)
        saving("left", "Saves 33.0 kg.")
        saving("right", "Saves 35.0 kg.")
        // A bad pull goes.
        click("max.review.menu.${ids[2]}")
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeBy(50)
        saving("left", "Saves 30.0 kg.")
        click("max.review.save")
        compose.runOnIdle {
            assertEquals(listOf(MaxMeasurementResult(Side.left, 30.0), MaxMeasurementResult(Side.right, 35.0)), result)
        }
    }

    @Test fun keepPullingReturnsToTheVisitAndItKeepsReading() {
        val world = World()
        show(world)
        pull(world, 30.0)
        click("max.measure.save")
        click("max.review.back")
        compose.onNodeWithTag("max.measure.dock").assertIsDisplayed()
        pull(world, 31.0)
        compose.onNodeWithText("Review 2 pulls").assertIsDisplayed()
    }

    @Test fun bothHandsTogetherSavesOneSharedValue() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, side = Side.both, onSave = { result = it; receipt(it) })
        compose.onNodeWithText("BOTH HANDS TOGETHER", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithTag("max.measure.left").assertDoesNotExist()
        compose.onNodeWithTag("max.measure.right").assertDoesNotExist()
        pull(world, 62.0)
        pull(world, 60.0)
        click("max.measure.save")
        compose.onNodeWithTag("max.review.saving.both").assertIsDisplayed()
        click("max.review.menu.1")
        compose.onNodeWithText("Move to right hand").assertDoesNotExist()
        compose.onNodeWithText("Move to left hand").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeBy(50)
        click("max.review.save")
        compose.runOnIdle { assertEquals(listOf(MaxMeasurementResult(Side.both, 60.0)), result) }
    }

    @Test fun aLostLinkClosesThePullAndLeavesItsSaveReachable() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, onSave = { result = it; receipt(it) })
        compose.runOnIdle {
            repeat(4) { world.sample(41.0) }
            world.client.setState(ProgressorConnectionState.Disconnected(null))
        }
        compose.mainClock.advanceTimeBy(50)
        handValue("left", "41.0 kilograms, best of 1 pull")
        compose.onNodeWithTag("max.measure.connect").assertIsDisplayed()
        compose.onNodeWithText("Connect your gauge to measure. Every pull counts.").assertIsDisplayed()
        compose.onNodeWithText("Try demo mode").assertDoesNotExist()
        click("max.measure.save")
        click("max.review.save")
        compose.runOnIdle { assertEquals(listOf(MaxMeasurementResult(Side.left, 41.0)), result) }
    }

    @Test fun aDisconnectedGaugeIsConnectedOnOpenAndReadsWhenItLands() {
        val world = World(connected = false)
        show(world)
        compose.runOnIdle {
            assertTrue(world.device.state.isConnected, "Opening the visit connects")
            assertTrue(world.device.isStreaming, "The first connection starts the stream")
        }
        pull(world, 25.0)
        handValue("left", "25.0 kilograms, best of 1 pull")
    }

    @Test fun pendingSaveLocksActionsAndFailureKeepsThePulls() {
        val world = World()
        val firstSave = CompletableDeferred<TemplateStore.MaxSaveReceipt?>()
        val submissions = mutableListOf<List<MaxMeasurementResult>>()
        show(world, onSave = {
            submissions += it
            if (submissions.size == 1) firstSave.await() else receipt(it)
        })
        pull(world, 23.0)
        click("max.measure.right")
        pull(world, 27.0)
        click("max.measure.save")
        click("max.review.save")
        compose.onNodeWithTag("max.review.save").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("max.measure.adjust").assertIsNotEnabled()
        compose.onNodeWithTag("max.review.back").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            firstSave.complete(null)
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("max.review.saveFailed").assertIsDisplayed()
        saving("left", "Saves 23.0 kg.")
        saving("right", "Saves 27.0 kg.")
        click("max.review.save")
        compose.runOnIdle {
            assertEquals(2, submissions.size)
            assertEquals(submissions[0], submissions[1])
        }
    }

    @Test fun correctionCancelAndApplyKeepOwnershipAndTheRawPeak() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, onSave = { result = it; receipt(it) })
        pull(world, 31.5)
        click("max.measure.right")
        pull(world, 42.5)
        click("max.measure.save")
        click("max.measure.adjust")
        compose.onNodeWithContentDescription("Left hand, 31.5 kg. Double tap to type a value.")
            .performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("49.2")
        compose.onNodeWithTag("max.adjust.cancel").performClick()
        compose.mainClock.advanceTimeBy(50)
        saving("left", "Saves 31.5 kg.")
        click("max.measure.adjust")
        compose.onNodeWithContentDescription("Left hand, 31.5 kg. Double tap to type a value.")
            .performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("29.3")
        compose.onNodeWithTag("max.adjust.apply").performClick()
        compose.mainClock.advanceTimeBy(100)
        saving("left", "Saves 29.3 kg, adjusted by hand.")
        saving("right", "Saves 42.5 kg.")
        click("max.review.save")
        compose.runOnIdle {
            assertEquals(listOf(MaxMeasurementResult(Side.left, 29.3, MaxSource.manual),
                                MaxMeasurementResult(Side.right, 42.5)), result)
        }
    }

    @Test fun reconnectInvalidatesTarePrompt() {
        val world = World()
        show(world)
        compose.runOnIdle { world.sample(1.5) }
        compose.onNodeWithTag("max.measure.tare").assertTextContains("Tare")
        click("max.measure.tare")
        compose.onNodeWithText("Zero the gauge?").assertIsDisplayed()
        compose.runOnIdle {
            world.client.setState(ProgressorConnectionState.Disconnected(null))
            world.client.setState(ProgressorConnectionState.Connected)
            world.device.startStreaming(StreamStartCause.reconnect)
            world.sample(1.5)
        }
        compose.onAllNodesWithText("Tare").filterToOne(hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { assertEquals(0, world.client.commands.count { it == ProgressorCommand.tare }) }
        compose.onNodeWithText("Zero the gauge?").assertDoesNotExist()
    }

    @Test fun largeTextKeepsTareAndReviewReachable() {
        val world = World()
        var result: List<MaxMeasurementResult>? = null
        show(world, side = Side.right, largeText = true, onSave = { result = it; receipt(it) })
        compose.onNodeWithTag("max.measure.tare").performScrollTo().assertIsDisplayed()
        click("max.measure.left")
        pull(world, 23.0)
        click("max.measure.save")
        click("max.review.save")
        compose.runOnIdle { assertEquals(listOf(MaxMeasurementResult(Side.left, 23.0)), result) }
    }

    @Test fun cancellationNeverSubmitsAndReleasesTheStream() {
        val world = World()
        val visible = mutableStateOf(true)
        var submissions = 0
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides world.device) {
                GetAGripTheme {
                    if (visible.value) MaxMeasureScreen(GripSpec(),
                        onSave = { submissions += 1; receipt(it) }, onClose = { visible.value = false },
                        savedMax = { null })
                }
            }
        }
        compose.runOnIdle { repeat(4) { world.sample(30.0) } }
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
        val output = java.io.File("../../build/review/max-visit/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
