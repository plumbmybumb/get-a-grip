// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RoutineShare
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.builder.BuilderMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Turning the phone must not throw away open work: a real Activity recreated under a real
 * root, the way `WorkoutRecreationTests` holds the runner to the same rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-notnight-mdpi", application = GetAGripApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RootRecreationTests {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var application: GetAGripApplication
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var template: SessionTemplateEntity

    @Before fun launchActualApp() {
        application = ApplicationProvider.getApplicationContext()
        val plan = SessionPlan(name = "Morning", sets = listOf(SetPlan(repsPerSide = 3), SetPlan(repsPerSide = 4)))
        template = SessionTemplateEntity.from(RoutineDraft.blank(plan.name).copy(plan = plan), 0)
        runBlocking { application.database.routines().upsert(template) }
        compose.runOnUiThread {
            val intent = Intent(application, MainActivity::class.java).putExtra("mockDevice", true)
            controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        }
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

    private fun presentation(): RootPresentation =
        ViewModelProvider(controller.get())[RootPresentation::class.java]

    private fun recreate() {
        compose.runOnUiThread { controller.recreate() }
        compose.waitForIdle()
    }

    @Test fun anEditInProgressSurvivesRecreation() {
        compose.runOnIdle { presentation().building = BuilderMode.Edit(template.id) }
        compose.onNodeWithContentDescription("Routine name").performTextInput(" rotated")
        compose.onNodeWithContentDescription("Routine name").assertTextContains("rotated", substring = true)
        recreate()
        compose.onNodeWithText("Edit routine").assertIsDisplayed()
        compose.onNodeWithContentDescription("Routine name").assertTextContains("rotated", substring = true)
    }

    @Test fun aNewRoutineSurvivesRecreationBeforeItsRescueStashIsWritten() {
        compose.runOnIdle { presentation().building = BuilderMode.AddAnother }
        compose.onNodeWithContentDescription("Routine name").performTextInput("Pinches")
        // Immediately: the stash is still half a second away, so only the saved draft can
        // carry the name across, and the re-opened document must not sweep the stash over it.
        recreate()
        compose.onNodeWithText("Your routine").assertIsDisplayed()
        compose.onNodeWithContentDescription("Routine name").assertTextContains("Pinches", substring = true)
    }

    @Test fun aSharedRoutinePreviewSurvivesRecreation() {
        val shared = RoutineDraft.blank("From a friend").copy(
            plan = SessionPlan(name = "From a friend", sets = listOf(SetPlan(repsPerSide = 5))))
        val url = assertNotNull(RoutineShare.url(shared))
        compose.runOnIdle { application.templates.receiveShareLink(url) }
        compose.onNodeWithText("Shared routine").assertIsDisplayed()
        // Claiming the preview emptied the inbox: after this, nothing but saved state holds it.
        compose.runOnIdle { assertEquals(null, application.templates.pendingImport) }
        recreate()
        compose.onNodeWithText("Shared routine").assertIsDisplayed()
    }

    @Test fun aNewMeasurementStartsUnsaved() {
        val model = RootPresentation()
        val first = MeasureRequest(GripSpec(), Side.left)
        model.measuring = first
        model.measurementSaved = true
        assertTrue(model.measurementSaved)
        // Closing, then measuring the same grip again, is a new visit.
        model.measuring = null
        assertFalse(model.measurementSaved)
        model.measuring = MeasureRequest(GripSpec(), Side.left)
        assertFalse(model.measurementSaved, "a saved receipt belongs to the visit it happened in")
        model.measuring = MeasureRequest(GripSpec(), Side.right)
        assertFalse(model.measurementSaved)
    }
}
