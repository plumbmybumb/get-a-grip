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
    /// The store's write counter (`TemplateStore.writeRevision`), read — never observed — so
    /// the feed can tell a tab switch from a change. Zero forever for a feed over fixed lists.
    private val revision: () -> Long = { 0L },
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

    // MARK: - Folded with the read, off the main thread
    //
    // Each of these is a pass over the whole ledger. They used to be `remember`ed in the
    // screens, which a tab switch throws away — so every visit to History or Maxes walked the
    // entire history on the main thread again, however long ago it last changed. They are
    // computed beside the sort now and published with the lists they describe.

    /// The odometer — columns only, never a blob.
    var lifetime: LifetimeStats by mutableStateOf(LifetimeStats())
        private set

    /// The Maxes tab's cards, one per grip.
    var maxGroups: List<MaxGripGroup> by mutableStateOf(emptyList())
        private set

    /// The grip each session opened on, decoded from its rep blob here rather than by a row
    /// scrolling into view on the main thread.
    private var leadingGrips: Map<UUID, GripSpec?> by mutableStateOf(emptyMap())

    fun leadingGrip(log: WorkoutLogEntity): GripSpec? = leadingGrips[log.id]

    /// Decoded rep blobs, once per log EVER.
    ///
    /// `WorkoutLogEntity.resultsData` is write-once, so this cache never invalidates — and
    /// without it every recomposition would re-decode the whole history: a screen that
    /// walks every log's JSON on the main thread is a tab that gets slower every week you
    /// train, which is the house rule this exists to obey. Concurrent, because the fold above
    /// and the export both fill it OFF the main thread; not observable, because filling it
    /// must not invalidate anything.
    ///
    /// A deleted log leaves its entry behind, which is deliberate rather than a leak: undo
    /// re-inserts the SAME id carrying byte-identical `resultsData`, so the stale entry is
    /// still the right answer and the restored row draws without re-decoding.
    private val repsByID = ConcurrentHashMap<UUID, List<RepSummary>>()

    fun reps(log: WorkoutLogEntity): List<RepSummary> =
        repsByID[log.id] ?: log.reps.also { repsByID.putIfAbsent(log.id, it) }

    /// The month grid's fold, kept for the lists it was folded from. Asked for from
    /// composition; answered from the memo whenever the ledger and the day have not moved,
    /// and refolded off the main thread by `refresh` for the day it was last asked about.
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

    /// The store revision the published lists were read at, and the one the read in flight
    /// started from. Captured BEFORE the read, so a write that lands during it leaves the
    /// feed one behind and the next look reads again.
    private var loadedRevision = -1L
    private var requestedRevision = -1L

    /// **Read only when something was written since the last read.** Screens call this on
    /// the way in; a tab switch with nothing written is free. A never-loaded or failed feed
    /// always reads.
    fun refreshIfStale() {
        val current = revision()
        if (hasLoaded && current == loadedRevision) return
        if (refreshJob?.isActive == true && current == requestedRevision) return
        refresh()
    }

    /// Superseded reads must not overwrite a newer delete/undo/save. Cancel their work
    /// and check the generation too, for sources whose read ignores cancellation.
    /// Sorting the unbounded history belongs off the UI thread — and so does every fold of it.
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

// MARK: - Grouping

/// One grip's records, every hand mixed — the per-side slices are cut in the card.
data class MaxGripGroup(val key: String, val grip: GripSpec, val records: List<MaxRecordEntity>)

/// Most recently tested grip first — the one you are mid-progression on leads. Ties break on
/// the key, so two grips tested in one sitting don't swap places between launches.
///
/// Grouped on `gripKey` and NOT on `maxKey`, unlike the management list: a CARD is about one
/// grip and draws both hands as two lines on one chart, where a ROW is about one number and
/// must keep the hands apart. Folded here, beside the read, so the Maxes tab never walks the
/// table on the main thread.
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
