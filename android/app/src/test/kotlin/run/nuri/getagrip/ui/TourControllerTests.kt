// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import run.nuri.getagrip.ui.tour.InMemoryTourSeenStore
import run.nuri.getagrip.ui.tour.TourAct
import run.nuri.getagrip.ui.tour.TourAnchors
import run.nuri.getagrip.ui.tour.TourController
import run.nuri.getagrip.ui.tour.TourScript
import run.nuri.getagrip.ui.tour.TourTarget
import run.nuri.getagrip.ui.tour.cardOffsetPx

/// The spotlight tour's state machine, driven as a plain object.
///
/// The whole controller is testable because it reads and writes its `seen` flags through
/// `TourSeenStore` rather than touching DataStore — the same seam `RoutineSettings` gives
/// `TemplateStore`. A fresh `InMemoryTourSeenStore` is exactly a fresh install.
class TourControllerTests {

    @Test
    fun backFromHistoryRestoresTheTodaySpotlight() {
        val tour = controller()
        tour.begin(TourAct.Intro, hasRoutine = true)
        repeat(TourScript.today.indexOfFirst { it.target == TourTarget.HistoryMonth }) { tour.advance() }
        assertEquals(1, tour.current?.tab)
        tour.back()
        assertEquals(TourTarget.Consistency, tour.current?.target)
        assertEquals(0, tour.current?.tab)
        tour.advance()
        assertEquals(1, tour.current?.tab)
    }

    @Test
    fun presentedToursDoNotRequestATab() {
        for (act in listOf(TourAct.Builder, TourAct.Session)) {
            val tour = controller()
            tour.begin(act)
            assertNull(tour.current?.tab)
            tour.advance()
            assertNull(tour.current?.tab)
        }
    }

    private fun controller() = TourController(InMemoryTourSeenStore())

    // MARK: - Which act runs, and when

