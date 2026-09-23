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
import run.nuri.getagrip.data.LifetimeStats
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.data.lifetime
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RepSummary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/// The two whole tables History and the Maxes tab draw from.
///
/// TRANSLATION NOTE: iOS reads these with a live `@Query`. `TemplateStore` publishes only
/// what is DERIVED from several tables and keeps its `StoreGateway` private, so the two
/// raw-ledger screens need this:
///
/// - History needs EVERY log (the 5-week deck walks back to the first) and every max record
///   (the export's MAX HISTORY is the append-only progression, not the newest-per-grip
///   fold).
/// - The Maxes tab needs every max record: a card IS one grip's rows drawn as a curve.
///
/// A READER, never a writer — mutations still go through `TemplateStore`. Screens call
/// `refresh()` after a write lands, the Android price for no live query.
@Stable
class HistoryFeed(
    private val source: HistorySource,
    private val scope: CoroutineScope,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /// The store's write counter, read (never observed) so a tab switch can be told from a
    /// change. Zero forever over fixed lists.
    private val revision: () -> Long = { 0L },
) {

    /// **Newest first**, like iOS's `@Query(sort: startedAt, order: .reverse)`.
    var logs: List<WorkoutLogEntity> by mutableStateOf(emptyList())
        private set

    /// **Oldest first**, like iOS's max query: each grip's slice is in chart order, and the
    /// export wants the order it happened.
    var maxRecords: List<MaxRecordEntity> by mutableStateOf(emptyList())
        private set

    /// False until the first successful read, so a screen does not flash "nothing here yet"
    /// over a full history. A FAILED read (`HistorySource` null) leaves this and the last
    /// good lists alone rather than publishing a blank world.
    var hasLoaded: Boolean by mutableStateOf(false)
        private set

    // MARK: - Folded with the read, off the main thread
    //
    // Each is a pass over the whole ledger. `remember`ed in the screens, a tab switch threw
    // them away and every visit re-walked history on the main thread; now they are computed
    // beside the sort and published with their lists.

    /// The odometer — columns only, never a blob.
    var lifetime: LifetimeStats by mutableStateOf(LifetimeStats())
        private set

    /// The Maxes tab's cards, one per grip.
    var maxGroups: List<MaxGripGroup> by mutableStateOf(emptyList())
        private set

    /// The grip each session opened on, decoded here rather than on the main thread as a
    /// row scrolls in.
    private var leadingGrips: Map<UUID, GripSpec?> by mutableStateOf(emptyMap())

    fun leadingGrip(log: WorkoutLogEntity): GripSpec? = leadingGrips[log.id]

    /// Decoded rep blobs, once per log EVER. `resultsData` is write-once, so this never
    /// invalidates; without it every recomposition re-decodes the history and the tab gets
    /// slower every week you train. Concurrent (the fold and the export fill it off the
    /// main thread); not observable (filling must invalidate nothing).
    ///
    /// A deleted log's entry stays, deliberately: undo re-inserts the SAME id with
    /// byte-identical `resultsData`, so it is still right.
    private val repsByID = ConcurrentHashMap<UUID, List<RepSummary>>()

    fun reps(log: WorkoutLogEntity): List<RepSummary> =
        repsByID[log.id] ?: log.reps.also { repsByID.putIfAbsent(log.id, it) }

    /// The month grid's fold, memoized for the lists it came from; refolded off the main
    /// thread by `refresh` for the day last asked about.
    fun ledger(today: DayStamp, trackingSince: DayStamp?): DayLedger {
        val memo = ledgerMemo
        if (memo != null && memo.logs === logs && memo.today == today && memo.trackingSince == trackingSince) {
            return memo.ledger
        }
        return DayLedger(logs, today, trackingSince).also { ledgerMemo = LedgerMemo(logs, today, trackingSince, it) }
    }

    private class LedgerMemo(
        val logs: List<WorkoutLogEntity>,
        val today: DayStamp,
        val trackingSince: DayStamp?,
        val ledger: DayLedger,
    )

    private var ledgerMemo: LedgerMemo? = null

    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    /// The store revision the lists were read at, and the one the in-flight read started
    /// from. Captured BEFORE the read, so a write during it leaves the feed one behind and
    /// the next look rereads.
    private var loadedRevision = -1L
    private var requestedRevision = -1L

    /// **Read only when something was written since the last read.** A tab switch with
    /// nothing written is free; a never-loaded or failed feed always reads.
    fun refreshIfStale() {
        val current = revision()
        if (hasLoaded && current == loadedRevision) return
        if (refreshJob?.isActive == true && current == requestedRevision) return
        refresh()
    }

    /// A superseded read must not overwrite a newer delete/undo/save: cancel it and check
    /// the generation too, for sources that ignore cancellation. Sorting and folding the
    /// unbounded history stay off the UI thread.
    fun refresh() {
        val generation = ++refreshGeneration
        val readAt = revision()
        requestedRevision = readAt
        val ledgerKey = ledgerMemo
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val fetchedLogs = source.allLogs()
            val fetchedMaxes = source.allMaxes()
            // Either read failing means the world is unknown, not empty. Publish nothing.
            if (fetchedLogs == null || fetchedMaxes == null || generation != refreshGeneration) return@launch
            val folded = withContext(processingDispatcher) {
                val ordered = fetchedLogs.sortedByDescending { it.startedAt }
                val maxes = fetchedMaxes.sortedBy { it.recordedAt }
                Folded(
                    logs = ordered,
                    maxRecords = maxes,
                    lifetime = ordered.lifetime,
                    maxGroups = groupsOf(maxes),
                    leadingGrips = ordered.associate { it.id to reps(it).firstOrNull()?.grip },
                    ledger = ledgerKey?.let { LedgerMemo(ordered, it.today, it.trackingSince,
                        DayLedger(ordered, it.today, it.trackingSince)) },
                )
            }
            if (generation != refreshGeneration) return@launch
            logs = folded.logs
            maxRecords = folded.maxRecords
            lifetime = folded.lifetime
            maxGroups = folded.maxGroups
            leadingGrips = folded.leadingGrips
            folded.ledger?.let { ledgerMemo = it }
            loadedRevision = readAt
            hasLoaded = true
        }
    }

    private class Folded(
        val logs: List<WorkoutLogEntity>,
        val maxRecords: List<MaxRecordEntity>,
        val lifetime: LifetimeStats,
        val maxGroups: List<MaxGripGroup>,
        val leadingGrips: Map<UUID, GripSpec?>,
        val ledger: LedgerMemo?,
    )

    companion object {
        /// A feed over two fixed lists, for previews and screens without a database.
        fun of(
            logs: List<WorkoutLogEntity>,
            maxRecords: List<MaxRecordEntity>,
            scope: CoroutineScope,
        ): HistoryFeed = HistoryFeed(StaticHistorySource(logs, maxRecords), scope)
            .also { it.refresh() }
    }
}

