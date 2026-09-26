// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
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
import run.nuri.getagrip.ui.builder.BuilderMode
import run.nuri.getagrip.ui.builder.RoutineBuilderHost
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// **The builder's cost is INVALIDATION, and this counts it.** The lag this guards against
/// was every section and every set row redrawing for one keystroke (iOS: 15 bodies → 3). A recomposition
/// count is the only honest check — a screenshot of a document that redrew six rows looks
/// exactly like one that redrew one.
///
/// Counted with the Compose runtime's own trace hook: the compiler brackets the BODY of every
/// restartable composable with a trace event, emitted only when the body actually runs, so a
/// skipped row contributes nothing.
///
/// **It keys on composable FUNCTION NAMES** as they appear in those trace events. Renaming a
/// section or row composable breaks these tests — a count of zero for a name that no longer
/// exists — so rename the string here with it.
@OptIn(InternalComposeTracingApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp-mdpi")
class BuilderRecompositionTests {
    @get:Rule val compose = createComposeRule()

    private class Bodies : CompositionTracer {
        private val counts = ConcurrentHashMap<String, Int>()
        @Volatile var recording = false
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (!recording) return
            val name = info.substringBefore(" (").substringAfterLast('.')
            counts.merge(name, 1, Int::plus)
        }
        override fun traceEventEnd() = Unit
        override fun isTraceInProgress(): Boolean = true
        fun reset() = counts.clear()
        operator fun get(name: String): Int = counts[name] ?: 0
        override fun toString() = counts.toSortedMap().toString()
    }

    private val bodies = Bodies()
    private val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun trace() = Composer.setTracer(bodies)
    @After fun close() {
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = false
        })
        scope.cancel(); settingsScope.cancel(); db.close()
    }

    private fun showSixSetRoutine() {
        val settings = SettingsStore(RuntimeEnvironment.getApplication(), settingsScope)
        val store = TemplateStore(RoomStoreGateway(db), DayClock(), settings, RecordingAlarmScheduler(), scope)
        val routine = RoutineDraft.starter
        runBlocking {
            db.routines().upsert(SessionTemplateEntity.from(routine.normalized, 0))
            store.syncDerived()
        }
        val id = store.routines.single().id
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides store, LocalSettingsStore provides settings) {
                GetAGripTheme { RoutineBuilderHost(mode = BuilderMode.Edit(id), onDone = {}) }
            }
        }
        compose.waitForIdle()
        assertTrue(routine.plan.sets.size >= 4, "the point is several rows that should sit still")
    }

    private fun measure(action: () -> Unit): Bodies {
        bodies.reset()
        bodies.recording = true
        action()
        compose.waitForIdle()
        bodies.recording = false
        return bodies
    }

    @Test fun typingTheNameRedrawsNoSectionAndNoSetRow() {
        showSixSetRoutine()
        val counted = measure {
            compose.onNodeWithContentDescription("Routine name").performTextInput("x")
        }
        assertTrue(counted["NameSection"] >= 1, "the name field itself must redraw: $counted")
        for (section in listOf("RhythmSection", "TimingStepper", "RoutineTargetRow", "HandsHeader")) {
            assertEquals(0, counted[section], "$section redrew for a letter typed into the name: $counted")
        }
    }

    /// The Rhythm page's three ladder rows share a card: stepping one must leave the other two,
    /// the hands and the target row sitting still.
    @Test fun steppingTheHoldRedrawsOnlyItsOwnRow() {
        showSixSetRoutine()
        val counted = measure {
            compose.onNode(hasContentDescription("Hold") and hasStateDescription("10 seconds"))
                .performCustomAccessibilityActionWithLabel("Increase")
        }
        assertEquals(1, counted["TimingStepper"], "one ladder row changed, so one redraws: $counted")
        assertEquals(0, counted["NameSection"], counted.toString())
        assertEquals(0, counted["RoutineTargetRow"], counted.toString())
        assertEquals(0, counted["HandsHeader"], counted.toString())
    }

    @Test fun editingOneSetRedrawsOnlyThatRow() {
        showSixSetRoutine()
        compose.onNodeWithText("Sets").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasStateDescription("Collapsed") and hasClickAction())[0].performClick()
        compose.waitForIdle()
        val counted = measure {
            compose.onNodeWithContentDescription("Increase Pulls per side").performScrollTo().performClick()
        }
        assertEquals(1, counted["SetRowView"], "one set changed, so one row redraws: $counted")
    }
}
