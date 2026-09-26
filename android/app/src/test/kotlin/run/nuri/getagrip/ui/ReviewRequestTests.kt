// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.InMemoryRoutineSettings
import run.nuri.getagrip.store.LocalSettingsStore
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.LogIdentity
import run.nuri.getagrip.store.PlayStoreListing
import run.nuri.getagrip.store.RecordingAlarmScheduler
import run.nuri.getagrip.store.ReviewRequestPolicy
import run.nuri.getagrip.store.RoomStoreGateway
import run.nuri.getagrip.store.SettingsStore
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.settings.SupportCard
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The rating ask, ported from iOS's `ReviewRequestPolicy`: once per device, from the fifth
/// session the RUNNER saved, never for a discarded one, and a permanent Settings row that
/// opens the Play listing.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewRequestTests {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val opened = mutableListOf<GetAGripDatabase>()

    @After fun tearDown() {
        owner.cancel()
        opened.forEach { it.close() }
    }

    private fun finishWrites() = runBlocking { owner.coroutineContext[Job]!!.children.toList().joinAll() }

    private fun store(): TemplateStore {
        val db = GetAGripDatabase.inMemory(context)
        opened.add(db)
        return TemplateStore(
            gateway = RoomStoreGateway(db, UnconfinedTestDispatcher()),
            clock = DayClock(),
            settings = InMemoryRoutineSettings(),
            scheduler = RecordingAlarmScheduler(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
    }

    private suspend fun TemplateStore.saveRunnerSession() = assertNotNull(
        recordSession(
            plan = SessionPlan(),
            identity = LogIdentity.of(null, SessionPlan(), UUID.randomUUID()),
            reps = emptyList(),
            startedAt = Instant.now(),
            finishedAt = Instant.now(),
            rpe = null,
        ),
    )

    @Test fun asksFromTheFifthSavedSessionOnwardsAndNeverTwice() {
        assertEquals(5, ReviewRequestPolicy.SESSIONS_BEFORE_ASKING)
        assertFalse(ReviewRequestPolicy.shouldAsk(hangSessionsLogged = 0, alreadyAsked = false))
        assertFalse(ReviewRequestPolicy.shouldAsk(hangSessionsLogged = 4, alreadyAsked = false))
        assertTrue(ReviewRequestPolicy.shouldAsk(hangSessionsLogged = 5, alreadyAsked = false))
        // Somebody updating with a long history is asked on their next session, once.
        assertTrue(ReviewRequestPolicy.shouldAsk(hangSessionsLogged = 40, alreadyAsked = false))
        assertFalse(ReviewRequestPolicy.shouldAsk(hangSessionsLogged = 5, alreadyAsked = true))
        assertFalse(ReviewRequestPolicy.shouldAsk(hangSessionsLogged = 400, alreadyAsked = true))
    }

    @Test fun theOnceFlagPersistsAcrossLaunches() {
        val first = SettingsStore(context, owner)
        first.setReviewRequested(false)
        finishWrites()
        assertFalse(SettingsStore(context, owner).reviewRequested, "a fresh install has not asked")
        first.setReviewRequested(true)
        assertTrue(first.reviewRequested)
        finishWrites()
        assertTrue(SettingsStore(context, owner).reviewRequested, "the once-only flag survives a relaunch")
    }

    @Test fun hangSessionCountCountsOnlySessionsTheAppRan() = runBlocking {
        val store = store()
        assertEquals(0, store.hangSessionCount())
        assertEquals(0, store.sessionsSavedThisLaunch)
        assertNotNull(store.recordLoggedSession(SessionKind.climbLimit))
        assertNotNull(store.recordLoggedSession(SessionKind.hangManual))
        assertEquals(0, store.hangSessionCount(), "climbs and hand-logged hangs are not sessions the app ran")
        assertEquals(0, store.sessionsSavedThisLaunch, "only the runner's saves move the counter")
        repeat(5) { store.saveRunnerSession() }
        assertEquals(5, store.hangSessionCount())
        assertEquals(5, store.sessionsSavedThisLaunch)
        assertTrue(ReviewRequestPolicy.shouldAsk(store.hangSessionCount(), alreadyAsked = false))
    }

    /// Store first, web second, and false — never a crash — when the phone has neither.
    @Test fun thePlayListingFallsBackToTheWebThenGivesUp() {
        val id = context.packageName
        assertEquals("market://details?id=$id", PlayStoreListing.marketUri(id))
        assertEquals("https://play.google.com/store/apps/details?id=$id", PlayStoreListing.webUri(id))

        val opened = mutableListOf<String>()
        val noStore = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                if (intent.data?.scheme == "market") throw ActivityNotFoundException()
                opened += intent.dataString!!
            }
        }
        assertTrue(PlayStoreListing.open(noStore))
        assertEquals(listOf(PlayStoreListing.webUri(id)), opened)

        val nothing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(PlayStoreListing.open(nothing))
    }

    @Test fun theSettingsRowOpensThePlayListing() {
        val intents = mutableListOf<Intent>()
        val capturing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { intents += intent }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides capturing) {
                GetAGripTheme { SupportCard("Progressor") { null } }
            }
        }
        compose.onNodeWithText("Get a Grip is free and open source. A rating helps other climbers find it.")
            .assertExists()
        compose.onNodeWithContentDescription("Rate on Google Play. Opens Google Play.").performClick()
        assertEquals(listOf(PlayStoreListing.marketUri(context.packageName)), intents.map { it.dataString })
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
    }

    /// The whole ask, driven the way the root drives it: a runner opens and closes around each
    /// save. A discarded session never asks; the fifth save does, once; the sixth does not.
    @Test fun thePromptAppearsOnceAfterTheFifthSavedRunnerSession() {
        val templates = store()
        val settings = SettingsStore(context, owner)
        settings.setReviewRequested(false)
        finishWrites()
        var runnerOpen by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides templates, LocalSettingsStore provides settings) {
                GetAGripTheme {
                    val review = rememberReviewRequest(runnerOpen)
                    ReviewRequestDialog(review)
                }
            }
        }
        fun session(save: Boolean) {
            compose.runOnIdle { runnerOpen = true }
            compose.waitForIdle()
            if (save) runBlocking { templates.saveRunnerSession() }
            compose.runOnIdle { runnerOpen = false }
            compose.mainClock.advanceTimeBy(REVIEW_PROMPT_DELAY_MILLIS + 100)
            compose.waitForIdle()
        }
        fun prompts() = compose.onAllNodesWithText("Rate Get a Grip").fetchSemanticsNodes().size

        repeat(4) { session(save = true) }
        assertEquals(0, prompts(), "four sessions is not yet five")
        session(save = false)
        assertEquals(0, prompts(), "a discarded session never asks")
        assertFalse(settings.reviewRequested)

        session(save = true)
        compose.waitUntil(5_000) { prompts() == 1 }
        assertTrue(settings.reviewRequested, "the flag is written before the prompt shows")
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertEquals(0, prompts())

        session(save = true)
        assertEquals(0, prompts(), "never twice")
    }
}
