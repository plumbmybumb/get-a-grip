// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.units

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.SettingsStore
import run.nuri.getagrip.ui.builder.TargetBandRow
import run.nuri.getagrip.ui.maxes.MaxEntryDraft
import run.nuri.getagrip.ui.maxes.MaxEntrySheet
import run.nuri.getagrip.ui.preview.PreviewWorld
import run.nuri.getagrip.ui.settings.WeightUnitSetting
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1100dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeightUnitSettingsTests {
    @get:Rule val compose = createComposeRule()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val oldLocale = Locale.getDefault()
    @Before fun before() { Locale.setDefault(Locale.US); WeightUnits.current = WeightUnit.kg }
    @After fun after() { owner.cancel(); WeightUnits.current = WeightUnit.kg; Locale.setDefault(oldLocale) }
    private fun finishWrites() = runBlocking { owner.coroutineContext[Job]!!.children.toList().joinAll() }

    @Test fun settingSwitchesImmediatelyAndSurvivesFreshStoreCreation() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(context, owner)
        settings.setWeightUnit(WeightUnit.kg)
        finishWrites()
        compose.setContent { GetAGripTheme { WeightUnitSetting(settings) } }
        compose.onNodeWithText("Pounds · lb").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(WeightUnit.lb, settings.weightUnit) }
        finishWrites()
        compose.runOnIdle { assertEquals(WeightUnit.lb, SettingsStore(context, owner).weightUnit) }
        compose.onNodeWithText("Kilograms · kg").performClick().assertIsSelected()
        finishWrites()
        compose.runOnIdle { assertEquals(WeightUnit.kg, SettingsStore(context, owner).weightUnit) }
    }

    @Test fun rapidUnitChangesPersistTheFinalVisibleChoice() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(context, owner)
        repeat(50) { settings.setWeightUnit(if (it % 2 == 0) WeightUnit.kg else WeightUnit.lb) }
        assertEquals(WeightUnit.lb, settings.weightUnit)
        finishWrites()
        assertEquals(WeightUnit.lb, SettingsStore(context, owner).weightUnit)
    }

    @Test fun actualManualMaxFieldStoresPoundsAsKgAndUntouchedEditingPreservesPrecision() {
        WeightUnits.current = WeightUnit.lb
        val draft = MaxEntryDraft().apply { receiveMeasured(12.3456789, Side.left) }
        compose.setContent { PreviewWorld { MaxEntrySheet(draft, {}, {}) } }
        val value = compose.onNodeWithContentDescription("Max on this grip, 27.2 lb. Double tap to type a value.")
        value.performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(12.3456789, draft.kg, 0.0); assertEquals(MaxSource.measured, draft.source) }
        value.performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("22.7")
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(22.7 * 0.45359237, draft.kg, 1e-12); assertEquals(MaxSource.manual, draft.source) }
        compose.onNodeWithContentDescription("Max on this grip, 22.7 lb. Double tap to type a value.").assertIsDisplayed()
        capture("android-max-entry-lb.png")
    }

    @Test fun actualTargetFieldsConvertTypedBoundsWhilePercentMeaningStaysUnchanged() {
        WeightUnits.current = WeightUnit.lb
        var set by mutableStateOf(SetPlan(grip = GripSpec(), targetLoKg = 10.0, targetHiKg = 15.0))
        compose.setContent { GetAGripTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                TargetBandRow(set, MaxTable(), HandMode.bothHands) { set = it }
            }
        } }
        compose.onNodeWithText("Target load").performClick()
        compose.onNodeWithContentDescription("Lower bound, 22.0 lb. Double tap to type a value.").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("20.0")
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(20.0 * 0.45359237, set.targetLoKg!!, 1e-12); assertEquals(15.0, set.targetHiKg) }
        compose.onNodeWithText("Upper bound").performScrollTo()
        capture("android-target-entry-lb.png")
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../../build/review/weight-units", name).apply { parentFile?.mkdirs() }
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
