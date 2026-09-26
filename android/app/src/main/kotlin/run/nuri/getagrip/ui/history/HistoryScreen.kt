// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import run.nuri.getagrip.ui.l10n.LocalizedPattern
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.AnalysisExport
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.l10n.trQuantity
import run.nuri.getagrip.store.AnalysisExportAssembler
import run.nuri.getagrip.store.DayLedger
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.LocalDayClock
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.SessionRow
import run.nuri.getagrip.ui.components.UndoSnackbar
import run.nuri.getagrip.ui.components.UndoSnackbarEffect
import run.nuri.getagrip.ui.components.benchmarkBore
import run.nuri.getagrip.ui.components.climbNotch
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.components.spokenSession
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.preview.PreviewWorld
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics

/// What you have actually done, and whether it is going anywhere.
///
/// Two questions, in this order: **did I show up** (the five-week grid) and what each day was
/// (the session log). For a twice-a-day habit, turning up is the whole game.
///
/// Every number comes from a `WorkoutLogEntity`, which freezes its plan and each rep's grip at
/// save time, so editing or deleting a routine never rewrites what you DID. Its NAME is the
/// one exception — see `displayName`.
///
/// Between the calendar and the odometer sits the per-routine TREND deck (`TrendDeck`): is
/// the load going anywhere, grip by grip.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onLogSession: () -> Unit,
    modifier: Modifier = Modifier,
    /// The tour's anchor for the month calendar, passed IN so this screen knows nothing of the
    /// tour and stays previewable (as `SetRowView` does with `palette` and `maxes`).
    monthAnchor: Modifier = Modifier,
    feed: HistoryFeed = LocalHistoryFeed.current,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val clock = LocalDayClock.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbar = remember { SnackbarHostState() }

    var showAllSessions by remember { mutableStateOf(false) }
    var exportRequest by remember { mutableStateOf<AnalysisExportRequest?>(null) }
    var shareRequest by remember { mutableStateOf<ShareCalendarRequest?>(null) }

    // The feed has no live query, so the screen asks on the way in; it READS only when a write
    // landed since, so switching tabs is free. Keyed on the revision so a write made while
    // History is up (a cascade delete, a session logged here) still reaches it.
    LaunchedEffect(feed, templates.writeRevision) { feed.refreshIfStale() }

    val logs = feed.logs
    val today = clock.today
    // Folded ONCE and handed down (see `DayLedger`): cells answering their own questions made
    // ~200 whole-log passes per 35-day card. The feed keeps it and refolds off the main thread.
    val trackingSince = templates.trackingSince
    val ledger = feed.ledger(today, trackingSince)
    // The odometer, folded by the feed beside the read — columns only, no blobs.
    val lifetime = feed.lifetime
    val windowCount = HistoryWindows.pageCount(ledger.trackingSince, today)
    val routineNames = templates.routineNames

    fun displayName(log: WorkoutLogEntity): String = sessionDisplayName(log, routineNames)

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // `RootTabView`'s Scaffold already inset this subtree; a second inset would pad twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { UndoSnackbar(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text(tr("History")) },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = palette.card,
                    titleContentColor = palette.inkPrimary,
                ),
                actions = {
                    // The SCREEN-level action on the screen's own bar: the month card's share exports ONE
                    // calendar as a picture, this the whole ledger as a document. Two scopes, two places.
                    //
                    // Offered even with nothing logged: the sheet says so, which beats a toolbar item that
                    // comes and goes.
                    IconButton(onClick = {
                        exportRequest = buildExportRequest(feed, routineNames, today,
                            criticalForce = templates.criticalForceRecords)
                    }) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = tr("Export for analysis"),
                            tint = palette.graphite,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).readablePageWidth(),
            contentPadding = PaddingValues(bottom = Metrics.spacing + LocalFloatingTabBarInset.current),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (logs.isEmpty()) {
                item("empty") {
                    EmptyCard(onLogSession, Modifier.padding(horizontal = Metrics.hPadding, vertical = 12.dp))
                }
            } else {
                item("subtitle") {
                    Text(
                        tr("%d session%s", logs.size, if (logs.size == 1) "" else "s"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkTertiary,
                        modifier = Modifier.padding(horizontal = Metrics.hPadding, vertical = 2.dp),
                    )
                }
                item("months") {
                    // One card per 5-WEEK WINDOW, swiped like every other deck (Nuri, 2026-08-10) — and a deck
                    // only once a second window exists, as on Today.
                    if (windowCount > 1) {
                        MonthDeck(windowCount, ledger, today, monthAnchor) { shareRequest = it }
                    } else {
                        Box(Modifier.padding(horizontal = Metrics.hPadding, vertical = 12.dp)) {
                            MonthCard(0, ledger, today) { shareRequest = it }
                        }
                    }
                }
                item("trends") {
                    // One trend card per routine with measured pulls. It owns the grip selection and
                    // builds its model off the main thread, so a chip tap recomposes that block alone.
                    TrendDeck(logs, routineNames, feed::reps)
                }
                item("lifetime") {
                    // Between the trends and the log (Nuri, 2026-09-20): how often, then where it is
                    // going, then how much, then the sessions it adds up.
                    LifetimeCard(lifetime, Modifier.padding(horizontal = Metrics.hPadding, vertical = 6.dp))
                }
                item("sessions-label") {
                    CapsLabel(
                        tr("Sessions"),
                        Modifier.padding(horizontal = Metrics.hPadding).padding(top = 10.dp, bottom = 2.dp),
                    )
                }

                // TEN, then a door: the log grows forever. The recent ones are what you check; the rest are
                // one tap away.
                val visible = if (showAllSessions) logs else logs.take(RECENT_SESSION_LIMIT)
                items(visible, key = { it.id }) { log ->
                    SwipeableSessionRow(
                        log = log,
                        name = displayName(log),
                        leadingGrip = feed.leadingGrip(log),
                        onExport = { exportRequest = buildExportRequest(feed, routineNames, today, log) },
                        onDelete = {
                            scope.launch {
                                // Leaves the list only once the delete landed; the same guard raises the Undo.
                                if (templates.deleteSession(log)) feed.refresh()
                            }
                        },
                    )
                }

                if (!showAllSessions && logs.size > RECENT_SESSION_LIMIT) {
                    item("show-all") {
                        // Says HOW MANY it holds back: "show more" hiding an unknown quantity reads as a trick.
                        ShowAllRow(logs.size - RECENT_SESSION_LIMIT) { showAllSessions = true }
                    }
                }

                item("footnote") {
                    // Deleting a session moves the grid under it — a lot of consequence for a swipe — so the
                    // foot of the list says so rather than letting the grid quietly change shape.
                    Text(
                        tr("Deleting a session removes it from your streak and your trends too. Your routines are untouched."),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                        modifier = Modifier
                            .padding(horizontal = Metrics.hPadding)
                            .padding(top = 14.dp, bottom = 24.dp),
                    )
                }
            }

            item("log-a-session") {
                LogSessionRow(onLogSession, Modifier.padding(horizontal = Metrics.hPadding, vertical = 4.dp))
            }
        }
    }

    UndoSnackbarEffect(
        hostState = snackbar,
        deleted = templates.lastDeletedSession,
        message = tr("Session deleted"),
        onUndo = { scope.launch { templates.undoDeleteSession(); feed.refresh() } },
        onExpired = { templates.dismissSessionUndo() },
    )

    exportRequest?.let { request ->
        AnalysisExportSheet(request) { exportRequest = null }
    }
    shareRequest?.let { request ->
        ShareCalendarSheet(request) { shareRequest = null }
    }
}

