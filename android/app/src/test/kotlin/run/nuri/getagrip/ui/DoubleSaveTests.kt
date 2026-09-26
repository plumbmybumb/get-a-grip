// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.runtime.CompositionLocalProvider
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
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.*
import run.nuri.getagrip.ui.builder.BuilderMode
import run.nuri.getagrip.ui.builder.RoutineBuilderHost
import run.nuri.getagrip.ui.share.RoutineImportSheet
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/// A new routine has no id until the store writes it, so every Save that reaches the store is
/// another routine. The write is held open here — the window a quick second tap lands in on a
/// real phone — and two taps must still make one routine.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp-mdpi")
class DoubleSaveTests {
    @get:Rule val compose = createComposeRule()

    private val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val gate = CompletableDeferred<Unit>()
    private val room = RoomStoreGateway(db)

    /// Gateway calls in flight. A second save is always either one of these or a main-thread
    /// hop between two of them, so "the main thread is idle and this is zero" is the store
    /// having settled — a signal, where a fixed sleep only hoped.
    private val inFlight = AtomicInteger()

    private suspend fun <T> counted(block: suspend () -> T): T {
        inFlight.incrementAndGet()
        try { return block() } finally { inFlight.decrementAndGet() }
    }

    private val gateway = object : StoreGateway by room {
        override suspend fun allRoutines() = counted { room.allRoutines() }
        override suspend fun routine(id: UUID) = counted { room.routine(id) }
        override suspend fun logsFrom(dayKey: Int) = counted { room.logsFrom(dayKey) }
        override suspend fun allLogs() = counted { room.allLogs() }
        override suspend fun allMaxes() = counted { room.allMaxes() }
        override suspend fun log(id: UUID) = counted { room.log(id) }
        override suspend fun logsFor(templateID: UUID) = counted { room.logsFor(templateID) }
        override suspend fun dayStamps(before: Instant) = counted { room.dayStamps(before) }
        override suspend fun write(work: suspend (StoreWriter) -> Unit) = counted {
            gate.await()
            room.write(work)
        }
    }
    private val settings = SettingsStore(RuntimeEnvironment.getApplication(), settingsScope)
    private val store = TemplateStore(gateway, DayClock(), settings, RecordingAlarmScheduler(), scope)

    @After fun close() { scope.cancel(); settingsScope.cancel(); db.close() }

    private fun routineCount(): Int = runBlocking { db.routines().all().size }

    private fun releaseAndSettle() {
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { routineCount() > 0 && inFlight.get() == 0 }
        // Drain the main thread: a second save waiting there would now be in a gateway call.
        compose.waitForIdle()
        compose.waitUntil(5_000) { inFlight.get() == 0 }
    }

    @Test fun doubleTappingSaveInTheBuilderCreatesOneRoutine() {
        var closed = 0
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides store, LocalSettingsStore provides settings) {
                GetAGripTheme { RoutineBuilderHost(mode = BuilderMode.AddAnother, onDone = { closed++ }) }
            }
        }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Add a set").performScrollTo().performClick()
        val save = compose.onNodeWithText("Save")
        save.performClick()
        save.assertIsNotEnabled()
        save.performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Save routine").performClick()
        releaseAndSettle()
        compose.waitUntil(5_000) { closed > 0 }
        assertEquals(1, routineCount())
        assertEquals(1, closed)
    }

    @Test fun doubleTappingAddOnASharedRoutineAddsItOnce() {
        val shared = RoutineDraft.blank("From a friend").copy(
            plan = SessionPlan(name = "From a friend", sets = listOf(SetPlan(repsPerSide = 5))))
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides store, LocalSettingsStore provides settings) {
                GetAGripTheme { RoutineImportSheet(shared) {} }
            }
        }
        val add = compose.onNodeWithText("Add to my routines")
        add.performScrollTo().performClick()
        add.assertIsNotEnabled()
        add.performClick()
        releaseAndSettle()
        assertEquals(1, routineCount())
    }
}
