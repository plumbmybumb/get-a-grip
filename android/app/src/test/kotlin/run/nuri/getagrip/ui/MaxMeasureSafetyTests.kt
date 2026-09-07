// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
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
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.maxes.MaxMeasureScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaxMeasureSafetyTests {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w393dp-h820dp-mdpi")
    fun compactPhoneShowsHandAndPrimaryActionsWithoutScrolling() {
        val client = RecordingProgressorClient()
        val clock = FakeClock()
        val device = DeviceStore(client = client, scope = inertScope(), clock = clock)
        client.setState(ProgressorConnectionState.Connected)
        var result: Pair<Double, Side>? = null
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme(darkTheme = false) {
                    MaxMeasureScreen(
                        GripSpec(),
                        initialSide = Side.right,
                        onMeasured = { kg, side -> result = kg to side },
                        onCancel = {},
                    )
                }
            }
        }
        // No performScrollTo: the hand and primary action must coexist in the viewport.
        compose.onNodeWithText("Both hands").assertIsDisplayed()
        compose.onNodeWithText("Left hand").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithText("Right hand").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()
        captureReview("android-ready.png")
        compose.onNodeWithText("Start").performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle {
            repeat(321) { index ->
                val t = index / 80.0
                clock.uptime = 10.0 + t
                clock.wall = 1_000.0 + t
                val kg = when {
                    t < 1.0 -> t * 31.5
                    t < 2.5 -> 31.5
                    else -> (31.5 - (t - 2.5) * 24.0).coerceAtLeast(0.0)
                }
                client.emit(ProgressorEvent.Sample(ForceSample(kg = kg, deviceMicros = (index * 12_500).toUInt())))
            }
        }
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Left hand").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Use this max").assertIsDisplayed()
        captureReview("android-done.png")
        compose.onNodeWithText("Use this max").performClick()
        compose.runOnIdle { assertEquals(31.5 to Side.left, result) }
    }

    @Test
    @Config(qualifiers = "w393dp-h820dp-mdpi")
    fun largeTextCanScrollToHandAndMeasurementActions() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        var result: Pair<Double, Side>? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDeviceStore provides device,
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                GetAGripTheme {
                    MaxMeasureScreen(
                        GripSpec(),
                        initialSide = Side.right,
                        onMeasured = { kg, side -> result = kg to side },
                        onCancel = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Left hand").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Start").performScrollTo().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle {
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 23.0, deviceMicros = 12_500u)))
        }
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Use this max").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(23.0 to Side.left, result) }
    }

    private fun captureReview(name: String) {
        val output = java.io.File("../../build/review/max-hand/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun reconnectInvalidatesTarePromptAndLostSignalKeepsPeakAccessible() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        device.startStreaming(StreamStartCause.initial)
        client.emit(ProgressorEvent.Sample(ForceSample(kg = 5.0, deviceMicros = 1u)))
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme { MaxMeasureScreen(GripSpec(), onMeasured = { _, _ -> }, onCancel = {}) }
            }
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Zero the gauge").performScrollTo().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Zero the gauge?").assertIsDisplayed()
        compose.runOnIdle {
            client.setState(ProgressorConnectionState.Disconnected(null))
            client.setState(ProgressorConnectionState.Connected)
            device.startStreaming(StreamStartCause.reconnect)
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 5.0, deviceMicros = 2u)))
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Tare").performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { assertEquals(0, client.commands.count { it == ProgressorCommand.tare }) }
        compose.onNodeWithText("Zero the gauge?").assertDoesNotExist()

        compose.onNodeWithText("Start").performScrollTo().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle {
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 10.0, deviceMicros = 12_502u)))
            client.setState(ProgressorConnectionState.Disconnected(null))
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithContentDescription("10.0 kilograms, your hardest pull. No live reading").assertExists()
    }

    @Test fun changeHandBeforeMeasuringLocksDuringPullAndReturnsSelectedHand() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        var result: Pair<Double, Side>? = null
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme {
                    MaxMeasureScreen(
                        GripSpec(),
                        initialSide = Side.right,
                        onMeasured = { kg, side -> result = kg to side },
                        onCancel = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Right hand").assertIsSelected()
        compose.onNodeWithText("Left hand").performClick().assertIsSelected()
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Left hand").performScrollTo().assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("Right hand").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Left hand").assertIsSelected()
        compose.runOnIdle {
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 23.0, deviceMicros = 12_500u)))
        }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Left hand").assertIsEnabled().assertIsSelected()
        compose.onNodeWithText("Use this max").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(23.0 to Side.left, result) }
    }

    @Test fun finishedMeasurementCanCorrectHandEvenAfterDisconnecting() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        var result: Pair<Double, Side>? = null
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme {
                    MaxMeasureScreen(
                        GripSpec(),
                        initialSide = Side.left,
                        onMeasured = { kg, side -> result = kg to side },
                        onCancel = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle {
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 42.0, deviceMicros = 12_500u)))
        }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.runOnIdle { client.setState(ProgressorConnectionState.Disconnected(null)) }
        compose.onNodeWithText("Both hands").performScrollTo().assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithText("Use this max").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(42.0 to Side.both, result) }
    }

    @Test fun pendingSaveLocksActionsAndFailureKeepsTheResultForRetry() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        val saving = mutableStateOf(false)
        val failed = mutableStateOf(false)
        val results = mutableListOf<Pair<Double, Side>>()
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme {
                    MaxMeasureScreen(
                        GripSpec(),
                        initialSide = Side.left,
                        isSaving = saving.value,
                        saveFailed = failed.value,
                        onMeasured = { kg, side ->
                            results += kg to side
                            saving.value = true
                        },
                        onCancel = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Start").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle {
            client.emit(ProgressorEvent.Sample(ForceSample(kg = 23.0, deviceMicros = 12_500u)))
        }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithText("Use this max").performScrollTo().performClick()
        compose.onNodeWithText("Use this max").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Try again").assertIsNotEnabled()
        compose.onNodeWithText("Left hand").assertIsNotEnabled().assertIsSelected()
        compose.onNodeWithContentDescription("Cancel").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(23.0 to Side.left), results)
            saving.value = false
            failed.value = true
        }
        compose.onNodeWithText("That couldn't be saved — nothing was recorded. Try again.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Left hand").assertIsEnabled().assertIsSelected()
        compose.onNodeWithText("Use this max").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(23.0 to Side.left, 23.0 to Side.left), results) }
    }
}
