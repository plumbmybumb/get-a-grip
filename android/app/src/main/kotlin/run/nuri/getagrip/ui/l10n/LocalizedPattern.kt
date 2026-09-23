// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.l10n

import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/// **A date pattern, resolved against the locale in force when it FORMATS.**
///
/// The screens used to hold `DateTimeFormatter.ofPattern(…, Locale.getDefault())` in
/// top-level vals, and a top-level val is built once, when its file's class first loads. So
/// the month names were frozen in whatever language the process started in: change the
/// phone's (or the app's own) language and every string on screen followed — except the
/// dates, still in the old one, until the process died. The same trap `Tab.label` is a
/// getter to avoid.
///
/// `Locale.getDefault()` is what the platform updates when the configuration's locale
/// changes — the same answer `LocalConfiguration` gives a composable — and it can be read
/// from the pure formatting functions these patterns live in, which a composition local
/// cannot. The formatter is cached for the locale it was built for, like `ValueRow`'s number
/// format, so a list of rows builds one formatter, not one per row.
class LocalizedPattern(private val pattern: String) {
    private class Built(val locale: Locale, val formatter: DateTimeFormatter)

    @Volatile private var built: Built? = null

    fun formatter(locale: Locale = Locale.getDefault()): DateTimeFormatter {
        built?.let { if (it.locale == locale) return it.formatter }
        return DateTimeFormatter.ofPattern(pattern, locale).also { built = Built(locale, it) }
    }

    fun format(temporal: TemporalAccessor): String = formatter().format(temporal)
}
