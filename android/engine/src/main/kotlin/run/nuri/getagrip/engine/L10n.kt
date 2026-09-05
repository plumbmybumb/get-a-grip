// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.util.Locale

/// Display strings inside the engine — `FingerSet.name`, `GripPosition.name`, the
/// default routine name, `Side.prompt` — are localized on iOS through
/// `String(localized:)`, where the KEY is the English sentence and the catalog carries
/// the French. The Kotlin engine keeps the same discipline with no Android dependency:
/// the key is the English text (with Java format specifiers: `%d`, `%s`, `%.1f`), and
/// the app installs a `lookup` backed by its string resources, generated from the same
/// `.xcstrings` catalog by `android/scripts/xcstrings_to_android.py`.
///
/// Nothing on the WIRE goes through here — keys, tokens, blobs and the analysis export
/// are deliberately never localized.
object L10n {
    @Volatile
    var lookup: ((String) -> String?)? = null

    fun tr(key: String): String = lookup?.invoke(key) ?: key

    fun tr(key: String, vararg args: Any?): String =
        String.format(Locale.getDefault(), tr(key), *args)
}
