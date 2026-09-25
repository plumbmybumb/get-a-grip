// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import run.nuri.getagrip.store.SettingsStore
import run.nuri.getagrip.ui.runner.RunnerProgressStyle
import run.nuri.getagrip.ui.runner.RunnerProgressStyles
import run.nuri.getagrip.ui.settings.ProgressStyleSetting
import run.nuri.getagrip.ui.theme.GetAGripTheme
import kotlin.test.assertEquals

/// Settings › "Progress style (test)": labelled as a test-build control, verbatim, default
/// Stacked, and persisted under iOS's key so the choice survives a relaunch.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
class ProgressStyleSettingsTests {
    @get:Rule val compose = createComposeRule()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun after() { owner.cancel(); RunnerProgressStyles.current = RunnerProgressStyle.stacked }
    private fun finishWrites() = runBlocking { owner.coroutineContext[Job]!!.children.toList().joinAll() }

    @Test fun theTestPickerDefaultsToStackedAndPersistsAChoice() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(context, owner)
        assertEquals(RunnerProgressStyle.stacked, settings.runnerProgressStyle)
        compose.setContent { GetAGripTheme { ProgressStyleSetting(settings) } }
        compose.onNodeWithText("Test build · not in the App Store version", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Progress style (test)").assertIsDisplayed()
        compose.onNodeWithTag("settings.test.progressStyle.stacked").assertIsSelected()
        compose.onNodeWithText("Today").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(RunnerProgressStyle.baseline, RunnerProgressStyles.current) }
        finishWrites()
        compose.runOnIdle {
            RunnerProgressStyles.current = RunnerProgressStyle.stacked
            assertEquals(RunnerProgressStyle.baseline, SettingsStore(context, owner).runnerProgressStyle,
                "A fresh store reads the stored choice")
        }
    }
}
