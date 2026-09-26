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
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.*
import run.nuri.getagrip.ui.builder.BuilderMode
import run.nuri.getagrip.ui.builder.RoutineBuilderHost
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals

/// The three pages driven as a person would: Back / Next while creating, the switcher while
/// editing, the ladder stepper's single TalkBack element, and Custom timing on a set.
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp-mdpi")
class BuilderPagesFlowTests {
    @get:Rule val compose = createComposeRule()

    private val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = SettingsStore(RuntimeEnvironment.getApplication(), settingsScope)
    private val store = TemplateStore(RoomStoreGateway(db), DayClock(), settings, RecordingAlarmScheduler(), scope)

    @After fun close() { scope.cancel(); settingsScope.cancel(); db.close() }

    private fun show(mode: BuilderMode, onDone: (java.util.UUID?) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalTemplateStore provides store, LocalSettingsStore provides settings) {
                GetAGripTheme { RoutineBuilderHost(mode = mode, onDone = onDone) }
            }
        }
        compose.waitForIdle()
    }

    private fun seedStarter(): java.util.UUID {
        runBlocking {
            db.routines().upsert(SessionTemplateEntity.from(RoutineDraft.starter.normalized, 0))
            store.syncDerived()
        }
        return store.routines.single().id
    }

    @Test fun creatingWalksThreePagesAndSavesFromTheLast() {
        var saved: java.util.UUID? = null
        show(BuilderMode.AddAnother) { saved = it }
        // Page 1: no Back, the page names itself, and no complaint about sets not yet reached.
        compose.onNodeWithText("Rhythm").assertExists()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithText("Add at least one set with a pull in it.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rhythm, page 1 of 3").assertExists()

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Add at least one set with a pull in it.").assertExists()
        compose.onNodeWithText("Duplicate last set").assertDoesNotExist()
        compose.onNodeWithText("Add a set").performClick()
        compose.onNodeWithText("Duplicate last set").assertExists()

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").assertDoesNotExist()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Add a set").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Save routine").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals(1, store.routines.size)
    }

    @Test fun theLadderRowIsOneElementThatStepsTheLadderAndTypes() {
        show(BuilderMode.AddAnother)
        val hold = compose.onNode(hasContentDescription("Hold") and hasStateDescription("10 seconds"))
        hold.performCustomAccessibilityActionWithLabel("Increase")
        compose.onNode(hasContentDescription("Hold") and hasStateDescription("12 seconds"))
            .performCustomAccessibilityActionWithLabel("Type a value")
        val field = compose.onNode(hasSetTextAction() and isFocused())
        field.performTextInput("22")
        field.performImeAction()
        // Typed values are NOT snapped to the ladder, and step to their neighbours.
        compose.onNode(hasContentDescription("Hold") and hasStateDescription("22 seconds"))
            .performCustomAccessibilityActionWithLabel("Decrease")
        compose.onNode(hasContentDescription("Hold") and hasStateDescription("20 seconds")).assertExists()
    }

    @Test fun editingJumpsPagesAndCustomTimingSeedsFromTheRoutine() {
        val id = seedStarter()
        show(BuilderMode.Edit(id))
        compose.onNodeWithText("Edit routine").assertExists()
        compose.onNodeWithText("Next").assertDoesNotExist()
        compose.onNodeWithText("Schedule").performClick()
        compose.onNodeWithText("Delete routine").assertExists()
        compose.onNodeWithText("Sets").performClick()
        compose.onAllNodes(hasStateDescription("Collapsed") and hasClickAction())[0].performClick()
        compose.onNodeWithText("Custom timing").performScrollTo().performClick()
        compose.waitForIdle()
        // Seeded with the routine's own hold, so nothing differs yet.
        val routineHold = RoutineDraft.starter.plan.holdSeconds
        compose.onNode(hasContentDescription("Hold") and hasStateDescription("$routineHold seconds"))
            .performScrollTo()
            .performCustomAccessibilityActionWithLabel("Increase")
        compose.onNode(hasText("s hold", substring = true)).assertExists()
        compose.onNodeWithText("Custom timing").performClick()
        compose.onNode(hasText("s hold", substring = true)).assertDoesNotExist()
    }
}
