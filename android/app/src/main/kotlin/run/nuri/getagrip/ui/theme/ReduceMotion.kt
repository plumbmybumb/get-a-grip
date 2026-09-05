// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

private val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun MotionPreferences(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    fun read() = if (inspection) false else runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    var reduced by remember(context, inspection) { mutableStateOf(read()) }
    DisposableEffect(context, inspection) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        if (!inspection) resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { if (!inspection) resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalReduceMotion provides reduced, content = content)
}

@Composable
fun rememberReduceMotion(): Boolean = LocalReduceMotion.current
