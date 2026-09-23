// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// **A foreground-service start is a promise, and the service keeps it on every path.**
///
/// `startForegroundService` obliges the service to call `startForeground` within a few
/// seconds whether or not it still wants to run. The stray path — the session ended, and
/// cleared its card, between the start request and `onStartCommand` — used to go straight to
/// `stopSelf()`, and the system answered with `ForegroundServiceDidNotStartInTimeException`,
/// which kills the process.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionForegroundServiceTests {

    @After
    fun clearPending() {
        SessionForegroundService.pending = null
    }

    @Test
    fun aStrayStartIsPromotedBeforeItStops() {
        SessionForegroundService.pending = null
        val controller = Robolectric.buildService(SessionForegroundService::class.java).create()
        controller.startCommand(0, 1)
        val shadow = shadowOf(controller.get())

        assertNotNull(shadow.lastForegroundNotification,
            "startForeground must run even when there is no card to adopt")
        assertTrue(shadow.isStoppedBySelf, "and a stray still stops at once")
    }

    @Test
    fun aRealStartAdoptsThePublishersCardAndKeepsRunning() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val card = LiveUpdateNotification.placeholder(context)
        SessionForegroundService.pending = card
        val controller = Robolectric.buildService(SessionForegroundService::class.java).create()
        controller.startCommand(0, 1)
        val shadow = shadowOf(controller.get())

        assertTrue(shadow.lastForegroundNotification === card)
        assertTrue(!shadow.isStoppedBySelf)
    }
}