private const val RECENT_SESSION_LIMIT = 10

/// The routine's live name where it still exists, falling back to the name frozen into the
/// log — a pure function of a snapshot, so the export can ask it off the main thread.
private fun sessionDisplayName(log: WorkoutLogEntity, routineNames: Map<UUID, String>): String {
    val id = log.templateID ?: return log.templateName
    return routineNames[id] ?: log.templateName
}

/// **Freeze WHAT to export at the tap; assemble it once the sheet is up.** Assembling every
/// log's blobs on the main thread made the button slower every week of training. Values are
/// captured here; the sheet runs `assemble` on a background dispatcher.
private fun buildExportRequest(
    feed: HistoryFeed,
    routineNames: Map<UUID, String>,
    today: DayStamp,
    workout: WorkoutLogEntity? = null,
    criticalForce: List<CriticalForceRecordEntity> = emptyList(),
): AnalysisExportRequest {
    val logs = workout?.let { listOf(it) } ?: feed.logs
    val maxRecords = feed.maxRecords
    // A single workout's export carries no tests.
    val tests = if (workout != null) emptyList() else criticalForce
    return AnalysisExportRequest(isWorkout = workout != null) {
        AnalysisExportAssembler.input(
            logs = logs,
            maxRecords = maxRecords,
            criticalForceRecords = tests,
            reps = feed::reps,
            displayName = { sessionDisplayName(it, routineNames) },
            today = today,
        )
    }
}

