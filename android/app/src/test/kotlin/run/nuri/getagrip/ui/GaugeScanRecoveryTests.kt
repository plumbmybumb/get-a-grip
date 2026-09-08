// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.ui.gauge.GaugeScreen
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GaugeScanRecoveryTests {
    @get:Rule val compose = createComposeRule()
    private lateinit var scope: CoroutineScope

    @After fun cancelStoreWatchdogs() {
        if (::scope.isInitialized) scope.cancel()
    }

    private fun showSearchingGauge(kind: GaugeKind): RecordingProgressorClient {
        val client = RecordingProgressorClient(kind)
        scope = inertScope()
        val device = DeviceStore(client = client, scope = scope, clock = FakeClock())
        client.setState(ProgressorConnectionState.Scanning)
        compose.setContent {
            CompositionLocalProvider(LocalDeviceStore provides device) {
                GetAGripTheme { GaugeScreen() }
            }
        }
        return client
    }

    @Test fun silentBroadcastScanCanBeCancelledAndRetriedWithoutLeavingTheScreen() {
        val client = showSearchingGauge(GaugeKind.whc06)
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertIsEnabled()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = java.io.File("../../build/review/whc06-hotfix").apply { mkdirs() }
        java.io.File(directory, "android-cancel-search.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(ProgressorConnectionState.Disconnected(), client.state)
        }
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Connect gauge").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ProgressorConnectionState.Connected, client.state) }
        compose.onNodeWithText("Disconnect").assertIsDisplayed()
        compose.onNodeWithText("Start measuring").assertIsDisplayed()
    }

    @Test fun progressorSearchRetainsItsExistingDisabledSearchingAction() {
        val client = showSearchingGauge(GaugeKind.progressor)
        compose.onNodeWithText("Searching…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(ProgressorConnectionState.Scanning, client.state) }
    }

    @Test fun genericGattSearchRetainsItsExistingDisabledSearchingAction() {
        val client = showSearchingGauge(GaugeKind.entralpi)
        compose.onNodeWithText("Searching…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(ProgressorConnectionState.Scanning, client.state) }
    }
}
