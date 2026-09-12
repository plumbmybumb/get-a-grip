// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.ui.tour.TourAct
import run.nuri.getagrip.ui.tour.TourController
import kotlin.test.assertEquals

/** Exercise the real routes: a card-only callback test cannot distinguish an
 * overview from accidentally opening the builder or switching tabs under a sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-notnight-mdpi", application = GetAGripApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutineOverviewNavigationTests {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var application: GetAGripApplication
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var template: SessionTemplateEntity

    @Before fun launchActualApp() {
        application = ApplicationProvider.getApplicationContext()
        for (act in TourAct.entries) {
            application.settings.setTourSeenVersion(act.rawValue, TourController.VERSION)
        }
        application.settings.setBuilderGuideDone(true)
        val plan = SessionPlan(
            name = "Precision routine",
            sets = listOf(
                SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = 3),
                SetPlan(grip = GripSpec(edgeMM = 15), repsPerSide = 2),
            ),
            handMode = HandMode.alternateEachRep,
            holdSeconds = 7,
            restSeconds = 13,
            setBreakSeconds = 45,
            leadInSeconds = 5,
        )
        template = SessionTemplateEntity.from(RoutineDraft.blank(plan.name).copy(plan = plan), 0)
        runBlocking { application.database.routines().upsert(template) }
        compose.runOnUiThread {
            val intent = Intent(application, MainActivity::class.java).putExtra("mockDevice", true)
            controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle { application.templates.routines.any { it.id == template.id } }
        }
        compose.onNodeWithTag("today.routineOverview").assertIsDisplayed()
    }

    @After fun closeActualApp() {
        if (::controller.isInitialized) {
            compose.runOnUiThread { controller.pause().stop().destroy() }
        }
        if (::application.isInitialized) {
            application.gaugeScope.cancel()
            application.storeScope.cancel()
            application.database.close()
        }
    }

    @Test fun planRowShowsReadOnlyOverviewBeforeOpeningTheExistingEditor() {
        compose.onNodeWithTag("today.routineOverview").performClick()
        compose.onNodeWithTag("routineOverview").assertIsDisplayed()
        compose.onNode(hasText(template.name) and hasAnyAncestor(hasTestTag("routineOverview")))
            .assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(template, savedRoutine(), "Inspecting the plan must not mutate it")
        capture("android-routine-overview.png")

        compose.onNodeWithTag("routineOverview.edit").assertIsDisplayed().performClick()
        compose.onNodeWithText("Edit routine").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(template.name)).assertIsDisplayed()
        compose.onNodeWithTag("routineOverview").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("today.routineOverview").assertIsDisplayed()
        assertEquals(template, savedRoutine(), "Opening and cancelling Edit must preserve the same routine")
    }

    @Test fun routineOptionsEditStillGoesDirectlyToTheSameSavedRoutine() {
        compose.onNodeWithContentDescription("Routine options").performClick()
        compose.onNodeWithText("Edit routine").performClick()
        compose.onNodeWithTag("routineOverview").assertDoesNotExist()
        compose.onNodeWithText("Edit routine").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(template.name)).assertIsDisplayed()
            .performTextReplacement("Edited from options")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            val persisted = compose.runOnIdle {
                application.templates.routines.any { it.id == template.id && it.name == "Edited from options" }
            }
            // The store publishes before the save coroutine finishes and dismisses
            // the editor. Wait for that real navigation too, not only its write.
            persisted && compose.onAllNodesWithTag("today.routineOverview").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("today.routineOverview").assertIsDisplayed()
        compose.onNodeWithText("Edit routine").assertDoesNotExist()
        val saved = savedRoutine()
        assertEquals(template.id, saved.id)
        assertEquals("Edited from options", saved.name)
        assertEquals(template.plan.sets, saved.plan.sets)
    }

    @Test fun fortnightOpensHistoryWhileItsLogActionKeepsTodaySelected() {
        compose.onNodeWithTag("today.history").performScrollTo().performClick()
        tab("History").assertIsSelected()
        compose.onNodeWithTag("today.routineOverview").assertDoesNotExist()

        tab("Today").performClick()
        compose.onNodeWithContentDescription(
            "Log a session you did elsewhere — climbing, or hangs off the gauge",
        ).performScrollTo().performClick()
        compose.onNodeWithText("Log a session").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        tab("Today").assertIsSelected()
        tab("History").assertIsNotSelected()
        compose.onNodeWithTag("today.routineOverview").assertExists()
        assertEquals(0, runBlocking { application.database.logs().all() }.size,
            "Cancelling the Log action must not create a session")
    }

    private fun tab(label: String): SemanticsNodeInteraction = compose.onNode(
        hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
    )

    private fun savedRoutine(): SessionTemplateEntity = runBlocking {
        application.database.routines().byId(template.id)!!
    }

    private fun capture(name: String) {
        val output = java.io.File("../../build/review/routine-overview/$name").canonicalFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("routineOverview").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
