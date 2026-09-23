// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.store.DeviceStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// **A finished session is written down before anyone taps Save.** The draft store, and the
/// runner's finish that feeds it: a session used to exist only in memory between its last
/// rep and the summary's Save, with its foreground service already stopped — so a process
/// reclaimed in that window took the workout with it.
class FinishedSessionDraftTests {

    private val directory: File = Files.createTempDirectory("draft").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun draft() = FinishedSessionDraft(
        id = UUID.randomUUID(),
        templateID = UUID.randomUUID(),
        templateName = "Daily no-hangs",
        sessionsPerDayTarget = 2,
        plan = SessionPlan(name = "Daily no-hangs", sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 2))),
        reps = listOf(
            RepSummary(grip = GripSpec(), side = Side.left, heldSeconds = 7.0, peakKg = 12.5, avgKg = 11.0),
            RepSummary(grip = GripSpec(), side = Side.right, heldSeconds = 3.2, peakKg = 9.0, avgKg = 8.5,
                outcome = RepOutcome.skipped),
        ),
        startedAt = Instant.ofEpochMilli(1_790_000_000_000),
        finishedAt = Instant.ofEpochMilli(1_790_000_780_000),
    )

    @Test
    fun aDraftRoundTripsWithEverythingSaveNeeds() {
        val store = FinishedSessionDraftStore(File(directory, FinishedSessionDraftStore.FILE_NAME))
        val written = draft()
        assertTrue(store.save(written))
        assertEquals(written, store.load())
    }

    @Test
    fun noDraftAndClearedDraftReadAsNothing() {
        val store = FinishedSessionDraftStore(File(directory, FinishedSessionDraftStore.FILE_NAME))
        assertNull(store.load())
        store.save(draft())
        store.clear()
        assertNull(store.load())
    }

    /// A newer draft replaces the older one whole — one unsaved session at a time.
    @Test
    fun aNewDraftReplacesTheOld() {
        val store = FinishedSessionDraftStore(File(directory, FinishedSessionDraftStore.FILE_NAME))
        store.save(draft())
        val newer = draft()
        store.save(newer)
        assertEquals(newer.id, store.load()?.id)
    }

    /// A file this build cannot read is not offered: a prompt promising a session it cannot
    /// save is worse than none.
    @Test
    fun anUnreadableDraftIsNotOffered() {
        val file = File(directory, FinishedSessionDraftStore.FILE_NAME)
        file.writeText("{\"version\": 1, \"id\": \"not-a-uuid\"}")
        assertNull(FinishedSessionDraftStore(file).load())
        file.writeText("{truncated")
        assertNull(FinishedSessionDraftStore(file).load())
        val future = FinishedSessionDraftStore(file)
        future.save(draft())
        file.writeText(file.readText().replace("\"version\":1", "\"version\":99"))
        assertNull(future.load(), "a format from a newer build")
    }

    // MARK: - The runner's finish

    private class Service : SessionServiceController {
        var begins = 0
        var ends = 0
        override fun begin() { begins++ }
        override fun end() { ends++ }
    }

    private class Card : ActivityPublisher {
        override var isRunning = false
        var finished: String? = null
        var ends = 0
        override fun start(routineName: String, plannedReps: Int, setCount: Int, state: SessionActivityState) {
            isRunning = true
        }
        override fun update(state: SessionActivityState) = Unit
        override fun end() { if (isRunning) ends++; isRunning = false }
        override fun showFinished(routineName: String) { finished = routineName }
    }

    /// At the last rep: the draft is handed over ONCE, the stream stops, the card says
    /// "session done", and the service keeps the process alive until `end()`.
    @Test
    fun theFinishWritesTheDraftAndKeepsTheServiceUntilTheSummaryIsResolved() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.setState(ProgressorConnectionState.Connected)
        val service = Service()
        val card = Card()
        val session = RunnerSession(
            plan = SessionPlan(name = "Finish", sets = listOf(SetPlan(repsPerSide = 1)),
                handMode = HandMode.bothHands, holdSeconds = 1, leadInSeconds = 0),
            routineName = "Finish", device = device, scope = inertScope(), clock = FakeClock(),
            activity = card, service = service,
        )
        val handed = mutableListOf<SessionOutcome>()
        session.onFinished = { handed += it }
        session.begin()
        for (index in 1..120) {
            client.emit(run.nuri.getagrip.engine.ProgressorEvent.Sample(ForceSample(10.0, (index * 12_500).toUInt())))
        }
        session.send(RunnerEvent.Abort)

        assertTrue(session.isFinished)
        val outcome = assertNotNull(handed.singleOrNull(), "handed over exactly once")
        assertEquals(session.sessionID, outcome.id, "under the id its log row will carry")
        assertTrue(outcome.didAnyWork)
        assertFalse(device.isStreaming, "the summary reads no force")
        assertEquals(ProgressorCommand.stopWeightMeasurement, client.commands.last())
        assertEquals("Finish", card.finished)
        assertEquals(0, service.ends, "the service outlives the last rep")

        session.send(RunnerEvent.Tick)
        assertEquals(1, handed.size, "and never twice")

        session.end()
        assertEquals(1, service.ends, "until the summary is resolved")
    }
}
