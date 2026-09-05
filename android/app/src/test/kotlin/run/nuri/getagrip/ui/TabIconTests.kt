// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import run.nuri.getagrip.ui.components.ClimbingIcon
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The four tabs, decided for good in Phase 8b.
///
/// There is not much pure logic in a tab bar, which is exactly why this is worth pinning: the
/// icons and their ORDER are an identity decision that a stray import can change silently, and
/// nothing in a build failure would say so. The mapping from the SF Symbols the iOS app names
/// is recorded on `Tab` and in `android/CLAUDE.md`.
class TabIconTests {

    /// **Order is the tab bar's contract**, and it is not only cosmetic: `TourStep.tab` is an
    /// INDEX, so History at 1, Maxes at 2 and Settings at 3 is what makes the tour's
    /// tab-switching steps land where they say they do.
    @Test
    fun theFourTabsAreInTheOrderTheTourIndexesThem() {
        assertEquals(listOf(Tab.Today, Tab.History, Tab.Maxes, Tab.Settings), Tab.entries)
        assertEquals(0, Tab.entries.indexOf(Tab.Today))
        assertEquals(1, Tab.entries.indexOf(Tab.History))
        assertEquals(2, Tab.entries.indexOf(Tab.Maxes))
        assertEquals(3, Tab.entries.indexOf(Tab.Settings))
    }

    /// The four chosen icons, by identity. `Scale` is the one that changed: it replaced
    /// `FitnessCenter`, a dumbbell, because this app MEASURES a load rather than lifting
    /// weights — and the gym-equipment metaphor is the one Frez leans on.
    @Test
    fun theTabsCarryTheIconsThatWereChosen() {
        assertEquals(ClimbingIcon, Tab.Today.icon)
        assertEquals(Icons.AutoMirrored.Outlined.ShowChart, Tab.History.icon)
        assertEquals(Icons.Outlined.Scale, Tab.Maxes.icon)
        assertEquals(Icons.Outlined.Settings, Tab.Settings.icon)
    }

    /// Four tabs, four different glyphs. A repeated icon reads as a broken build long before
    /// anyone reads the labels.
    @Test
    fun noTwoTabsShareAGlyph() {
        assertEquals(Tab.entries.size, Tab.entries.map { it.icon.name }.toSet().size)
    }

    /// **History's chart comes from the AUTO-MIRRORED set**, and it is the only one that
    /// should: a line climbing left to right means the opposite in an RTL layout, where a
    /// scale, a gear and a climbing figure all mean exactly what they meant. The vector's own
    /// name carries the package it was built in, which is the only handle a JVM test has on
    /// the distinction.
    @Test
    fun onlyTheChartComesFromTheAutoMirroredSet() {
        assertTrue(
            Tab.History.icon.name.contains("AutoMirrored"),
            "a chart has a reading direction; got ${Tab.History.icon.name}",
        )
        for (tab in listOf(Tab.Today, Tab.Maxes, Tab.Settings)) {
            assertTrue(
                !tab.icon.name.contains("AutoMirrored"),
                "${tab.name} should not mirror; got ${tab.icon.name}",
            )
        }
    }

    /// **The label is resolved on READ, never baked in at construction.** Enum entries are
    /// built once when the class loads, so a translated string captured in the constructor
    /// would keep the language it was born in after the phone's language changed under a
    /// running process. Without a string lookup installed, `L10n.tr` echoes the key — which is
    /// the English, and is what this asserts.
    @Test
    fun everyTabResolvesItsLabelOnRead() {
        assertEquals("Today", Tab.Today.label)
        assertEquals("History", Tab.History.label)
        assertEquals("Maxes", Tab.Maxes.label)
        assertEquals("Settings", Tab.Settings.label)
    }
}
