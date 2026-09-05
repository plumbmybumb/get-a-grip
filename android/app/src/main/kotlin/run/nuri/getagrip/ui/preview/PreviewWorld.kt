// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.HistorySource
import run.nuri.getagrip.store.InMemoryRoutineSettings
import run.nuri.getagrip.store.LocalDayClock
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.RecordingAlarmScheduler
import run.nuri.getagrip.store.StoreGateway
import run.nuri.getagrip.store.StoreWriter
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.theme.GetAGripTheme
import java.util.UUID

/// A whole world for the `@Preview`s of History and Maxes — theme, clock, store and feed —
/// backed by three in-memory lists.
///
/// It exists because both screens read `LocalTemplateStore` (routine names, the newest max
/// per grip, the delete/undo hub) and neither can be rendered by Android Studio without
/// one. Reads only: the gateway's `write` is a no-op, so a preview that taps a destructive
/// control changes nothing and cannot crash.
@Composable
fun PreviewWorld(
    logs: List<WorkoutLogEntity> = SeededHistory.logs(),
    maxes: List<MaxRecordEntity> = SeededHistory.maxes(),
    routines: List<SessionTemplateEntity> = emptyList(),
    content: @Composable (HistoryFeed) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clock = remember { DayClock() }
    val gateway = remember(logs, maxes, routines) { PreviewGateway(logs, maxes, routines) }
    val templates = remember(gateway) {
        TemplateStore(
            gateway = gateway,
            clock = clock,
            settings = InMemoryRoutineSettings(),
            scheduler = RecordingAlarmScheduler(),
            scope = scope,
        )
    }
    val feed = remember(gateway) { HistoryFeed(gateway.asPreviewSource(), scope) }

    LaunchedEffect(templates) {
        templates.syncDerived()
        feed.refresh()
    }

    GetAGripTheme {
        CompositionLocalProvider(
            LocalDayClock provides clock,
            LocalTemplateStore provides templates,
            LocalHistoryFeed provides feed,
        ) {
            content(feed)
        }
    }
}

private class PreviewGateway(
    private val logs: List<WorkoutLogEntity>,
    private val maxes: List<MaxRecordEntity>,
    private val routines: List<SessionTemplateEntity>,
) : StoreGateway {
    override suspend fun allRoutines() = routines
    override suspend fun routine(id: UUID) = routines.firstOrNull { it.id == id }
    override suspend fun logsFrom(dayKey: Int) = logs.filter { it.dayKey >= dayKey }
    override suspend fun allLogs() = logs
    override suspend fun allMaxes() = maxes
    override suspend fun write(work: suspend (StoreWriter) -> Unit) = Unit

    fun asPreviewSource(): HistorySource = object : HistorySource {
        override suspend fun allLogs() = logs
        override suspend fun allMaxes() = maxes
    }
}

/// Five weeks of a twice-a-day habit with a couple of gym days and one benchmark morning in
/// it, so every glyph the grid can draw is on screen at once — plus two grips with a real
/// max progression behind them, so the Maxes card has a curve rather than a single point.
object SeededHistory {

    fun logs(today: DayStamp = DayStamp.today()): List<WorkoutLogEntity> {
        val out = mutableListOf<WorkoutLogEntity>()
        for (back in 0 until 40) {
            val day = today - back
            // A missed day every week, so the grid is a habit rather than a solid block.
            if (back % 7 == 3) continue
            val rounds = if (back % 5 == 0) 1 else 2
            repeat(rounds) {
                out += WorkoutLogEntity.from(
                    plan = SessionPlan(), templateID = null, templateName = "Daily no-hangs",
                    sessionsPerDayTarget = 2, reps = emptyList(),
                    startedAt = day.startOfDay().toInstant(),
                    finishedAt = day.startOfDay().toInstant().plusSeconds(21 * 60),
                    day = day,
                ).copy(completedReps = 36, plannedReps = 36, totalHeldSeconds = 216.0, peakKg = 12.8)
            }
            if (back == 4 || back == 11) {
                out += WorkoutLogEntity.logged(
                    kind = SessionKind.climbLimit, day = day, at = day.startOfDay().toInstant(),
                    sessionsPerDayTarget = 2, minutes = 120,
                )
            }
            if (back == 8) {
                out += WorkoutLogEntity.logged(
                    kind = SessionKind.benchmark, day = day, at = day.startOfDay().toInstant(),
                    sessionsPerDayTarget = 2,
                )
            }
        }
        return out
    }

    fun maxes(today: DayStamp = DayStamp.today()): List<MaxRecordEntity> {
        val fourFinger = GripSpec()
        val out = mutableListOf<MaxRecordEntity>()
        listOf(60 to 28.0, 30 to 29.5, 8 to 31.0).forEach { (back, kg) ->
            out += MaxRecordEntity.from(
                grip = fourFinger, kg = kg, source = MaxSource.measured, side = Side.left,
                recordedAt = (today - back).startOfDay().toInstant(),
            )
            out += MaxRecordEntity.from(
                grip = fourFinger, kg = kg - 2.0, source = MaxSource.measured, side = Side.right,
                recordedAt = (today - back).startOfDay().toInstant(),
            )
        }
        out += MaxRecordEntity.from(
            grip = GripSpec(edgeMM = 20, fingers = run.nuri.getagrip.engine.FingerSet.frontTwo),
            kg = 18.5, source = MaxSource.manual, side = Side.both,
            recordedAt = (today - 20).startOfDay().toInstant(),
        )
        return out
    }
}