// MARK: - The five-week windows

/// The window arithmetic, as pure functions — the grid's identity and its counting, with
/// no Compose in them, so `HistoryWindowTests` can pin them.
object HistoryWindows {
    /// Five weeks, and the deck's page size: 7 × 5 exactly, so every card has the same shape.
    const val WINDOW_DAYS = 35

    /// How many windows have anything to show: from today back to the first day on record,
    /// in 35-day pages. Never zero — a brand-new app still gets its current five weeks.
    fun pageCount(trackingSince: DayStamp, today: DayStamp): Int {
        val span = today.raw - trackingSince.raw + 1
        return maxOf(1, Math.ceil(span.toDouble() / WINDOW_DAYS).toInt())
    }

    /// Five weeks ending 35×`window` days ago; window 0 is home. Oldest first, filling the grid
    /// left to right, top to bottom.
    fun days(window: Int, today: DayStamp): List<DayStamp> =
        (0 until WINDOW_DAYS).map { today - (WINDOW_DAYS - 1 - it) - window * WINDOW_DAYS }

    /// Home says what it IS; the past says WHEN it was — the window's identity once "last
    /// 5 weeks" stops being true of it.
    fun title(window: Int, days: List<DayStamp>): String {
        val first = days.firstOrNull()
        val last = days.lastOrNull()
        if (window <= 0 || first == null || last == null) return L10n.tr("Last 5 weeks")
        return L10n.tr(
            "%s – %s",
            RANGE_DATE.format(first.localDate()),
            RANGE_DATE.format(last.localDate()),
        )
    }

    /// Counted against the days that were actually TRACKED, so a first week with the app
    /// doesn't read as 6 of 35.
    fun summary(window: Int, days: List<DayStamp>, trackingSince: DayStamp, trained: (DayStamp) -> Boolean): String {
        val tracked = days.filter { it >= trackingSince }
        val count = tracked.count(trained)
        if (tracked.size == days.size) {
            return if (window == 0) L10n.tr("%d of the last 35 days trained", count)
            else L10n.tr("%d of 35 days trained", count)
        }
        return L10n.tr("%d of %d days trained since you started", count, tracked.size)
    }

    private val RANGE_DATE = LocalizedPattern("d MMM")
}

/// LAZY, unlike the other decks: pages grow with history, and a two-year habit must not build
/// seventy hidden grids at once.
///
/// TRANSLATION NOTE: iOS pages with `.viewAligned(limitBehavior: .always)`. Here a snapping
/// `LazyRow`; the trailing content margin makes the next card PEEK, saying there is more.
@Composable
private fun MonthDeck(
    windowCount: Int,
    ledger: DayLedger,
    today: DayStamp,
    anchor: Modifier,
    onShare: (ShareCalendarRequest) -> Unit,
) {
    val state = rememberLazyListState()
    // Anchor the DECK, not a card: a `LazyRow` card's anchor vanishes on swipe (why the anchor
    // registry keeps a list per target).
    BoxWithConstraints(Modifier.fillMaxWidth().then(anchor)) {
        val cardWidth = maxWidth - Metrics.hPadding * 2
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(
                start = Metrics.hPadding,
                end = Metrics.hPadding + 8.dp,
                top = 12.dp,
                bottom = 6.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(windowCount) { window ->
                Box(Modifier.width(cardWidth)) {
                    MonthCard(window, ledger, today, onShare)
                }
            }
        }
    }
}

