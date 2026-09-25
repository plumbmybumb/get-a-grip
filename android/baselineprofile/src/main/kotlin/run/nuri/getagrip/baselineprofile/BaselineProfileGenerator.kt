// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/// **The journeys the shipped profile is recorded from** — the three that are slowest on a
/// cold process: launching onto Today, switching tabs, and opening the builder.
///
/// **Screens are found by their ENGLISH text** ("Today", "Skip", "Build my routine"), so run
/// the generator on a device or emulator whose language is English — on any other locale
/// the steps find nothing, are skipped, and the profile silently shrinks to the launch.
///
/// Every step is written to survive the app's first-launch state (the intro tour, no
/// routine yet) and every later one (tour seen, a routine saved by nobody), because the rule
/// runs the journey repeatedly against the same install until the profile is stable. A step
/// whose control is not on screen is skipped, never failed: a missing tap costs the profile
/// a few methods, a failure costs it everything.
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    /// **Launch onto Today, and nothing else** — the one journey also written as the STARTUP
    /// profile, which lays the dex out so the classes the first frame needs are read
    /// together. Only launch belongs there: a startup profile that also carried the tabs and
    /// the builder would spread the first frame's classes across everything else, which is
    /// the layout it exists to prevent.
    @Test fun startup() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        launchOntoToday()
    }

    /// **The tabs and the builder**, after a launch — the baseline profile only.
    @Test fun tabsAndBuilder() = rule.collect(packageName = PACKAGE) {
        launchOntoToday()

        // The builder, from Today's own door — the first-run card or the deck's ghost card.
        if (openBuilder() || (skipTourIfShown() && openBuilder())) {
            skipTourIfShown()
            // The whole document, eagerly built: scroll it end to end and back.
            device.findObject(By.scrollable(true))?.let { page ->
                page.fling(Direction.DOWN)
                page.fling(Direction.UP)
            }
            find(By.text("Cancel"))?.click()
            // Untouched, Cancel closes outright; answer the dialog if anything changed.
            device.wait(Until.findObject(By.text("Discard")), 1_000)?.click()
            device.waitForIdle()
        }

        for (tab in listOf("History", "Benchmarks", "Settings")) {
            tabBar(tab)?.click()
            device.waitForIdle()
            skipTourIfShown()
        }
        // Settings' one push, and back.
        find(By.textContains("Tap to choose yours"))?.let {
            it.click()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
        tabBar("Today")?.click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.launchOntoToday() {
        pressHome()
        startActivityAndWait()
        // On a fresh install the intro tour arrives a beat AFTER the first frame (it waits to
        // learn whether a routine exists), and its scrim swallows taps meant for the page.
        device.wait(Until.hasObject(By.text("Today")), TIMEOUT)
        skipTourIfShown(firstLaunch = true)
    }

    private fun MacrobenchmarkScope.find(selector: BySelector) =
        device.wait(Until.findObject(selector), TIMEOUT)

    /// The LAST match: the bar is drawn after the page, and "Today" is also the page's title.
    private fun MacrobenchmarkScope.tabBar(label: String) =
        find(By.text(label))?.let { device.findObjects(By.text(label)).lastOrNull() }

    /// True when the builder's document is on screen.
    private fun MacrobenchmarkScope.openBuilder(): Boolean {
        val door = find(By.text("Build my routine")) ?: find(By.text("New routine")) ?: return false
        door.click()
        return device.wait(Until.hasObject(By.desc("Routine name")), TIMEOUT)
    }

    /// True when there was a tour step to skip.
    private fun MacrobenchmarkScope.skipTourIfShown(firstLaunch: Boolean = false): Boolean {
        val skip = device.wait(Until.findObject(By.text("Skip")), if (firstLaunch) 3_000 else 1_500)
        skip?.click()
        device.waitForIdle()
        return skip != null
    }

    private companion object {
        const val PACKAGE = "run.nuri.getagrip"
        const val TIMEOUT = 5_000L
    }
}