    /// **The act is chosen at RUNTIME, from whether a routine exists.** On a genuine first
    /// launch there is nothing to point at, so every step about the card would light a
    /// rectangle that is not on screen; the first-run script hands you to the builder instead.
    @Test
    fun withNoRoutineTheTourRunsTheFirstRunScript() {
        val tour = controller()
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = false)
        assertEquals(TourScript.firstRun.size, tour.steps.size)
        assertTrue(tour.awaitingRoutine, "act one steps aside for the builder")
        assertNull(tour.steps.first().target, "the opening card is about the app, not a control")
    }

    /// With a routine on screen the same act runs the script that has something to light.
    @Test
    fun withARoutineTheTourRunsTheTodayScript() {
        val tour = controller()
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = true)
        assertEquals(TourScript.today.size, tour.steps.size)
        assertFalse(tour.awaitingRoutine)
        assertEquals(TourTarget.RoutineCard, tour.steps.first().target)
    }

    /// Idempotent, because every caller is a `LaunchedEffect` that can fire more than once.
    /// Starting an act twice must not restart one already running.
    @Test
    fun beginningAnActAlreadyRunningDoesNotRestartIt() {
        val tour = controller()
        tour.begin(TourAct.Intro, hasRoutine = true)
        tour.advance()
        tour.advance()
        assertEquals(2, tour.index)
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = true)
        assertEquals(2, tour.index, "a second beginIfUnseen must not rewind the tour")
    }

    // MARK: - The seen flags

    /// Skipping counts as seen. Being asked twice whether you want the tour you already
    /// declined is worse than never offering it.
    @Test
    fun skippingMarksTheActSeenAndItNeverRunsAgain() {
        val store = InMemoryTourSeenStore()
        val tour = TourController(store)
        tour.begin(TourAct.Intro, hasRoutine = true)
        tour.finish()
        assertFalse(tour.isRunning)
        assertEquals(TourController.VERSION, store.tourSeenVersion(TourAct.Intro.rawValue))

        TourController(store).let {
            it.beginIfUnseen(TourAct.Intro, hasRoutine = true)
            assertFalse(it.isRunning, "a relaunch honours the Skip")
        }
    }

    /// **THE FLAG IS VERSIONED, not a Bool.** When the tour gains an act, a bumped version is
    /// what lets it run again for people who saw the old one — and a Bool would have no way
    /// to say that.
    @Test
    fun anOlderSeenVersionLetsTheActRunAgain() {
        val store = InMemoryTourSeenStore()
        store.setTourSeenVersion(TourAct.Intro.rawValue, TourController.VERSION - 1)
        val tour = TourController(store)
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = true)
        assertTrue(tour.isRunning, "a stale version is not 'seen'")
    }

    /// **Each act is seen on its own.** They happen minutes or days apart, and one Skip must
    /// not silently swallow the two you have not reached yet.
    @Test
    fun finishingOneActLeavesTheOthersUnseen() {
        val store = InMemoryTourSeenStore()
        val tour = TourController(store)
        tour.begin(TourAct.Intro, hasRoutine = true)
        tour.finish()
        assertEquals(0, store.tourSeenVersion(TourAct.Builder.rawValue))
        assertEquals(0, store.tourSeenVersion(TourAct.Session.rawValue))
    }

    /// The Settings row. `replay` clears ALL THREE flags — the builder and session acts fire
    /// minutes or days later, and a replay that only restarted the intro would never reach
    /// them — and it asks for Today, because a reset two tabs away from anywhere you could
    /// see it reads as a dead button.
    @Test
    fun replayClearsEveryActAndAsksForToday() {
        val store = InMemoryTourSeenStore()
        for (act in TourAct.entries) store.setTourSeenVersion(act.rawValue, TourController.VERSION)
        val tour = TourController(store)
        tour.replay(hasRoutine = true)
        for (act in TourAct.entries) {
            assertEquals(0, store.tourSeenVersion(act.rawValue), "${act.rawValue} still marked seen")
        }
        assertTrue(tour.isRunning)
        assertEquals(0, tour.requestedTab)
    }

    // MARK: - The hand-off to the builder and back

    /// **`builderOpened()` takes over from a RUNNING intro act.** Act one's last step is the
    /// hand-off that opened the document, and that step has now done its job — guarding on
    /// `!isRunning` left the intro's "Build one now" card sitting over the builder it had just
    /// opened.
    @Test
    fun builderOpenedTakesOverFromARunningIntroAct() {
        val tour = controller()
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = false)
        tour.advance() // onto the interactive "Build one now" step
        assertTrue(tour.isRunning)

        tour.builderOpened()
        assertEquals(TourAct.Builder, tour.act)
        assertEquals(TourScript.builder.size, tour.steps.size)
        assertEquals(0, tour.index)
    }

    /// Only in that window. Opening the builder a month later to add a set is not a moment
    /// for a tutorial.
    @Test
    fun builderOpenedDoesNothingWhenTheTourIsNotWaitingForARoutine() {
        val tour = controller()
        tour.builderOpened()
        assertNull(tour.act)
        assertFalse(tour.isRunning)
    }

    /// Seen already: stand down rather than leaving the intro card on top of the builder.
    @Test
    fun builderOpenedStandsDownWhenTheBuilderActIsAlreadySeen() {
        val store = InMemoryTourSeenStore()
        store.setTourSeenVersion(TourAct.Builder.rawValue, TourController.VERSION)
        val tour = TourController(store)
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = false)
        tour.advance()
        tour.builderOpened()
        assertFalse(tour.isRunning, "no card is left hanging over the builder")
    }

    /// **Reaching the end of act one is not the end of the tour.** It steps aside and waits
    /// for the builder rather than marking itself seen, or saving your first routine would
    /// drop you back onto a screen nobody has explained.
    @Test
    fun theEndOfActOneWaitsForARoutineRatherThanFinishing() {
        val store = InMemoryTourSeenStore()
        val tour = TourController(store)
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = false)
        repeat(TourScript.firstRun.size) { tour.advance() }
        assertFalse(tour.isRunning)
        assertTrue(tour.awaitingRoutine)
        assertEquals(0, store.tourSeenVersion(TourAct.Intro.rawValue), "not seen — only paused")
    }

    /// A routine now exists, so act one picks up with the script that has something to point
    /// at.
    @Test
    fun routineCreatedResumesActOneWithTheTodayScript() {
        val tour = controller()
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = false)
        repeat(TourScript.firstRun.size) { tour.advance() }
        tour.routineCreated()
        assertEquals(TourAct.Intro, tour.act)
        assertEquals(TourScript.today.size, tour.steps.size)
        assertFalse(tour.awaitingRoutine)
    }

    /// **Finishing the BUILDER act must leave `awaitingRoutine` standing**, or saving the
    /// routine would not resume act one. Only the intro act owns that flag.
    @Test
    fun finishingTheBuilderActLeavesActOneWaiting() {
        val tour = controller()
        tour.beginIfUnseen(TourAct.Intro, hasRoutine = false)
        tour.advance()
        tour.builderOpened()
        tour.finish()
        assertTrue(tour.awaitingRoutine, "act one is still owed a resume")
        tour.routineCreated()
        assertEquals(TourScript.today.size, tour.steps.size)
    }

    /// `routineCreated` is a no-op when the tour never handed you to the builder — opening the
    /// app with routines already in it must not start a tour somebody skipped.
    @Test
    fun routineCreatedDoesNothingWhenNoActWasWaiting() {
        val tour = controller()
        tour.routineCreated()
        assertNull(tour.act)
    }

    // MARK: - The session act's pause policy

    /// **The session act PAUSES the runner.** Teaching over a running clock costs the pull
    /// being explained, so the flag is true for exactly as long as the act is on screen.
    @Test
    fun theSessionActAsksForAPauseWhileItIsRunning() {
        val tour = controller()
        assertFalse(tour.sessionPausesRunner)
        tour.beginIfUnseen(TourAct.Session)
        assertTrue(tour.sessionPausesRunner)
        repeat(TourScript.session.size) { tour.advance() }
        assertFalse(tour.sessionPausesRunner, "the runner resumes when the act ends")
    }

    /// Skipping mid-act resumes too — the runner is not left paused because you declined the
    /// lesson.
    @Test
    fun skippingTheSessionActAlsoReleasesThePause() {
        val tour = controller()
        tour.beginIfUnseen(TourAct.Session)
        tour.finish()
        assertFalse(tour.sessionPausesRunner)
    }

    /// **Only the session act pauses.** The intro act runs over Today, where there is no clock
    /// to stop, and the builder act over a draft.
    @Test
    fun theOtherActsNeverAskForAPause() {
        val tour = controller()
        tour.begin(TourAct.Intro, hasRoutine = true)
        assertFalse(tour.sessionPausesRunner)
        tour.finish()
        tour.begin(TourAct.Builder)
        assertFalse(tour.sessionPausesRunner)
    }

    // MARK: - Walking the script

    @Test
    fun backNeverGoesBelowTheFirstStep() {
        val tour = controller()
        tour.begin(TourAct.Session)
        tour.back()
        assertEquals(0, tour.index)
    }

    /// The progress line is "n of m", one-based, so the first step reads "1 of 3".
    @Test
    fun progressIsOneBased() {
        val tour = controller()
        tour.begin(TourAct.Session)
        assertTrue(tour.progress.startsWith("1"), "got ${tour.progress}")
    }

    /// **Exactly two steps are interactive**, one per act that hands you a control: "Build one
    /// now" and the builder's Save. Those are the only two that punch the scrim's hit region,
    /// and the only two where tap-anywhere-to-advance is off.
    @Test
    fun onlyTheTwoHandOffStepsAreInteractive() {
        val interactive = (TourScript.firstRun + TourScript.today + TourScript.builder + TourScript.session)
            .filter { it.interactive }
            .map { it.target }
        assertEquals(listOf(TourTarget.BuildRoutine, TourTarget.BuilderFinish), interactive)
    }

    /// Every step that names a tab names a real one, and the tour returns to Today at the end.
    @Test
    fun everyTabbedStepNamesARealTab() {
        val tabs = TourScript.today.mapNotNull { it.tab }
        assertTrue(tabs.all { it in 0..3 }, "got $tabs")
        assertEquals(0, TourScript.today.last().tab, "the tour ends where it started")
    }

    // MARK: - The anchor registry

    /// **A LIST per target, and the host picks the VISIBLE one.** Any pager, lazy list or
    /// eager stack can legitimately have two views claiming one target, and only one of them
    /// is on screen; a last-wins merge punched the hole off the edge of the screen.
    @Test
    fun theMostVisibleRegistrantWinsTheTarget() {
        val anchors = TourAnchors()
        val host = Rect(0f, 0f, 400f, 800f)
        val offScreen = Rect(900f, 100f, 1300f, 200f)
        val onScreen = Rect(20f, 100f, 380f, 200f)
        anchors.register(TourTarget.BuilderSets, "spare", offScreen)
        anchors.register(TourTarget.BuilderSets, "visible", onScreen)
        assertEquals(onScreen, anchors.visibleRect(TourTarget.BuilderSets, host))
    }

    /// Registration order must not decide it — the off-screen page is just as likely to
    /// register second.
    @Test
    fun theVisibleRegistrantWinsWhicheverOrderTheyRegistered() {
        val anchors = TourAnchors()
        val host = Rect(0f, 0f, 400f, 800f)
        val onScreen = Rect(20f, 100f, 380f, 200f)
        anchors.register(TourTarget.BuilderSets, "visible", onScreen)
        anchors.register(TourTarget.BuilderSets, "spare", Rect(900f, 100f, 1300f, 200f))
        assertEquals(onScreen, anchors.visibleRect(TourTarget.BuilderSets, host))
    }

    /// A view that has not been laid out yet reports a zero-sized rect, and it would beat
    /// nothing. Dropped outright.
    @Test
    fun aZeroSizedRegistrantIsIgnored() {
        val anchors = TourAnchors()
        val host = Rect(0f, 0f, 400f, 800f)
        anchors.register(TourTarget.StartButton, "unlaid", Rect(0f, 0f, 0f, 0f))
        assertNull(anchors.visibleRect(TourTarget.StartButton, host))
    }

    /// Everything claiming this target is off screen, so nothing is lit — and the overlay's
    /// escape hatch (tap anywhere still advances) is what stops that becoming a dead screen.
    @Test
    fun anEntirelyOffScreenTargetResolvesToNothing() {
        val anchors = TourAnchors()
        val host = Rect(0f, 0f, 400f, 800f)
        anchors.register(TourTarget.Consistency, "below", Rect(20f, 1200f, 380f, 1300f))
        assertNull(anchors.visibleRect(TourTarget.Consistency, host))
    }

    /// A control that left the composition must stop claiming its target, or a step would
    /// light where it used to be.
    @Test
    fun unregisteringRemovesTheClaim() {
        val anchors = TourAnchors()
        val host = Rect(0f, 0f, 400f, 800f)
        anchors.register(TourTarget.RoutineCard, "card", Rect(20f, 100f, 380f, 300f))
        assertNotNull(anchors.visibleRect(TourTarget.RoutineCard, host))
        anchors.unregister(TourTarget.RoutineCard, "card")
        assertNull(anchors.visibleRect(TourTarget.RoutineCard, host))
    }

    // MARK: - Where the callout sits

    /// BELOW the lit control when there is room under it. The card must never cover the thing
    /// it is describing, which is the one job this arithmetic has.
    @Test
    fun theCalloutSitsBelowAControlWithRoomUnderIt() {
        val y = cardOffsetPx(
            spotlight = Rect(20f, 100f, 380f, 260f),
            cardHeight = 200f,
            hostHeight = 900f,
            floor = 70f,
            bottomInset = 120f,
        )
        assertTrue(y >= 260f, "expected the card below the spotlight, got $y")
        assertTrue(y + 200f <= 780f, "and clear of the bottom inset")
    }

    /// ABOVE it when there is not — a card that would run under the tab bar is a card with
    /// unreachable buttons.
    @Test
    fun theCalloutSitsAboveAControlNearTheBottom() {
        val spotlight = Rect(20f, 620f, 380f, 800f)
        val y = cardOffsetPx(spotlight, cardHeight = 200f, hostHeight = 900f, floor = 70f, bottomInset = 120f)
        assertTrue(y + 200f <= spotlight.top, "expected the card above the spotlight, got $y")
    }

    /// Centred when nothing is lit — a step about the app rather than about a control.
    @Test
    fun theCalloutCentresWhenNothingIsLit() {
        val y = cardOffsetPx(null, cardHeight = 200f, hostHeight = 900f, floor = 70f, bottomInset = 120f)
        assertEquals(350f, y)
    }

    /// **Clamped to the PHYSICAL edges, because the host ignores the safe area** — it has to,
    /// so the scrim covers the status bar and the navigation bar. A four-line card on a short
    /// screen lands on the floor rather than off the top of it.
    @Test
    fun aTallCalloutIsClampedToTheFloorRatherThanRunningOffTheTop() {
        val y = cardOffsetPx(
            spotlight = Rect(20f, 80f, 380f, 700f),
            cardHeight = 400f,
            hostHeight = 800f,
            floor = 70f,
            bottomInset = 120f,
        )
        assertTrue(y >= 70f, "the card must never sit above the floor, got $y")
    }
}
