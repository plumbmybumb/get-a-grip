// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.RepSummary
import java.util.UUID

/// The two whole tables History and the Maxes tab draw from.
///
/// TRANSLATION NOTE: iOS reads these with `@Query` — a live, sorted view of the store that
/// costs nothing until a screen asks for it. `TemplateStore` deliberately publishes only
/// what is DERIVED from more than one table (today's counts, the 14-day strip, the newest
/// max per grip), and its `StoreGateway` is private, so there is no Android equivalent of
/// "just query the table" for the two screens whose whole job is the raw ledger:
///
/// - History needs EVERY log (the 5-week deck walks back to the first one) and every max
///   record, because the export's MAX HISTORY section is the append-only progression
///   itself rather than the newest-per-grip fold.
/// - The Maxes tab needs every max record for the same reason: a card IS one grip's rows
///   drawn as a curve.
///
/// So this is the `@Query` twin: two published lists, refreshed on demand. It is a READER
/// and never a writer — every mutation still goes through `TemplateStore` so the
/// derived-recompute and reminder-replan pipeline can never be skipped. Screens call
/// `refresh()` after a store write lands, which is the Android price for not having a live
/// query.
@Stable
class HistoryFeed(
    private val source: HistorySource,
    private val scope: CoroutineScope,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /// **Newest first**, matching iOS's `@Query(sort: startedAt, order: .reverse)` — the
    /// session you are most likely looking for is the one you just did.
    var logs: List<WorkoutLogEntity> by mutableStateOf(emptyList())
        private set

    /// **Oldest first**, matching iOS's max query: each grip's slice is then already in
    /// chart order, and the export wants the progression in the order it happened.
    var maxRecords: List<MaxRecordEntity> by mutableStateOf(emptyList())
        private set

    /// False until the first successful read. A screen that drew its empty state before
    /// the first fetch landed would flash "nothing here yet" over a full history — and,
    /// worse, a READ THAT FAILED is not "there are nothing": `HistorySource` returns null
    /// for a failure and this stays false, so the last good lists keep being drawn rather
    /// than being replaced by a blank world.
    var hasLoaded: Boolean by mutableStateOf(false)
        private set

    /// Decoded rep blobs, once per log EVER.
    ///
    /// `WorkoutLogEntity.resultsData` is write-once, so this cache never invalidates — and
    /// without it every recomposition would re-decode the whole history: a screen that
    /// walks every log's JSON on the main thread is a tab that gets slower every week you
    /// train, which is the house rule this exists to obey. A plain HashMap behind a
    /// non-observable field, deliberately: mutating it must not invalidate anything.
    ///
    /// A deleted log leaves its entry behind, which is deliberate rather than a leak: undo
    /// re-inserts the SAME id carrying byte-identical `resultsData`, so the stale entry is
    /// still the right answer and the restored row draws without re-decoding.
    private val repsByID = HashMap<UUID, List<RepSummary>>()

    fun reps(log: WorkoutLogEntity): List<RepSummary> =
        repsByID.getOrPut(log.id) { log.reps }

    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    /// Superseded reads must not overwrite a newer delete/undo/save. Cancel their work
    /// and check the generation too, for sources whose read ignores cancellation.
    /// Sorting the unbounded history belongs off the UI thread.
    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val fetchedLogs = source.allLogs()
            val fetchedMaxes = source.allMaxes()
            // Either read failing means the world is unknown, not empty. Publish nothing.
            if (fetchedLogs == null || fetchedMaxes == null || generation != refreshGeneration) return@launch
            val ordered = withContext(processingDispatcher) {
                fetchedLogs.sortedByDescending { it.startedAt } to fetchedMaxes.sortedBy { it.recordedAt }
            }
            if (generation != refreshGeneration) return@launch
            logs = ordered.first
            maxRecords = ordered.second
            hasLoaded = true
        }
    }

    companion object {
        /// A feed over two fixed lists — previews, and any screen that wants to draw a
        /// world without a database behind it.
        fun of(
            logs: List<WorkoutLogEntity>,
            maxRecords: List<MaxRecordEntity>,
            scope: CoroutineScope,
        ): HistoryFeed = HistoryFeed(StaticHistorySource(logs, maxRecords), scope)
            .also { it.refresh() }
    }
}

/// The read half of the store, named as its own interface so a preview can satisfy it with
/// two lists.
///
/// **Null means the read FAILED, which is not the same fact as "there is nothing".** Same
/// contract as `StoreGateway`, and for the same reason: collapsing the two blanks a screen
/// on a transient error.
interface HistorySource {
    suspend fun allLogs(): List<WorkoutLogEntity>?
    suspend fun allMaxes(): List<MaxRecordEntity>?
}

/// Narrow a `StoreGateway` to the two reads this feed makes. The gateway is the app's one
/// door to Room; this borrows it rather than opening a second.
fun StoreGateway.asHistorySource(): HistorySource = object : HistorySource {
    override suspend fun allLogs(): List<WorkoutLogEntity>? = this@asHistorySource.allLogs()
    override suspend fun allMaxes(): List<MaxRecordEntity>? = this@asHistorySource.allMaxes()
}

private class StaticHistorySource(
    private val logs: List<WorkoutLogEntity>,
    private val maxes: List<MaxRecordEntity>,
) : HistorySource {
    override suspend fun allLogs(): List<WorkoutLogEntity> = logs
    override suspend fun allMaxes(): List<MaxRecordEntity> = maxes
}

/// See `LocalDeviceStore` for why this is `staticCompositionLocalOf`.
val LocalHistoryFeed: ProvidableCompositionLocal<HistoryFeed> = staticCompositionLocalOf {
    error("LocalHistoryFeed was read outside a CompositionLocalProvider")
}
