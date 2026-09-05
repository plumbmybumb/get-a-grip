// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import run.nuri.getagrip.ui.components.*
import run.nuri.getagrip.ui.theme.GetAGripTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SwipeActionRowTests {
    @get:Rule val compose = createComposeRule()

    @Test fun tinySwipeNeverDeletesAndFastRepeatedSwipesOnlyReveal() {
        var deletes = 0
        compose.setContent {
            GetAGripTheme {
                SwipeActionRow(onDelete = { deletes++ }, modifier = Modifier.width(360.dp).testTag("row")) {
                    Text("Workout", Modifier.fillMaxWidth().height(72.dp))
                }
            }
        }
        compose.onNodeWithTag("row").performTouchInput {
            swipe(center, center - Offset(12f, 0f), 200)
        }
        compose.runOnIdle { assertEquals(0, deletes) }
        repeat(2) {
            compose.onNodeWithTag("row").performTouchInput { swipeLeft(durationMillis = 80) }
            compose.runOnIdle { assertEquals(0, deletes) }
        }
        compose.onNodeWithText("Delete").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun oppositeSwipeRevealsShareAndTapUsesTheLatestCallback() {
        var result = 0
        var newest by mutableStateOf(1)
        compose.setContent {
            GetAGripTheme {
                val captured = newest
                SwipeActionRow(onDelete = { error("Sharing must never delete") }, onShare = { result = captured },
                    modifier = Modifier.width(360.dp).testTag("row")) {
                    Text("Workout", Modifier.fillMaxWidth().height(72.dp))
                }
            }
        }
        compose.onNodeWithTag("row").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0, result); newest = 2 }
        compose.onNodeWithText("Share").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, result) }
    }

    @Test fun tappingTheRevealedRowClosesItWithoutAnAction() {
        var deletes = 0
        compose.setContent {
            GetAGripTheme {
                SwipeActionRow(onDelete = { deletes++ }, modifier = Modifier.width(360.dp).testTag("row")) {
                    Text("Workout", Modifier.fillMaxWidth().height(72.dp))
                }
            }
        }
        compose.onNodeWithTag("row").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithTag("row").performTouchInput { click(centerLeft + Offset(20f, 0f)) }
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deletes) }
    }

    @Test fun rtlMirrorsTheDeleteGesture() {
        var deletes = 0
        compose.setContent {
            GetAGripTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SwipeActionRow(onDelete = { deletes++ }, modifier = Modifier.width(360.dp).testTag("row")) {
                        Text("Workout", Modifier.fillMaxWidth().height(72.dp))
                    }
                }
            }
        }
        compose.onNodeWithTag("row").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText("Delete").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun verticalScrollingDoesNotRevealOrDeleteRows() {
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            GetAGripTheme {
                val state = rememberLazyListState()
                listState = state
                LazyColumn(Modifier.size(360.dp, 400.dp).testTag("list"), state = state) {
                    items(30) { index ->
                        SwipeActionRow(onDelete = { error("Vertical scroll must not delete") }) {
                            Text("Workout $index", Modifier.fillMaxWidth().height(72.dp))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("list").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) }
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test fun undoTracksTheMeasuredMenuHeightIncludingLargeText() {
        var inset by mutableStateOf(88.dp)
        compose.setContent {
            GetAGripTheme {
                val host = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { host.showSnackbar("Session deleted", "Undo", duration = SnackbarDuration.Indefinite) }
                CompositionLocalProvider(LocalFloatingTabBarInset provides inset) {
                    Box(Modifier.size(360.dp, 640.dp).testTag("screen")) {
                        UndoSnackbar(host, Modifier.align(Alignment.BottomCenter))
                        Box(Modifier.fillMaxWidth().height(inset).align(Alignment.BottomCenter).testTag("menu"))
                    }
                }
            }
        }
        fun check() {
            val menu = compose.onNodeWithTag("menu").fetchSemanticsNode().boundsInRoot
            val undo = compose.onNodeWithText("Undo").fetchSemanticsNode().boundsInRoot
            assertTrue("Undo must sit above the menu", undo.bottom <= menu.top)
        }
        check()
        compose.runOnIdle { inset = 176.dp }
        check()
    }
}
