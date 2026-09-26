// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import run.nuri.getagrip.store.LocalSettingsStore
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.PlayStoreListing
import run.nuri.getagrip.store.ReviewRequestPolicy
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// Whether the one-time rating ask is up. Owned by the root, above the runner's early return,
/// so its baseline survives the runner replacing the screen.
@Stable
class ReviewRequestState {
    var showing: Boolean by mutableStateOf(false)
}

/// Once, after the fifth saved session has closed — see `ReviewRequestPolicy`.
///
/// Decided HERE, not in the summary: on the settled screen, a beat after the runner is gone,
/// never from a tap on something else. Read off the store's save counter across the runner —
/// the baseline is taken when a runner OPENS — so a discarded session never counts, and
/// neither does a launch recovery saved behind the scenes.
///
/// The flag is written BEFORE the prompt shows, as on iOS: the ask never repeats on this
/// device whatever becomes of it.
@Composable
fun rememberReviewRequest(runnerOpen: Boolean): ReviewRequestState {
    val templates = LocalTemplateStore.current
    val settings = LocalSettingsStore.current
    val state = remember { ReviewRequestState() }
    var baseline by remember { mutableIntStateOf(templates.sessionsSavedThisLaunch) }
    LaunchedEffect(runnerOpen) {
        val saved = templates.sessionsSavedThisLaunch
        if (runnerOpen) {
            baseline = saved
            return@LaunchedEffect
        }
        if (saved <= baseline) return@LaunchedEffect
        baseline = saved
        if (!ReviewRequestPolicy.shouldAsk(templates.hangSessionCount(), settings.reviewRequested)) {
            return@LaunchedEffect
        }
        settings.setReviewRequested(true)
        delay(REVIEW_PROMPT_DELAY_MILLIS)
        state.showing = true
    }
    return state
}

/// A second after the runner closes: the screen has settled, and the finish is still fresh.
internal const val REVIEW_PROMPT_DELAY_MILLIS = 1_000L

/// The ask itself. It says WHY — free, open source, a rating helps other climbers find it —
/// the line Settings' permanent row carries too, and it only opens the Play listing: the
/// store's own rating UI does the rating. "Not now" costs nothing and is never asked again.
///
/// TRANSLATION NOTE: iOS calls the system prompt here, whose words are Apple's. Android's
/// counterpart needs Play's proprietary review library — see `ReviewRequestPolicy`.
@Composable
fun ReviewRequestDialog(state: ReviewRequestState) {
    if (!state.showing) return
    val context = LocalContext.current
    val palette = LocalGripPalette.current
    AlertDialog(
        onDismissRequest = { state.showing = false },
        title = { Text(tr("Rate Get a Grip")) },
        text = { Text(tr("A rating helps other climbers find Get a Grip.")) },
        confirmButton = {
            TextButton(onClick = {
                state.showing = false
                PlayStoreListing.open(context)
            }) { Text(tr("Rate on Google Play")) }
        },
        dismissButton = {
            TextButton(onClick = { state.showing = false }) { Text(tr("Not now")) }
        },
        containerColor = palette.card,
    )
}