@Composable
private fun MonthCard(
    window: Int,
    ledger: DayLedger,
    today: DayStamp,
    onShare: (ShareCalendarRequest) -> Unit,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val days = remember(window, today) { HistoryWindows.days(window, today) }
    val title = HistoryWindows.title(window, days)

    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CapsLabel(title, Modifier.weight(1f))
                IconButton(onClick = {
                    onShare(
                        ShareCalendarRequest(
                            days = days,
                            ledger = ledger,
                            title = title,
                            today = today,
                            bestPull = heaviestCurrentMax(templates.currentMaxes.values),
                        ),
                    )
                }) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = tr("Share this five-week calendar"),
                        tint = palette.graphite,
                    )
                }
            }

            DayGrid(days, ledger, today)

            Text(
                HistoryWindows.summary(window, days, ledger.trackingSince) { ledger.fraction(it) > 0 },
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkTertiary,
            )

            // Only once a marked cell exists: a legend for an unseen glyph is clutter.
            val anyClimb = days.any { ledger.climbed(it) }
            val anyBenchmark = days.any { ledger.benchmarked(it) }
            if (anyClimb || anyBenchmark) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    // The legend explains SHAPES; the grid's spoken summary already says it in words.
                    modifier = Modifier.clearAndSetSemantics {},
                ) {
                    if (anyClimb) LegendSwatch(palette.graphite, tr("Climbing gym"), notch = true)
                    if (anyBenchmark) LegendSwatch(palette.bleu, tr("Testing"), bore = 5.dp)
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(
    fill: Color,
    label: String,
    notch: Boolean = false,
    bore: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val palette = LocalGripPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .climbNotch(notch)
                .benchmarkBore(bore > 0.dp, bore)
                .background(fill, RoundedCornerShape(3.dp)),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
    }
}

