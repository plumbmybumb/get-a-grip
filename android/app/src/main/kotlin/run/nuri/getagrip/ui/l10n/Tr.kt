// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.l10n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import run.nuri.getagrip.l10n.PLURAL_KEYS
import run.nuri.getagrip.l10n.STRING_KEYS
import java.util.Locale

/// **The KEY IS THE ENGLISH SENTENCE.** Every user-visible string in this app is written
/// as the English it says, with Java format specifiers where it takes arguments:
///
///     Text(tr("Set %d of %d", set, total))
///
/// which is the same key the iOS app hands `String(localized:)` and the same one
/// `:engine` hands `L10n.tr`. One inventory, one French translation, and a sentence that
/// cannot drift between the two apps — the resource id is an implementation detail
/// `STRING_KEYS` looks up, generated from the iOS catalogs by
/// `android/scripts/xcstrings_to_android.py`.
///
/// A new string is written the same way: as its English sentence, added to the iOS
/// catalog (or, if it genuinely has no iOS twin, to `android/scripts/android_extra.json`)
/// and regenerated. **Never a bare literal** — a literal is a sentence that exists in one
/// language only, and nothing on screen says so.
///
/// `stringResource` is what makes this recompose: it reads the configuration, so flipping
/// the phone to French redraws every one of these without the app restarting.
///
/// A key with no resource behind it FALLS BACK to itself, formatted. That is the English
/// sentence, which is the right thing to show and the wrong thing to ship — the
/// `StringCatalogTests` are what stop it reaching a build.
@Composable
@ReadOnlyComposable
fun tr(key: String, vararg args: Any): String {
    val plural = PLURAL_KEYS[key]
    if (plural != null) {
        return pluralStringResource(plural, count(args), *args)
    }
    val id = STRING_KEYS[key] ?: return fallback(key, args)
    return if (args.isEmpty()) stringResource(id) else stringResource(id, *args)
}

/// The same lookup off the composition — a notification, a share sheet's text, anything
/// built from a `Context` rather than drawn. `Resources.getString` formats against the
/// resources' OWN locale, so this and the composable above cannot disagree.
fun Context.tr(key: String, vararg args: Any): String {
    val plural = PLURAL_KEYS[key]
    if (plural != null) {
        return resources.getQuantityString(plural, count(args), *args)
    }
    val id = STRING_KEYS[key] ?: return fallback(key, args)
    return if (args.isEmpty()) getString(id) else getString(id, *args)
}

/// The quantity a plural selects on. It is the FIRST argument by construction: every
/// counted string in the catalog leads with its count (`%lld minutes`), which is also why
/// `variations.plural` is only ever put on single-specifier keys — see the French rules in
/// the root `CLAUDE.md`.
private fun count(args: Array<out Any>): Int = (args.firstOrNull() as? Number)?.toInt() ?: 0

private fun fallback(key: String, args: Array<out Any>): String {
    if (args.isEmpty()) return key
    return runCatching { String.format(Locale.getDefault(), key, *args) }.getOrDefault(key)
}
