// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Claims a Save immediately, before its coroutine or the next UI frame can run. */
@Stable
internal class SubmissionState {
    var isRunning by mutableStateOf(false)
        private set

    fun launch(scope: CoroutineScope, action: suspend () -> Unit) {
        if (isRunning) return
        isRunning = true
        scope.launch { action() }.invokeOnCompletion {
            // Completion also runs when the scope is cancelled before the body starts.
            isRunning = false
        }
    }
}
