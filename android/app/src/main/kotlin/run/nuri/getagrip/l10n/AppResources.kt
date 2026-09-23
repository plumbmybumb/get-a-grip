// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.l10n

import android.content.res.Resources
import java.util.Locale

/// The process's `Resources`, published by `GetAGripApplication` so code with no `Context`
/// or composition can read a string.
///
/// It exists for ONE thing `L10n` cannot express: **a counted string.** `L10n.lookup` gets
/// only a key, so it cannot choose "1 manqué" versus "2 manqués"; `trQuantity` is that
/// door. Everything else uses `L10n.tr` or the composable `tr`.
///
/// Holding Application `Resources` leaks nothing, and it is re-published on configuration
/// change so a language switch is answered by the next read.
object AppResources {

    @Volatile
    var current: Resources? = null
}

/// A counted string, off the composition. `count` picks the plural form and is the first
/// format argument (`%d missed`). Without resources (JVM test, before `onCreate`) it
/// degrades to the formatted English key, like `L10n.tr`.
fun trQuantity(key: String, count: Int, vararg args: Any): String {
    val resources = AppResources.current
    val plural = PLURAL_KEYS[key]
    if (resources != null && plural != null) {
        return resources.getQuantityString(plural, count, count, *args)
    }
    val id = STRING_KEYS[key]
    if (resources != null && id != null) {
        return resources.getString(id, count, *args)
    }
    return runCatching {
        String.format(Locale.getDefault(), key, count, *args)
    }.getOrDefault(key)
}
