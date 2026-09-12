// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.ui.builder.SetRowView
import run.nuri.getagrip.ui.builder.StablePlan
import run.nuri.getagrip.ui.theme.GetAGripTheme

/** Exercise the real builder row: a codec test cannot catch a stale UI-only cap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuilderPullCountTests {
    @get:Rule val compose = createComposeRule()

    @Test fun stepperPassesTwentyAndTypedCountsReachTheStorageLimit() {
        var set by mutableStateOf(SetPlan(repsPerSide = 19))
        compose.setContent {
            GetAGripTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SetRowView(
                        plan = StablePlan(SessionPlan(sets = listOf(set))), setID = set.id,
                        isExpanded = true, maxes = MaxTable(), percentBandsVary = false,
                        canMoveUp = false, canMoveDown = false,
                        onTap = {}, onEditGrip = {}, onMoveUp = {}, onMoveDown = {},
                        onDuplicate = {}, onRemove = {}, onSetChange = { set = it },
                    )
                }
            }
        }
        val plus = compose.onNodeWithContentDescription("Increase Pulls per side")
        plus.performScrollTo().performClick()
        plus.performClick()
        compose.runOnIdle { assertEquals(21, set.repsPerSide) }

        for (count in listOf(36, 100)) {
            compose.onNodeWithContentDescription("Pulls per side,", substring = true)
                .performScrollTo().performClick()
            val field = compose.onNode(hasSetTextAction())
            field.performTextInput(count.toString())
            field.performImeAction()
            compose.runOnIdle { assertEquals(count, set.repsPerSide) }
        }
        plus.assertIsNotEnabled()
    }
}
