// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import kotlin.test.Test
import kotlin.test.assertEquals

/// The two pure rules under both connected clients' reconnect: how the next attempt reaches
/// the gauge, and whether the platform's scan quota can afford a scan right now.
class ReconnectPolicyTests {

    /// **A dropped link waits for the SAME gauge, with no scan.** Android pauses unfiltered
    /// scans with the screen off, so the old scan ladder behind a locked screen found
    /// nothing, spent its attempts and gave up on a session's gauge for good.
    @Test
    fun aDroppedLinkWaitsForItsOwnGaugeWithoutScanning() {
        assertEquals(AttemptRoute.awaitInRange,
            AttemptRouting.route(hasRemembered = true, recovering = true, directTriesLeft = 0))
        assertEquals(AttemptRoute.awaitInRange,
            AttemptRouting.route(hasRemembered = true, recovering = true, directTriesLeft = 1))
    }

    /// An explicit Connect gets one quick direct try at the remembered unit, then scans —
    /// the person may be holding a different gauge of the same kind today.
    @Test
    fun anExplicitConnectTriesTheRememberedGaugeOnceThenScans() {
        assertEquals(AttemptRoute.direct,
            AttemptRouting.route(hasRemembered = true, recovering = false, directTriesLeft = 1))
        assertEquals(AttemptRoute.scan,
            AttemptRouting.route(hasRemembered = true, recovering = false, directTriesLeft = 0))
    }

    @Test
    fun withNothingRememberedThereIsOnlyTheScan() {
        for (recovering in listOf(false, true)) {
            assertEquals(AttemptRoute.scan,
                AttemptRouting.route(hasRemembered = false, recovering = recovering, directTriesLeft = 1))
        }
    }

    /// Four starts in a 31 s window, then wait for the oldest to age out — one under the
    /// platform's five, with a second's margin on the window.
    @Test
    fun theFifthStartInAWindowWaitsForTheOldestToAgeOut() {
        val budget = ScanStartBudget()
        for (t in listOf(0.0, 2.0, 4.0, 6.0)) {
            assertEquals(0.0, budget.delaySeconds(t))
            budget.recordStart(t)
        }
        assertEquals(31.0 - 8.0, budget.delaySeconds(8.0), 1e-9)
        assertEquals(0.0, budget.delaySeconds(31.0), "the first start has aged out")
    }

    /// The platform's own "too frequently" outranks our count: a full window of cooldown.
    @Test
    fun aTooFrequentRefusalBuysAFullWindow() {
        val budget = ScanStartBudget()
        budget.noteTooFrequent(10.0)
        assertEquals(31.0, budget.delaySeconds(10.0), 1e-9)
        assertEquals(0.0, budget.delaySeconds(41.0))
    }
}
