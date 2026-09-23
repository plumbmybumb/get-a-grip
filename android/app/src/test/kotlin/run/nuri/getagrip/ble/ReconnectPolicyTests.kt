// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The rules under both connected clients' reconnect: how the next attempt reaches the
/// gauge, what is remembered between links, and whether the platform's scan quota can afford
/// a scan right now.
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

    // MARK: - RememberedGauge

    @Test
    fun aFirstConnectHasNothingToRememberSoItScans() {
        val gauge = RememberedGauge<String>()
        gauge.connectRequested()
        assertNull(gauge.device)
        assertEquals(AttemptRoute.scan, gauge.route())
    }

    /// Connect → one direct try → scans. The try is spent by CHOOSING it, so a direct attempt
    /// that times out is followed by a scan, not by a second direct try.
    @Test
    fun anExplicitConnectSpendsItsOneDirectTryThenScans() {
        val gauge = RememberedGauge<String>()
        gauge.established("unit-1")
        gauge.connectRequested()
        assertEquals(AttemptRoute.direct, gauge.route())
        assertEquals(AttemptRoute.scan, gauge.route())
        assertEquals(AttemptRoute.scan, gauge.route())
    }

    /// An established link dropped: wait for THAT device, on every attempt, until it is back.
    @Test
    fun aLostEstablishedLinkWaitsForItsOwnDeviceUntilItReturns() {
        val gauge = RememberedGauge<String>()
        gauge.established("unit-1")
        gauge.attaching(autoConnect = false)
        assertFalse(gauge.linkLost(), "a direct link has no GATT left open to close")
        gauge.awaitReturn()
        assertEquals(AttemptRoute.awaitInRange, gauge.route())
        assertEquals(AttemptRoute.awaitInRange, gauge.route(), "however long it takes")

        gauge.established("unit-1")
        gauge.connectRequested()
        assertEquals(AttemptRoute.direct, gauge.route(), "back, so the wait is over")
    }

    /// An autoConnect link is reported ONCE, so its GATT is closed once.
    @Test
    fun aLostAutoConnectLinkIsReportedForClosingExactlyOnce() {
        val gauge = RememberedGauge<String>()
        gauge.established("unit-1")
        gauge.awaitReturn()
        gauge.attaching(autoConnect = true)
        assertTrue(gauge.linkLost())
        assertFalse(gauge.linkLost())
    }

    /// Letting go ends any recovery: the next Connect starts from a direct try, not a wait.
    @Test
    fun releasingTheLinkEndsTheWaitForTheRememberedDevice() {
        val gauge = RememberedGauge<String>()
        gauge.established("unit-1")
        gauge.awaitReturn()
        gauge.released()
        assertEquals(AttemptRoute.scan, gauge.route())
        assertEquals("unit-1", gauge.device, "the device itself is still remembered")
    }

    // MARK: - BudgetedScanStart

    private class FakeClock(var uptime: Double = 0.0) : HostClock {
        override fun wallSeconds() = uptime
        override fun uptimeSeconds() = uptime
    }

    @Test
    fun anAffordableStartGoesOutNowAndIsCounted() {
        val scope = TestScope(StandardTestDispatcher())
        val budget = ScanStartBudget()
        val starts = BudgetedScanStart(scope, budget, FakeClock(), scope.coroutineContext)
        assertFalse(starts.deferIfOverBudget(stillWanted = { true }) { error("not deferred") })
        starts.started()
        assertEquals(0.0, budget.delaySeconds(0.0), "one start is well inside the quota")
    }

    /// Over the quota the start waits for the oldest to age out, then retries — unless the
    /// attempt that asked has been superseded, or the wait was cancelled with the attempt.
    @Test
    fun anUnaffordableStartWaitsThenRetriesOnlyIfStillWanted() {
        val scope = TestScope(StandardTestDispatcher())
        val clock = FakeClock()
        val budget = ScanStartBudget()
        repeat(4) { budget.recordStart(0.0) }
        val starts = BudgetedScanStart(scope, budget, clock, scope.coroutineContext)

        var retried = 0
        assertTrue(starts.deferIfOverBudget(stillWanted = { true }) { retried += 1 })
        scope.advanceTimeBy(30_999)
        scope.runCurrent()
        assertEquals(0, retried, "not before the window has passed")
        scope.advanceUntilIdle()
        assertEquals(1, retried)

        assertTrue(starts.deferIfOverBudget(stillWanted = { false }) { retried += 1 })
        scope.advanceUntilIdle()
        assertEquals(1, retried, "a superseded attempt does not start a scan")

        assertTrue(starts.deferIfOverBudget(stillWanted = { true }) { retried += 1 })
        starts.cancel()
        scope.advanceUntilIdle()
        assertEquals(1, retried, "a cancelled wait does not either")
    }

    @Test
    fun onlyATooFrequentRefusalBuysACooldown() {
        val clock = FakeClock(uptime = 5.0)
        val budget = ScanStartBudget()
        val scope = TestScope()
        val starts = BudgetedScanStart(scope, budget, clock, scope.coroutineContext)
        starts.failed(errorCode = 2)
        assertEquals(0.0, budget.delaySeconds(5.0))
        starts.failed(errorCode = ScanStartBudget.scanTooFrequentError)
        assertEquals(31.0, budget.delaySeconds(5.0), 1e-9)
    }
}
