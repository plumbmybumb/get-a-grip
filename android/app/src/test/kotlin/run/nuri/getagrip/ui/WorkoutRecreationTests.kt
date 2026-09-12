// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.GetAGripApplication
import run.nuri.getagrip.MainActivity
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.runner.ActiveWorkout
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.runner.SessionServiceController
import run.nuri.getagrip.runner.WorkoutViewModel
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.ui.tour.TourAct
import run.nuri.getagrip.ui.tour.TourController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real Activity/Compose teardown, with deterministic gauge samples at the store's
 * input. Recomposition-only tests cannot catch losing the full-screen workout route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-notnight-mdpi", application = GetAGripApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkoutRecreationTests {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var application: GetAGripApplication
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var template: SessionTemplateEntity
    private val clock = FakeClock()
    private val client = RecordingProgressorClient()
    private val service = ServiceRecorder()
    private var micros = 0u

    @Before fun launchActualApp() {
        application = ApplicationProvider.getApplicationContext()
        for (act in TourAct.entries) {
            application.settings.setTourSeenVersion(act.rawValue, TourController.VERSION)
        }
        val plan = SessionPlan(name = "Appearance recovery", sets = listOf(SetPlan(repsPerSide = 3)),
            handMode = HandMode.bothHands, holdSeconds = 4, restSeconds = 20,
            leadInSeconds = 0, waitForReleaseBeforeRest = true)
        template = SessionTemplateEntity.from(RoutineDraft.blank(plan.name).copy(plan = plan), 0)
        runBlocking { application.database.routines().upsert(template) }
        compose.runOnUiThread {
            val intent = Intent(application, MainActivity::class.java).putExtra("mockDevice", true)
            controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        }
        compose.waitForIdle()
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

    @Test fun nightModeAndActivityRecreationKeepCompletedPullsAndPartialHold() {
        val workout = startWorkout()
        compose.runOnIdle {
            completeFirstPull(workout.session)
            clock.uptime += 21
            workout.session.tickNow()
            repeat(24) { sample(workout.session, 12.0) }
        }
        val beforeResults = workout.session.runner.results.toList()
        val beforeHeld = workout.session.runner.heldSeconds
        val beforeStarted = workout.session.startedAt
        assertEquals(1, workout.session.snapshot.completedRepCount)
        assertTrue(workout.session.snapshot.phase is RunnerPhase.Working)
        assertTrue(beforeHeld > 0.5 && beforeHeld < 4, "Exercise an actual partially completed second pull")

        val originalActivity = controller.get()
        setNightMode(true)
        assertSame(originalActivity, controller.get(), "A system appearance change should update the existing Activity")
        assertSame(workout, currentModel().active)
        assertEquals(beforeResults, workout.session.runner.results)
        assertEquals(beforeHeld, workout.session.runner.heldSeconds, 0.000001)
        assertTrue(workout.session.snapshot.phase is RunnerPhase.Working,
            "Changing system appearance in place must not interrupt the pull")
        compose.onNodeWithTag("runner-plot").assertIsDisplayed()

        compose.runOnUiThread { controller.recreate() }
        compose.waitForIdle()
        assertNotSame(originalActivity, controller.get(), "Also exercise a genuine Activity replacement")
        assertSame(workout, currentModel().active, "The workout route and runner must survive together")
        assertEquals(beforeResults, workout.session.runner.results)
        assertEquals(beforeStarted, workout.session.startedAt)
        assertEquals(beforeHeld, workout.session.runner.heldSeconds, 0.000001)
        val recreatedPhase = workout.session.snapshot.phase
        assertTrue(recreatedPhase is RunnerPhase.Working ||
            (recreatedPhase is RunnerPhase.Paused && recreatedPhase.before is RunnerPhase.Working),
            "A replaced screen must retain the partial pull, including a legitimate lifecycle pause")
        assertEquals(1, service.starts)
        assertEquals(0, service.ends, "Tearing down the old screen must not end the workout service")
        compose.onNodeWithTag("runner-plot").assertIsDisplayed()
        if (recreatedPhase is RunnerPhase.Paused) {
            compose.onNodeWithText("Resume").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertTrue(workout.session.snapshot.phase is RunnerPhase.Working) }
        compose.runOnIdle { repeat(10) { sample(workout.session, 12.0) } }
        assertTrue(workout.session.runner.heldSeconds > beforeHeld,
            "Retained state must still consume the gauge stream after the new screen attaches")
        setNightMode(false)
        assertSame(workout, currentModel().active)
        assertEquals(1, workout.session.snapshot.completedRepCount)
    }

    @Test fun unsavedSummaryChoicesSurviveThemeAndRecreationThenSaveOnce() {
        val workout = startWorkout()
        compose.runOnIdle {
            completeFirstPull(workout.session)
            workout.session.send(RunnerEvent.Abort)
        }
        compose.onNodeWithText("Save and finish").assertIsDisplayed()
        val beforeOutcome = workout.outcome()
        compose.onNodeWithTag("effort.overall").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.onNodeWithContentDescription("New peaks to review").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Both hands", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Sets").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(RPE.hard, workout.summary.rpe)
            assertEquals(1, workout.summary.chosenMaxIDs.size)
            assertTrue(workout.summary.maxesExpanded)
            assertTrue(workout.summary.setsExpanded)
        }
        val selectedMaxes = workout.summary.chosenMaxIDs

        setNightMode(true)
        val oldActivity = controller.get()
        compose.runOnUiThread { controller.recreate() }
        compose.waitForIdle()
        assertNotSame(oldActivity, controller.get())
        assertSame(workout, currentModel().active)
        assertSame(beforeOutcome, workout.outcome(), "Summary timestamps and measured results remain frozen")
        assertEquals(RPE.hard, workout.summary.rpe)
        assertEquals(selectedMaxes, workout.summary.chosenMaxIDs)
        assertFalse(workout.summary.finished)
        compose.onNodeWithContentDescription("New peaks to review").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithContentDescription("Both hands", substring = true).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        compose.onNodeWithContentDescription("Sets").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))

        setNightMode(false)
        val model = compose.runOnIdle { currentModel() }
        compose.onNodeWithText("Save and finish").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { action -> action(); action() }
        // Room returns through the real Android main looper used by viewModelScope.
        // Poll on that looper too: advancing Compose's frame clock alone cannot run
        // a database continuation posted after the click's idle pass finished.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle { model.active == null || workout.summary.saveFailed }
        }
        assertFalse(workout.summary.saveFailed, application.templates.saveError)
        assertNull(model.active)
        val saved = runBlocking { application.database.logs().all() }
        assertEquals(1, saved.size, "Two fast Save actions must produce exactly one workout")
        assertEquals(beforeOutcome.results, saved.single().reps)
        assertEquals(RPE.hard, saved.single().grade)
        assertEquals(1, runBlocking { application.database.maxes().all() }.size)
        assertEquals(1, service.starts)
        assertEquals(1, service.ends)
    }

    @Test fun finalRestSecondsAndUpcomingHandSurviveAppearanceAndActivityReplacement() {
        val workout = startWorkout()
        compose.runOnIdle {
            completeFirstPull(workout.session)
            clock.uptime += 18
            workout.session.tickNow()
        }
        val before = workout.session.snapshot
        val completed = workout.session.runner.results.toList()
        assertEquals(2, before.secondsShown)
        assertTrue(before.showsRestFocus)
        compose.onNodeWithTag("runner.restFocus.countdown", useUnmergedTree = true).assertTextEquals("2")

        val activity = controller.get()
        setNightMode(true)
        assertSame(activity, controller.get())
        assertSame(workout, currentModel().active)
        assertEquals(before.phase, workout.session.snapshot.phase,
            "An in-place appearance change must not pause an ongoing rest")
        assertEquals(2, workout.session.snapshot.secondsShown)

        compose.onNodeWithText("Pause").performClick()
        compose.runOnUiThread { controller.recreate() }
        compose.waitForIdle()
        assertNotSame(activity, controller.get())
        assertSame(workout, currentModel().active)
        assertEquals(completed, workout.session.runner.results)
        assertEquals(before.side, workout.session.snapshot.side)
        assertEquals(before.grip, workout.session.snapshot.grip)
        assertEquals(before.setNumber, workout.session.snapshot.setNumber)
        assertEquals(before.completedRepCount, workout.session.snapshot.completedRepCount)
        compose.onNodeWithTag("runner.restFocus.phase", useUnmergedTree = true).assertTextEquals("PAUSED")
        compose.onNodeWithTag("runner.restFocus.countdown", useUnmergedTree = true).assertTextEquals("2")

        compose.runOnIdle { clock.uptime += 5; workout.session.tickNow() }
        assertEquals(2, workout.session.snapshot.secondsShown)
        compose.onNodeWithText("Resume").performClick()
        compose.runOnIdle { clock.uptime += 2.1; workout.session.tickNow() }
        assertTrue(workout.session.snapshot.phase is RunnerPhase.Armed)
        compose.onNodeWithTag("runner.restFocus").assertDoesNotExist()
        compose.onNodeWithTag("runner.hero").assertIsDisplayed()
        assertEquals(completed, workout.session.runner.results)
        assertEquals(1, service.starts)
        assertEquals(0, service.ends)
    }

    private fun startWorkout(): ActiveWorkout {
        lateinit var workout: ActiveWorkout
        compose.runOnIdle {
            val model = currentModel()
            assertTrue(model.start(template) { retainedScope ->
                val device = DeviceStore(client, scope = retainedScope, clock = clock)
                client.setState(ProgressorConnectionState.Connected)
                RunnerSession(plan = template.plan, routineName = template.name, device = device,
                    scope = retainedScope, clock = clock, service = service)
            })
            workout = model.active!!
        }
        compose.waitForIdle()
        return workout
    }

    private fun currentModel(): WorkoutViewModel =
        ViewModelProvider(controller.get())[WorkoutViewModel::class.java]

    private fun setNightMode(enabled: Boolean) {
        compose.runOnUiThread {
            val configuration = Configuration(controller.get().resources.configuration)
            configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (enabled) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            controller.configurationChange(configuration)
            assertEquals(if (enabled) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                controller.get().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        }
        compose.waitForIdle()
    }

    private fun sample(session: RunnerSession, kg: Double) {
        clock.uptime += 0.05
        session.tickNow()
        micros += 50_000u
        client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
    }

    private fun completeFirstPull(session: RunnerSession) {
        repeat(120) { if (session.snapshot.phase !is RunnerPhase.Releasing) sample(session, 12.0) }
        assertTrue(session.snapshot.phase is RunnerPhase.Releasing)
        sample(session, 0.0)
        assertTrue(session.snapshot.phase is RunnerPhase.Resting)
        assertEquals(1, session.snapshot.completedRepCount)
    }

    private class ServiceRecorder : SessionServiceController {
        var starts = 0
        var ends = 0
        override fun begin() { starts++ }
        override fun end() { ends++ }
    }
}
