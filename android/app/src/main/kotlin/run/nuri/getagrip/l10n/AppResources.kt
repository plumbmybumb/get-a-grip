// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.l10n

import android.content.res.Resources
import java.util.Locale

/// The process's own `Resources`, published by `GetAGripApplication` so code with no
/// `Context` and no composition can still read a string.
///
/// It exists for ONE thing the engine's `L10n` cannot express: **a counted string.**
/// `L10n.lookup` is handed a key and nothing else, so it cannot choose between "1 manqué"
/// and "2 manqués" — Android needs the quantity at lookup time. `trQuantity` is that door.
/// Everything else non-composable goes through `L10n.tr`, and everything drawn goes through
/// the composable `tr`, which recomposes on a locale change.
///
/// Holding an Application `Resources` for the life of the process leaks nothing (the
/// Application outlives everything), and it is re-published on every configuration change
/// so a language switched under a running process is answered by the next read.
object AppResources {

    @Volatile
    var current: Resources? = null
}

/// A counted string, off the composition. `count` selects the plural form and is also the
/// first format argument, which is how every counted key in the catalog is written
/// (`%d missed`) — see the plural rules in the root `CLAUDE.md`.
///
/// With no resources (a JVM unit test, or before `Application.onCreate`) this degrades to
/// the English key formatted with its arguments, exactly as `L10n.tr` does.
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
