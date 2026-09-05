// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import run.nuri.getagrip.ui.l10n.tr

/// Ten seconds of Undo, and no confirmation dialog anywhere.
///
/// Delete carries no dialog — the same bargain the whole app makes. A dialog in front of
/// every swipe is a tax on the taps that meant it and trains people to dismiss it blindly;
/// ten seconds of Undo costs the confident nothing.
///
/// **Shown only when the delete LANDED.** `deleted` is the store's own undo slot
/// (`lastDeletedSession`), which `persistAndSync` only fills after the write committed —
/// a failed delete rolls back, and an unguarded offer would let Undo insert a SECOND copy
/// of a row that is still there. Nothing here decides that; it just refuses to speak until
/// the store says so.
///
/// TRANSLATION NOTE: iOS floats its own `UndoBar` in a `safeAreaInset`. The Android house
/// answer is the Snackbar, which already owns the bottom inset, the timing and the action
/// affordance — `SnackbarDuration.Long` is the platform's ten seconds. What is NOT
/// delegated is the trigger and the haptic: the bar appears because the slot filled, and
/// the success tick fires on the UNDO tap, which is the action, never on the value
/// changing.
@Composable
fun UndoSnackbar(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    // The menu floats over tab content. Its measured height also covers large-text layouts.
    SnackbarHost(hostState, modifier.padding(bottom = LocalFloatingTabBarInset.current))
}

/// Raises the bar whenever `deleted` becomes non-null, and calls `onUndo` if the action is
/// tapped before it times out.
///
/// Keyed on the deleted value's IDENTITY, so two deletes in a row raise two bars rather
/// than one that silently keeps pointing at the first row. `onUndo` and `onExpired` are
/// held through `rememberUpdatedState`: the effect outlives a recomposition and must call
/// the LATEST lambda, not the one captured when the bar went up.
@Composable
fun <T : Any> UndoSnackbarEffect(
    hostState: SnackbarHostState,
    deleted: T?,
    message: String,
    actionLabel: String = tr("Undo"),
    onUndo: () -> Unit,
    onExpired: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val undo by rememberUpdatedState(onUndo)
    val expired by rememberUpdatedState(onExpired)
    val key = remember(deleted) { deleted }

    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = false,
            // The platform's ten seconds. iOS times its own bar; here the host does, and
            // the store's own undo window is armed for the same ten.
            duration = SnackbarDuration.Long,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> {
                undo()
                // **The haptic names its cause**: it fires on the tap that put the row
                // back, never on the bar appearing. `LongPress` is the same "it landed"
                // tick `GaugeScreen` spends on a completed tare — the closest thing the
                // platform offers to iOS's `.sensoryFeedback(.success)`.
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            SnackbarResult.Dismissed -> expired()
        }
    }
}
