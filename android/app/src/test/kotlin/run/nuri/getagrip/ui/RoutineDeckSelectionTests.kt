// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.*
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.today.RoutineDeck
import run.nuri.getagrip.ui.today.rememberRoutineSummary
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h820dp-mdpi")
class RoutineDeckSelectionTests {
    @get:Rule val compose = createComposeRule()

    private val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = SettingsStore(RuntimeEnvironment.getApplication(), settingsScope)
    private val store = TemplateStore(RoomStoreGateway(db), DayClock(), settings, RecordingAlarmScheduler(), scope)

    @After fun close() { scope.cancel(); settingsScope.cancel(); db.close() }

    private fun seed(vararg names: String): List<SessionTemplateEntity> = runBlocking {
        names.forEachIndexed { order, name ->
            val plan = SessionPlan(name = name, sets = listOf(SetPlan(repsPerSide = 3)))
            db.routines().upsert(SessionTemplateEntity.from(RoutineDraft.blank(name).copy(plan = plan), order))
        }
        store.syncDerived()
        store.routines
    }

    @Test fun aProgrammaticMoveToTheNewSelectionIsNotReadAsASwipe() {
        val (first, second) = seed("Morning", "Evening")
        var selected by mutableStateOf<UUID?>(first.id)
        val settled = mutableListOf<UUID>()
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides store) {
                GetAGripTheme {
                    RoutineDeck(routines = store.routines, templates = store, selectedID = selected,
                        onSettled = { settled += it })
                }
            }
        }
        compose.waitForIdle()
        // The day rolls, a reminder fires: the HOST moves the deck. Nobody swiped.
        compose.runOnIdle { selected = second.id }
        compose.waitForIdle()
        assertEquals(emptyList(), settled)
    }

    @Test fun aSwipeReachesTheHostsCurrentCallback() {
        val (_, second) = seed("Morning", "Evening")
        var generation by mutableStateOf("first")
        val settled = mutableListOf<Pair<String, UUID>>()
        compose.setContent {
            val label = generation
            CompositionLocalProvider(LocalTemplateStore provides store) {
                GetAGripTheme {
                    RoutineDeck(routines = store.routines, templates = store,
                        selectedID = store.routines.first().id,
                        onSettled = { settled += label to it })
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { generation = "today" }
        compose.waitForIdle()
        // The pager's own scroll action — the same settle a finger produces, which is all the
        // collector ever sees.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.waitForIdle()
        assertEquals(listOf("today" to second.id), settled)
    }

    @Test fun aMemoizedSummaryStillCountsTheSessionJustLogged() {
        val (routine) = seed("Morning")
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides store) {
                val current = store.routines.first()
                Text(rememberRoutineSummary(store, current).completedToday.toString(), Modifier.testTag("done"))
            }
        }
        compose.onNodeWithTag("done").assertTextEquals("0")
        runBlocking {
            store.recordSession(plan = routine.plan, template = routine, reps = emptyList(),
                startedAt = Instant.now().minusSeconds(60), finishedAt = Instant.now(), rpe = null)
        }
        compose.onNodeWithTag("done").assertTextEquals("1")
    }
}
