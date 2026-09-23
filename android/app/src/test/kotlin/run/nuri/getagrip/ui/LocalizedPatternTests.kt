// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import run.nuri.getagrip.ui.l10n.LocalizedPattern
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** A language change under a running process must reach the dates too — the month names
 * used to be frozen at class load. */
class LocalizedPatternTests {
    private val original = Locale.getDefault()
    @AfterEach fun restore() = Locale.setDefault(original)

    @Test fun formatsInTheLocaleInForceNotTheOneTheClassLoadedIn() {
        val pattern = LocalizedPattern("d MMMM")
        val day = LocalDate.of(2026, 8, 3)
        Locale.setDefault(Locale.UK)
        assertEquals("3 August", pattern.format(day))
        Locale.setDefault(Locale.FRANCE)
        assertEquals("3 août", pattern.format(day))
    }

    @Test fun oneFormatterServesEveryRowInALocale() {
        val pattern = LocalizedPattern("d MMM")
        assertSame(pattern.formatter(Locale.UK), pattern.formatter(Locale.UK))
    }
}
