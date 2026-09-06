// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import run.nuri.getagrip.ui.builder.BuilderScrollAnchors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuilderScrollAnchorsTests {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollingKeepsDestinationsStableWithoutRecomposingTheDocument() {
        val anchors = BuilderScrollAnchors()
        lateinit var scroll: ScrollState
        lateinit var scope: CoroutineScope
        var compositions = 0
        var expectedOffset = 0
        var expanded by mutableStateOf(false)
        var showTarget by mutableStateOf(true)
        compose.setContent {
            scroll = rememberScrollState()
            scope = rememberCoroutineScope()
            expectedOffset = with(LocalDensity.current) { (if (expanded) 640.dp else 320.dp).roundToPx() }
            SideEffect { compositions++ }
            Column(Modifier.height(180.dp).verticalScroll(scroll)) {
                Column(Modifier.onGloballyPositioned(anchors::contentPlaced)) {
                    Spacer(Modifier.height(if (expanded) 640.dp else 320.dp))
                    if (showTarget) Spacer(Modifier.height(44.dp).onGloballyPositioned { anchors.placed("set", it) })
                    Spacer(Modifier.height(640.dp))
                }
            }
        }
        compose.runOnIdle { assertEquals(expectedOffset, anchors.offset("set")) }
        var beforeScroll = 0
        compose.runOnIdle {
            beforeScroll = compositions
            scope.launch { scroll.scrollTo(150) }
        }
        compose.runOnIdle {
            assertEquals(150, scroll.value)
            assertEquals(beforeScroll, compositions)
            assertEquals(expectedOffset, anchors.offset("set"))
            expanded = true
        }
        compose.runOnIdle {
            assertTrue(expectedOffset > 320)
            assertEquals(expectedOffset, anchors.offset("set"))
            showTarget = false
        }
        compose.runOnIdle { assertNull(anchors.offset("set")) }
    }
}
