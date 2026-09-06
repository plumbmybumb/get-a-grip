// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

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
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import run.nuri.getagrip.ui.theme.Metrics

/// What you have actually done, and whether it is going anywhere.
///
/// Two different questions, and the screen answers them in that order: **did I show up**
/// (the five-week grid, which is about the ritual) and what each of those days was (the
/// session log). Consistency comes first deliberately — for a twice-a-day habit, turning up
/// is the whole game.
///
/// Every number here comes from a `WorkoutLogEntity`, which freezes its plan and each rep's
/// grip at save time. Editing or deleting a routine can therefore never rewrite what
/// history says you DID. Its NAME is the one deliberate exception — see `displayName`.
///
/// TRANSLATION NOTE: iOS also carries a per-routine TREND deck here (average load per grip,
/// per routine, as a chart with a chip row). It is not in this file: its grip picker is
/// built out of the house `Chip`, which belongs to the builder wave, and half a trend card
/// with no way to change grips would be worse than none. `MaxChart` is already the drawing
/// it needs — see the report.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onLogSession: () -> Unit,
    modifier: Modifier = Modifier,
    /// The spotlight tour's anchor for the month calendar. Passed IN rather than read from a
    /// composition local, so this screen still has no idea a tour exists and stays previewable
    /// — the same discipline `SetRowView` follows with `palette` and `maxes`.
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

    // The feed has no live query behind it, so a screen that draws the ledger asks for it
    // once on the way in and again after every write it makes.
    LaunchedEffect(feed) { feed.refresh() }

    val logs = feed.logs
    val today = clock.today
    // Folded ONCE and handed down, rather than each of the ~200 questions a card asks
    // re-scanning the whole log list. See `DayLedger`: every cell used to answer its own
    // questions by filtering every log, which is on the order of two hundred whole-array
    // passes per 35-day card.
    val trackingSince = templates.trackingSince
    val ledger = remember(logs, today, trackingSince) { DayLedger(logs, today, trackingSince) }
    val windowCount = HistoryWindows.pageCount(ledger.trackingSince, today)

    fun displayName(log: WorkoutLogEntity): String {
        val id = log.templateID ?: return log.templateName
        return templates.routineNames[id] ?: log.templateName
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // `RootTabView`'s Scaffold has already inset this subtree; a nested Scaffold that
        // added its own would pad both twice.
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
                    // The SCREEN-level action belongs on the screen's own bar: the month
                    // card's share button exports ONE five-week calendar as a picture, and
                    // this exports the whole ledger as a document. Two different scopes, so
                    // two different places — a second button on the card would read as a
                    // variant of the first.
                    //
                    // Offered even with nothing logged: the sheet then says plainly that
                    // there is nothing to export, which is a better answer than a toolbar
                    // item that is present on some launches and missing on others.
                    IconButton(onClick = {
                        exportRequest = buildExportRequest(feed, ::displayName, today)
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
            Modifier.fillMaxSize().padding(padding),
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
                    // One card per 5-WEEK WINDOW, swiped like every other deck (Nuri,
                    // 2026-08-10: "swipable cards of previous windows") — and only a deck
                    // once a second window exists to swipe to, the same honesty rule as
                    // Today's.
                    if (windowCount > 1) {
                        MonthDeck(windowCount, ledger, today, monthAnchor) { shareRequest = it }
                    } else {
                        Box(Modifier.padding(horizontal = Metrics.hPadding, vertical = 12.dp)) {
                            MonthCard(0, ledger, today) { shareRequest = it }
                        }
                    }
                }
                item("sessions-label") {
                    CapsLabel(
                        tr("Sessions"),
                        Modifier.padding(horizontal = Metrics.hPadding).padding(top = 10.dp, bottom = 2.dp),
                    )
                }

                // TEN, then a door: the log grows forever, and a habit app's history would
                // soon be a hundred rows of scroll under one card. The recent ones are the
                // ones you check; the rest are one tap away, not gone.
                val visible = if (showAllSessions) logs else logs.take(RECENT_SESSION_LIMIT)
                items(visible, key = { it.id }) { log ->
                    SwipeableSessionRow(
                        log = log,
                        name = displayName(log),
                        leadingGrip = feed.reps(log).firstOrNull()?.grip,
                        onExport = { exportRequest = buildExportRequest(feed, ::displayName, today, log) },
                        onDelete = {
                            scope.launch {
                                // The row only leaves the list once the store says the
                                // delete landed; `lastDeletedSession` is what raises the
                                // Undo, and it is set by the same guard.
                                if (templates.deleteSession(log)) feed.refresh()
                            }
                        },
                    )
                }

                if (!showAllSessions && logs.size > RECENT_SESSION_LIMIT) {
                    item("show-all") {
                        // Says HOW MANY it is holding back — "show more" hiding an unknown
                        // quantity reads as a trick.
                        ShowAllRow(logs.size - RECENT_SESSION_LIMIT) { showAllSessions = true }
                    }
                }

                item("footnote") {
                    // What a delete here does and does not touch, said once at the foot of
                    // the list. Deleting a session moves the grid under it, and that is a
                    // surprising amount of consequence for a swipe — so the screen states
                    // it rather than letting the grid quietly change shape.
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

/// Freeze the selected model values using the feed's rep cache. The sheet formats
/// these values on a background dispatcher when the user chooses a range.
private fun buildExportRequest(
    feed: HistoryFeed,
    displayName: (WorkoutLogEntity) -> String,
    today: DayStamp,
    workout: WorkoutLogEntity? = null,
): AnalysisExportRequest {
    val input = AnalysisExportAssembler.input(
        logs = workout?.let { listOf(it) } ?: feed.logs,
        maxRecords = feed.maxRecords,
        reps = { feed.reps(it) },
        displayName = displayName,
        today = today,
    )
    return AnalysisExportRequest(input = input, isWorkout = workout != null)
}

// MARK: - The five-week windows

/// The window arithmetic, as pure functions — the grid's identity and its counting, with
/// no Compose in them, so `HistoryWindowTests` can pin them.
object HistoryWindows {
    /// Five weeks, and the deck's page size. 35 = 7 columns × 5 rows exactly, which is what
    /// makes every card the same shape whatever month it lands on.
    const val WINDOW_DAYS = 35

    /// How many windows have anything to show: from today back to the first day on record,
    /// in 35-day pages. Never zero — a brand-new app still gets its current five weeks.
    fun pageCount(trackingSince: DayStamp, today: DayStamp): Int {
        val span = today.raw - trackingSince.raw + 1
        return maxOf(1, Math.ceil(span.toDouble() / WINDOW_DAYS).toInt())
    }

    /// Five weeks ending 35×`window` days ago. Window 0 is home — the last five weeks —
    /// and the list runs oldest first so it fills the grid left to right, top to bottom.
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

    private val RANGE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
}

/// LAZY, unlike the other decks: the page count grows with the training history and a
/// two-year habit must not build seventy hidden grids at once.
///
/// TRANSLATION NOTE: iOS uses a paging `ScrollView` with `.viewAligned(limitBehavior:
/// .always)` — one card per swipe, each page a destination rather than a distance. The
/// Compose twin is a `LazyRow` snapping on its own list state; the trailing content margin
/// is what makes the next card PEEK at the screen edge, which is how the deck says there is
/// more without a permanent sliver of chrome.
@Composable
private fun MonthDeck(
    windowCount: Int,
    ledger: DayLedger,
    today: DayStamp,
    anchor: Modifier,
    onShare: (ShareCalendarRequest) -> Unit,
) {
    val state = rememberLazyListState()
    // The DECK, not one card: a `LazyRow` builds only what is on screen, so an anchor on a
    // card would vanish the moment you swiped — which is the general case the anchor registry
    // keeps a list per target for.
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

            // Shown only once there is a marked cell to explain. A legend for a glyph
            // nobody has produced yet is clutter teaching nothing.
            val anyClimb = days.any { ledger.climbed(it) }
            val anyBenchmark = days.any { ledger.benchmarked(it) }
            if (anyClimb || anyBenchmark) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    // The legend explains the SHAPES; the grid's own spoken summary already
                    // carries the same distinction in words.
                    modifier = Modifier.clearAndSetSemantics {},
                ) {
                    if (anyClimb) LegendSwatch(palette.graphite, tr("Climbing gym"), notch = true)
                    if (anyBenchmark) LegendSwatch(palette.bleu, tr("Max testing"), bore = 5.dp)
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

/// Seven columns of 28 dp cells. A plain `Column` of `Row`s rather than a grid: the day
/// count is a compile-time constant (35 = 7 × 5) and a `LazyVerticalGrid` cannot be nested
/// inside a `LazyColumn` without a fixed height anyway.
@Composable
private fun DayGrid(days: List<DayStamp>, ledger: DayLedger, today: DayStamp) {
    Column(
        Modifier
            .fillMaxWidth()
            // The grid carries ONE spoken summary; 35 individually-labelled cells would be
            // a minute of TalkBack to learn nothing.
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

/// Shape-encoded, not colour-only: the fill RISES with how much of the day you did, and
/// today carries a ring. It has to survive Reduce Transparency and colourblindness, and it
/// speaks the same language as the strip on Today.
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
    // Read HERE, not inside `drawBehind`: a draw lambda has no composition to read a
    // CompositionLocal from, and capturing the two fills beside the cell is what keeps the
    // rising bar a pure drawing.
    val fill = if (showsBenchmark) palette.bleu else palette.graphite

    Box(modifier.height(CELL_SIZE), contentAlignment = Alignment.Center) {
        if (isTracked) {
            Box(Modifier.fillMaxSize().background(palette.inkTertiary.copy(alpha = 0.14f), shape))
        } else {
            // A HAIRLINE rather than an empty box: a day you never had the app cannot be
            // mistaken for one you skipped.
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

/// Folds the climb/benchmark counts in, so the "a climb completes the day" distinction the
/// grid's shape-coding carries has a spoken equivalent — the legend that explains those
/// glyphs is itself hidden from TalkBack, so without this the whole distinction would have
/// no accessible channel at all.
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

/// The share card always names the heaviest CURRENT record, never an older peak from the
/// append-only max history. Ties are stable so two equal pulls do not make the shared grip
/// depend on map iteration order.
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

/// The door to `SessionLogSheet` — a climb at the gym, or hangs done away from the gauge.
/// **This screen never builds that sheet**: it belongs to the wave that owns the input
/// controls, so all this owes is a named, always-present entry point.
@Composable
private fun LogSessionRow(onLogSession: () -> Unit, modifier: Modifier = Modifier) {
    SecondaryButton(
        title = tr("Log a session"),
        icon = Icons.Outlined.Add,
        modifier = modifier.fillMaxWidth().widthIn(max = Metrics.maxContentWidth),
        onClick = onLogSession,
    )
}

@Composable
private fun EmptyCard(onLogSession: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier.fillMaxWidth().widthIn(max = Metrics.maxContentWidth),
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