/// Seven columns of 28 dp cells, as `Row`s: 35 = 7 × 5 is constant, and a `LazyVerticalGrid`
/// cannot nest in a `LazyColumn` without a fixed height anyway.
@Composable
private fun DayGrid(days: List<DayStamp>, ledger: DayLedger, today: DayStamp) {
    Column(
        Modifier
            .fillMaxWidth()
            // ONE spoken summary: 35 labelled cells would be a minute of TalkBack teaching nothing.
            .semantics(mergeDescendants = true) {
                contentDescription = spokenMonthSummary(days, ledger)
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    DayCell(
                        fraction = ledger.fraction(day),
                        isToday = day == today,
                        isTracked = day >= ledger.trackingSince,
                        climbed = ledger.climbed(day),
                        benchmarked = ledger.benchmarked(day),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/// Shape-encoded, not colour-only: the fill RISES with how much of the day you did, and today
/// has a ring — surviving Reduce Transparency and colourblindness, like Today's strip.
@Composable
private fun DayCell(
    fraction: Double,
    isToday: Boolean,
    isTracked: Boolean,
    climbed: Boolean,
    benchmarked: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGripPalette.current
    // A climb on the same day WINS the cell: the notch and graphite stay, matching the
    // day-story precedence everywhere else.
    val showsBenchmark = benchmarked && !climbed
    val shape = RoundedCornerShape(CELL_RADIUS)
    // Read HERE: a `drawBehind` lambda cannot read a CompositionLocal, and capturing the fills
    // keeps the bar a pure drawing.
    val fill = if (showsBenchmark) palette.bleu else palette.graphite

    Box(modifier.height(CELL_SIZE), contentAlignment = Alignment.Center) {
        if (isTracked) {
            Box(Modifier.fillMaxSize().background(palette.inkTertiary.copy(alpha = 0.14f), shape))
        } else {
            // A HAIRLINE, so a day before the app cannot be mistaken for a skipped one.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(palette.inkTertiary.copy(alpha = 0.22f), RoundedCornerShape(1.dp)),
            )
        }
        if (fraction > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .climbNotch(climbed)
                    .benchmarkBore(showsBenchmark, 8.dp)
                    .drawBehind {
                        val height = (size.height * fraction.coerceIn(0.0, 1.0)).toFloat()
                        val radius = CELL_RADIUS.toPx()
                        drawRoundRect(
                            color = fill,
                            topLeft = Offset(0f, size.height - height),
                            size = Size(size.width, height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        )
                    },
            )
        }
        if (isToday) {
            // INK on a benchmark day: a bleu ring on the bleu fill is invisible, and today
            // must stay findable on the day you tested.
            Box(
                Modifier
                    .fillMaxSize()
                    .border(2.dp, if (showsBenchmark) palette.inkPrimary else palette.bleu, shape),
            )
        }
    }
}

private val CELL_SIZE = 28.dp
private val CELL_RADIUS = 5.dp

/// Folds the climb/benchmark counts in: the legend explaining those glyphs is hidden from
/// TalkBack, so this is the distinction's only accessible channel.
private fun spokenMonthSummary(days: List<DayStamp>, ledger: DayLedger): String {
    val tracked = days.filter { it >= ledger.trackingSince }
    val trained = tracked.count { ledger.fraction(it) > 0 }
    val full = tracked.count { ledger.fraction(it) >= 1 }
    val climbed = tracked.count { ledger.climbed(it) }
    val benchmarked = tracked.count { ledger.benchmarked(it) }
    var summary = L10n.tr(
        "Trained on %d of %d tracked days, %d of them fully.",
        trained,
        tracked.size,
        full,
    )
    if (climbed > 0) {
        summary += L10n.tr(" %d %s at the climbing gym.", climbed, L10n.tr(if (climbed == 1) "day" else "days"))
    }
    if (benchmarked > 0) {
        summary += L10n.tr(" %d %s testing maxes.", benchmarked, L10n.tr(if (benchmarked == 1) "day" else "days"))
    }
    return summary
}

/// The share card names the heaviest CURRENT record, never an older peak. Ties are stable, so
/// the shared grip does not depend on map order.
private fun heaviestCurrentMax(records: Collection<MaxRecordEntity>): ShareCalendarBestPull? =
    records
        .map { ShareCalendarBestPull(it.kg, it.grip, it.side, it.recordedAt) }
        .sortedWith(
            compareByDescending<ShareCalendarBestPull> { it.kg }
                .thenByDescending { it.recordedAt }
                .thenBy { it.sortKey },
        )
        .firstOrNull()

// MARK: - Rows

/** Share and Delete live on opposite sides; both require tapping the revealed button. */
@Composable
private fun SwipeableSessionRow(
    log: WorkoutLogEntity,
    name: String,
    leadingGrip: GripSpec?,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val palette = LocalGripPalette.current
    run.nuri.getagrip.ui.components.SwipeActionRow(
        onDelete = onDelete,
        onShare = onExport,
        deleteLabel = tr("%s. Delete", spokenSession(log, name)),
    ) {
        Column {
            SessionRow(log = log, name = name, leadingGrip = leadingGrip,
                modifier = Modifier.padding(horizontal = Metrics.hPadding).padding(vertical = 2.dp))
            HorizontalDivider(Modifier.padding(start = SEPARATOR_INSET),
                color = palette.inkTertiary.copy(alpha = 0.25f))
        }
    }
}

/// 60 dp: `Metrics.hPadding` (20) + the 44 dp artwork tile, less a hair, exactly as iOS
/// aligns its `listRowSeparatorLeading` guide.
private val SEPARATOR_INSET = 60.dp

@Composable
private fun ShowAllRow(hidden: Int, onShow: () -> Unit) {
    val palette = LocalGripPalette.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onShow,
            )
            .pressFeedback(interaction, scales = false)
            .padding(horizontal = Metrics.hPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            trQuantity("Show %d earlier sessions", hidden),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.graphite,
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = palette.graphite,
            modifier = Modifier.size(18.dp),
        )
    }
}

/// The door to `SessionLogSheet` — a climb, or hangs done away from the gauge. This screen
/// never builds that sheet; it owes only a named, always-present entry point.
@Composable
private fun LogSessionRow(onLogSession: () -> Unit, modifier: Modifier = Modifier) {
    SecondaryButton(
        title = tr("Log a session"),
        icon = Icons.Outlined.Add,
        modifier = modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
        onClick = onLogSession,
    )
}

@Composable
private fun EmptyCard(onLogSession: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CapsLabel(tr("Nothing here yet"))
            Text(
                tr("Finish a session and it lands here — what you did, how hard it felt, and how your load is moving per grip."),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary,
            )
            SecondaryButton(
                title = tr("Log a session"),
                icon = Icons.Outlined.Add,
                onClick = onLogSession,
            )
        }
    }
}

// MARK: - Previews

@Preview(name = "History", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun HistoryPreview() {
    PreviewWorld { feed -> HistoryScreen(onLogSession = {}, feed = feed) }
}
