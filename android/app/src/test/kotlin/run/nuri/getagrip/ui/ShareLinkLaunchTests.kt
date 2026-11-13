// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.GetAGripApplication
import run.nuri.getagrip.MainActivity
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RoutineShare
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** **A link is read ONCE.** A real Activity, launched by a routine link and then recreated
 * under a real root: the recreated Activity is handed the same launch intent, and reading
 * it again queued the same import a second time — after it had been declined. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-notnight-mdpi", application = GetAGripApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareLinkLaunchTests {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var application: GetAGripApplication
    private lateinit var controller: ActivityController<MainActivity>

    private val link: String = assertNotNull(
        RoutineShare.url(
            RoutineDraft.blank("From a friend").copy(
                plan = SessionPlan(name = "From a friend", sets = listOf(SetPlan(repsPerSide = 5))),
            ),
        ),
    )

    private fun viewIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(link), ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("mockDevice", true)

    private fun launch(intent: Intent) {
        application = ApplicationProvider.getApplicationContext()
        compose.runOnUiThread {
            controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        }
        compose.waitForIdle()
    }

    private fun recreate() {
        compose.runOnUiThread { controller.recreate() }
        compose.waitForIdle()
    }

    @After fun closeActualApp() {
        if (::controller.isInitialized) compose.runOnUiThread { controller.pause().stop().destroy() }
        if (::application.isInitialized) {
            application.gaugeScope.cancel()
            application.storeScope.cancel()
            application.database.close()
        }
    }

    /// The link that cold-launched the app is previewed, declined — and stays declined
    /// when the phone is turned.
    @Test fun aLinkThatLaunchedTheAppIsNotQueuedAgainOnRecreation() {
        launch(viewIntent())
        compose.onNodeWithText("Shared routine").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(controller.get().intent.data, "a queued link is cleared from the intent")
        }

        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Shared routine").assertDoesNotExist()

        recreate()
        compose.onNodeWithText("Shared routine").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(application.templates.pendingImport)
            assertNull(application.templates.pendingImportError)
        }
    }

    /// The other door: a link tapped while the app is already running arrives in
    /// `onNewIntent`, and is cleared from the intent the Activity keeps.
    @Test fun aLinkDeliveredToARunningAppIsReadOnceAndClearedToo() {
        launch(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("mockDevice", true))
        compose.onNodeWithText("Shared routine").assertDoesNotExist()

        compose.runOnUiThread { controller.newIntent(viewIntent()) }
        compose.waitForIdle()
        compose.onNodeWithText("Shared routine").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_VIEW, controller.get().intent.action, "the new intent is the Activity's own")
            assertNull(controller.get().intent.data, "and its link is spent")
        }

        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        recreate()
        compose.onNodeWithText("Shared routine").assertDoesNotExist()
    }

    /// The process was reclaimed and the task restored: the system hands a NEW Activity its
    /// own copy of the launch intent — link and all, which clearing ours could not reach —
    /// together with the state it saved. Saved state means this is not a fresh start.
    @Test fun aRestoredTaskDoesNotReplayTheLinkThatFirstOpenedIt() {
        application = ApplicationProvider.getApplicationContext()
        compose.runOnUiThread {
            controller = Robolectric.buildActivity(MainActivity::class.java, viewIntent())
                .create(Bundle()).start().resume().visible()
        }
        compose.waitForIdle()

        compose.onNodeWithText("Shared routine").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(application.templates.pendingImport)
            assertNull(application.templates.pendingImportError)
        }
    }

    /// Reopened from Recents, the task comes back with the link that first opened it and a
    /// flag saying so. Nothing is previewed.
    @Test fun aLinkReplayedFromRecentsOpensTheAppWithoutAnImport() {
        launch(viewIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        compose.onNodeWithText("Shared routine").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(application.templates.pendingImport)
            assertNull(application.templates.pendingImportError)
        }
    }
}