// MARK: - Grouping

/// One grip's records, every hand mixed — the per-side slices are cut in the card.
data class MaxGripGroup(val key: String, val grip: GripSpec, val records: List<MaxRecordEntity>)

/// Most recently tested grip first; ties break on the key so two grips tested together
/// don't swap between launches.
///
/// Grouped on `gripKey`, NOT `maxKey` (unlike the management list): a CARD draws both hands
/// as two lines on one chart, while a ROW is one number and keeps them apart. Folded beside
/// the read, off the main thread.
internal fun groupsOf(records: List<MaxRecordEntity>): List<MaxGripGroup> {
    val byKey = LinkedHashMap<String, MutableList<MaxRecordEntity>>()
    // `records` arrive oldest first, so each bucket is already in chart order.
    for (record in records) byKey.getOrPut(record.gripKey) { mutableListOf() }.add(record)
    return byKey
        .map { (key, rows) -> MaxGripGroup(key, rows.last().grip, rows) }
        .sortedWith(
            compareByDescending<MaxGripGroup> { it.records.last().recordedAt }.thenBy { it.key },
        )
}

/// The read half of the store, its own interface so a preview can satisfy it with two
/// lists.
///
/// **Null means the read FAILED, not "there is nothing"** — the `StoreGateway` contract;
/// collapsing them blanks a screen on a transient error.
interface HistorySource {
    suspend fun allLogs(): List<WorkoutLogEntity>?
    suspend fun allMaxes(): List<MaxRecordEntity>?
}

/// Narrow a `StoreGateway` to this feed's two reads: the app's one door to Room, borrowed
/// rather than a second one opened.
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
