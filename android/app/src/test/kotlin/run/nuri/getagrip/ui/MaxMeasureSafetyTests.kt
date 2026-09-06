// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

    @Test fun reconnectInvalidatesTarePromptAndLostSignalKeepsPeakAccessible() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        device.startStreaming(StreamStartCause.initial)
        client.emit(ProgressorEvent.Sample(ForceSample(kg = 5.0, deviceMicros = 1u)))
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme { MaxMeasureScreen(GripSpec(), onMeasured = {}, onCancel = {}) }
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
}
