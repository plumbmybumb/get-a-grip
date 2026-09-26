// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.*
import run.nuri.getagrip.ui.builder.BuilderMode
import run.nuri.getagrip.ui.builder.RoutineBuilderHost
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import kotlin.test.assertEquals

/// **Page 1 fits one phone screen while creating** — the measurement the three-page design
/// was chosen for, asserted rather than eyeballed.
///
/// The window is 360 × 668 dp: a 360 × 740 dp phone less a 24 dp status bar and a 48 dp
/// three-button navigation bar, the tightest case (Robolectric draws neither). The page's
/// scroll range must be ZERO there.
///
/// With `BUILDER_SHOTS=<dir>` in the environment it also writes the review screenshots of all
/// three pages, an open set, Custom timing and edit mode, at 360 × 740 dp.
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BuilderPageFitTests {
    @get:Rule val compose = createComposeRule()

    private val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = SettingsStore(RuntimeEnvironment.getApplication(), settingsScope)
    private val store = TemplateStore(RoomStoreGateway(db), DayClock(), settings, RecordingAlarmScheduler(), scope)
    private val shots: java.io.File? = System.getenv("BUILDER_SHOTS")?.let { java.io.File(it).apply { mkdirs() } }

    @After fun close() { scope.cancel(); settingsScope.cancel(); db.close() }

    @Composable
    private fun Host(mode: BuilderMode, dark: Boolean = false, fontScale: Float? = null) {
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalTemplateStore provides store, LocalSettingsStore provides settings) {
                GetAGripTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(LocalGripPalette.current.field)) {
                        RoutineBuilderHost(mode = mode, onDone = {})
                    }
                }
            }
        }
        if (fontScale == null) content() else {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
        }
    }

    /// The largest scroll offset any vertically scrolling node on screen allows, in px.
    private fun overflow(): Float = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    ).fetchSemanticsNodes().maxOfOrNull {
        it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    } ?: 0f

    private fun shot(name: String) {
        val dir = shots ?: return
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        java.io.File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /// The starter routine at 20–30 %, with maxes on two grips so a set can say what the band
    /// means per hand, and the rest missing so the Rhythm page's warning shows.
    private fun seedWorld(): RoutineDraft {
        val draft = RoutineDraft.starter.let { it.copy(plan = it.plan.copy(targetLoPercent = 0.2, targetHiPercent = 0.3)) }
        runBlocking {
            store.recordMax(20.0, draft.plan.sets[0].grip, side = Side.left)
            store.recordMax(22.0, draft.plan.sets[0].grip, side = Side.right)
            store.recordMax(18.0, draft.plan.sets[1].grip, side = Side.left)
            store.recordMax(20.0, draft.plan.sets[1].grip, side = Side.right)
        }
        return draft
    }

    @Config(sdk = [35], qualifiers = "w360dp-h668dp-xhdpi")
    @Test fun pageOneFitsA360By740PhoneWhileCreating() {
        store.clearDraft()
        compose.setContent { Host(BuilderMode.AddAnother) }
        compose.onNodeWithText("Rhythm").assertExists()
        shot("fit_blank")
        assertEquals(0f, overflow(), "page 1 must not scroll on a 360 × 740 dp phone")
    }

    /// With four sets, a routine band and the no-max warning — the fullest page 1 gets.
    @Config(sdk = [35], qualifiers = "w360dp-h668dp-xhdpi")
    @Test fun pageOneStillFitsWithABandAndItsWarning() {
        val draft = seedWorld()
        store.stashDraft(draft.copy(plan = draft.plan.copy(sets = draft.plan.sets.take(4))))
        compose.setContent { Host(BuilderMode.AddAnother) }
        compose.onNodeWithText("Some grips have no max yet, so their sets have no target.").assertExists()
        shot("fit_band")
        assertEquals(0f, overflow(), "page 1 must not scroll on a 360 × 740 dp phone")
    }

    @Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
    @Test fun reviewScreenshotsWhenAsked() {
        if (shots == null) return
        val draft = seedWorld()
        store.stashDraft(draft)
        compose.setContent { Host(BuilderMode.AddAnother) }
        shot("create1")
        compose.onNodeWithText("Next").performClick()
        shot("create2")
        compose.onAllNodes(hasStateDescription("Collapsed") and hasClickAction())[1].performClick()
        shot("create2_set_open")
        compose.onNodeWithText("Custom timing").performScrollTo().performClick()
        compose.onNode(hasContentDescription("Hold") and hasStateDescription("10 seconds"))
            .performCustomAccessibilityActionWithLabel("Increase")
        compose.onNode(hasContentDescription("Rest between pulls") and hasStateDescription("20 seconds"))
            .performScrollTo()
        shot("create2_custom_timing")
        compose.onNodeWithText("Next").performClick()
        shot("create3")
    }

    @Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
    @Test fun editScreenshotsWhenAsked() {
        if (shots == null) return
        val draft = seedWorld()
        runBlocking {
            db.routines().upsert(SessionTemplateEntity.from(draft.normalized, 0))
            store.syncDerived()
        }
        compose.setContent { Host(BuilderMode.Edit(store.routines.single().id)) }
        shot("edit1")
        compose.onNodeWithText("Sets").performClick()
        shot("edit2")
        compose.onNodeWithText("Schedule").performClick()
        shot("edit3")
    }

    @Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
    @Test fun darkAndLargeTextScreenshotsWhenAsked() {
        if (shots == null) return
        store.stashDraft(seedWorld())
        compose.setContent { Host(BuilderMode.AddAnother, dark = true) }
        shot("create1_dark")
    }

    @Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
    @Test fun largeTextScreenshotWhenAsked() {
        if (shots == null) return
        store.stashDraft(seedWorld())
        compose.setContent { Host(BuilderMode.AddAnother, fontScale = 2f) }
        shot("create1_large_text")
    }
}
