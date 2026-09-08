// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import run.nuri.getagrip.ui.units.WeightUnits

import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.engine.Fmt
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.MaxChart
import run.nuri.getagrip.ui.components.MaxPoint
import run.nuri.getagrip.ui.components.MaxSeries
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.preview.PreviewWorld
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics

/// How strong you are at your limit, per grip, over time — the fourth tab.
///
/// This is a different question from History's: History charts the WORKING load your
/// sessions actually averaged; this charts the CEILING the gauge has seen you pull.
/// `MaxRecordEntity` is append-only precisely so this screen costs nothing — every max ever
/// recorded is still there, and a card here is just one grip's rows drawn as a curve.
///
/// Manage opens the saved-record list within this tab. Charts, testing and record
/// management share the same data and the composer's existing save flow.
///
/// **The WORKING max is the NEWEST record, not the highest**: a benchmark that tests lower
/// honestly lowers your percentage targets too. Best-ever is shown beside it as the PR.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxesTabScreen(
    onAddMax: (GripSpec?) -> Unit,
    onMeasure: (GripSpec, Side) -> Unit,
    modifier: Modifier = Modifier,
    cardsAnchor: Modifier = Modifier,
    manageAnchor: Modifier = Modifier,
    feed: HistoryFeed = LocalHistoryFeed.current,
) {
    val nav = rememberNavController()
    val palette = LocalGripPalette.current
    NavHost(nav, startDestination = "overview", modifier = modifier.fillMaxSize()) {
        composable("overview") {
            MaxesOverview(onAddMax, onMeasure, cardsAnchor = cardsAnchor,
                manageAnchor = manageAnchor, onManage = { nav.navigate("manage") }, feed = feed)
        }
        composable("manage") {
            Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(title = { Text(tr("Manage maxes")) },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        navigationIcon = {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            titleContentColor = palette.inkPrimary))
                }) { padding ->
                MaxesListScreen(onAddMax, Modifier.padding(padding), feed = feed)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaxesOverview(
    /// Opens the composer — the sheet where a max is typed, a hand is chosen and the
    /// measure door is offered. **Not built in this wave**: `MaxEntrySheet` is made of the
    /// same input controls as the routine builder, so it belongs to that one. Null means
    /// "no grip in mind", which is the empty state's Add.
    onAddMax: (GripSpec?) -> Unit,
    /// Goes straight to the gauge for one grip and one hand, skipping the composer. The
    /// side is the one the card's current record was pulled with, so "Measure again" tests
    /// the same thing it is showing you.
    onMeasure: (GripSpec, Side) -> Unit,
    modifier: Modifier = Modifier,
    /// The spotlight tour's anchor for the grip cards. Passed IN, so this screen never reads
    /// a tour and stays previewable.
    cardsAnchor: Modifier = Modifier,
    manageAnchor: Modifier = Modifier,
    onManage: () -> Unit,
    feed: HistoryFeed = LocalHistoryFeed.current,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(feed) { feed.refresh() }

    val records = feed.maxRecords
    val groups = remember(records) { groupsOf(records) }
    val tested = remember(records) { records.map { it.gripKey }.toSet() }
    // Grips your routines train that have never seen a number — an invitation, not a
    // reproach, and only once routines exist at all.
    val invitations = if (templates.routines.isEmpty()) emptyList()
    else templates.recentGrips.filter { it.key !in tested }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text(tr("Maxes")) },
                actions = {
                    TextButton(onClick = onManage, modifier = manageAnchor.semantics {
                        contentDescription = L10n.tr("Manage maxes")
                    }) { Text(tr("Manage"), color = palette.inkPrimary) }
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = palette.card,
                    titleContentColor = palette.inkPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).readablePageWidth(),
            contentPadding = PaddingValues(
            start = Metrics.hPadding, end = Metrics.hPadding, top = 12.dp,
            bottom = 12.dp + LocalFloatingTabBarInset.current,
        ),
            verticalArrangement = Arrangement.spacedBy(Metrics.spacing),
        ) {
            item("subtitle") {
                // The staleness line the soft nudge (`TemplateStore.benchmarkNudge`) is the
                // icon-sized version of.
                Text(
                    templates.lastMeasuredMaxAt?.let { L10n.tr("Tested %s", relative(it)) }
                        ?: tr("Your ceiling, per grip"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkTertiary,
                )
            }

            if (groups.isEmpty() && invitations.isEmpty()) {
                item("empty") { EmptyCard { onAddMax(templates.recentGrips.firstOrNull()) } }
            } else {
                items(groups, key = { it.key }) { group ->
                    // The FIRST card carries the anchor. Lighting the whole `LazyColumn` would
                    // be lighting the screen, which is not a spotlight; the first card is what
                    // "a grip's ceiling, drawn over time" actually looks like.
                    GripCard(group, onMeasure, if (group.key == groups.first().key) cardsAnchor else Modifier)
                }
                items(invitations, key = { "invite-${it.key}" }) { grip ->
                    InvitationCard(grip) { onMeasure(grip, Side.both) }
                }
                item("footnote") {
                    // The same footnote contract as History's: what this screen's numbers
                    // are and are not. Percent targets follow the NEWEST number, including
                    // downward — worth one honest line on the screen where a bad testing day
                    // becomes visible.
                    Text(
                        tr("Your working max is the newest test, best is your record. Percentage targets follow the newest number — up or down."),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                    )
                }
            }

            item("add") {
                SecondaryButton(
                    title = tr("Add a max"),
                    modifier = Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
                ) { onAddMax(null) }
            }
        }
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
/// must keep the hands apart.
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

/// Which hands this grip has records for, in a fixed order so the readout never reshuffles.
internal fun presentSides(group: MaxGripGroup): List<Side> =
    listOf(Side.both, Side.left, Side.right).filter { side -> group.records.any { it.side == side } }

private fun newest(group: MaxGripGroup, side: Side): MaxRecordEntity? =
    group.records.lastOrNull { it.side == side }

/// "Best 31.5 kg · up 2.4 kg since 12 Jul" — the PR beside how the newest test moved against
/// the one before it, ON THE SAME HAND. Comparing across hands would report a difference
/// between your arms as progress.
internal fun progressLine(group: MaxGripGroup): String {
    val sides = presentSides(group)
    val bests = sides.joinToString(" · ") { side ->
        val weight = WeightUnits.number(group.records.filter { it.side == side }.maxOfOrNull { it.kg } ?: 0.0, 1)
        if (sides.size == 1) weight else "${side.displayName} $weight"
    }
    val parts = mutableListOf(WeightUnits.tr("Best %s kg", bests))
    val newest = group.records.lastOrNull()
    if (newest != null) {
        val series = group.records.filter { it.side == newest.side }
        if (series.size >= 2) {
            val previous = series[series.size - 2]
            val delta = newest.kg - previous.kg
            val when_ = SHORT_DATE.format(previous.recordedAt.atZone(ZoneId.systemDefault()))
            parts += if (Math.abs(delta) >= 0.05) {
                val verb = if (delta > 0) L10n.tr("up") else L10n.tr("down")
                WeightUnits.tr("%s %s kg since %s", verb, WeightUnits.number(Math.abs(delta), 1), when_)
            } else {
                L10n.tr("held since %s", when_)
            }
        }
    }
    return parts.joinToString(" · ")
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/// Plain relative wording, the twin of iOS's `.relative(presentation: .named)`. Deliberately
/// coarse: the useful fact about a max is that it is weeks old, never that it is 19 days old.
internal fun relative(instant: Instant, now: Instant = Instant.now()): String {
    val days = Duration.between(instant, now).toDays()
    return when {
        days <= 0L -> L10n.tr("today")
        days == 1L -> L10n.tr("yesterday")
        days < 7L -> L10n.tr("%d days ago", days)
        days < 14L -> L10n.tr("last week")
        days < 60L -> L10n.tr("%d weeks ago", days / 7)
        days < 365L -> L10n.tr("%d months ago", days / 30)
        else -> L10n.tr("over a year ago")
    }
}

// MARK: - Cards

@Composable
private fun GripCard(
    group: MaxGripGroup,
    onMeasure: (GripSpec, Side) -> Unit,
    anchor: Modifier = Modifier,
) {
    val palette = LocalGripPalette.current
    val sides = remember(group) { presentSides(group) }
    val line = remember(group, WeightUnits.current) { progressLine(group) }

    // Read outside the semantics lambda, which is not composable.
    val spoken = tr("%s: %s", group.grip.spoken, line)

    Card(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = spoken
        },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphTile(group.grip)
            Text(
                group.grip.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CurrentReadout(group, sides)
        }

        // A chart needs two points to have a direction; one record is a fact, not a trend.
        if (group.records.size >= 2) {
            MaxChart(
                sides.map { side ->
                    MaxSeries(
                        side = side,
                        points = group.records.filter { it.side == side }
                            .map { MaxPoint(it.recordedAt.toEpochMilli().toDouble(), it.kg) },
                    )
                },
                Modifier.fillMaxWidth(),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(title = tr("Measure again")) {
                // The hand the card's newest record was pulled with — "again" means the same
                // test, not a different one.
                onMeasure(group.grip, group.records.last().side)
            }
        }
    }
}

@Composable
private fun InvitationCard(grip: GripSpec, onMeasure: () -> Unit) {
    val palette = LocalGripPalette.current
    Card {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphTile(grip)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    grip.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
                // In a routine, never tested: its percentage targets are waiting on this
                // number.
                Text(
                    tr("In your routine — never tested"),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                )
            }
            SecondaryButton(title = tr("Measure"), onClick = onMeasure)
        }
    }
}

@Composable
private fun EmptyCard(onAdd: () -> Unit) {
    val palette = LocalGripPalette.current
    Card {
        CapsLabel(tr("No maxes yet"))
        Text(
            tr("Measure the most a grip can hold and it lands here — every later test draws the curve of you getting stronger."),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkSecondary,
        )
        SecondaryButton(title = tr("Measure a max"), modifier = Modifier.fillMaxWidth(), onClick = onAdd)
    }
}

/// The current WORKING numbers, trailing the title. One value for a both-hands grip; a
/// compact L/R pair once the hands have their own records — because 25 % of the left max and
/// 25 % of the right max are simply different kilograms, and this is where you see that.
@Composable
private fun CurrentReadout(group: MaxGripGroup, sides: List<Side>) {
    val palette = LocalGripPalette.current
    if (sides == listOf(Side.both)) {
        newest(group, Side.both)?.let { KgText(it.kg, prominent = true) }
        return
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        sides.forEach { side ->
            newest(group, side)?.let { record ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        if (side == Side.both) tr("Both") else if (side == Side.left) tr("L") else tr("R"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkTertiary,
                    )
                    KgText(record.kg, prominent = false)
                }
            }
        }
    }
}

@Composable
internal fun KgText(kg: Double, prominent: Boolean) {
    val palette = LocalGripPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            WeightUnits.number(kg, 1),
            // A MEASUREMENT snaps rather than rolling; no `animateContentSize`, no numeric
            // transition. There is nothing counting here.
            style = (if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge)
                .copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (prominent) palette.inkPrimary else palette.inkSecondary,
            maxLines = 1,
        )
        Text(WeightUnits.symbol, style = MaterialTheme.typography.labelSmall, color = palette.inkTertiary)
    }
}

@Composable
internal fun GlyphTile(grip: GripSpec, side: androidx.compose.ui.unit.Dp = 44.dp) {
    val palette = LocalGripPalette.current
    Box(
        Modifier
            .size(side)
            .background(palette.inkTertiary.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        FingerGlyph(grip.fingers, position = grip.position, dot = 5.dp, gap = 2.5.dp)
    }
}

@Composable
private fun Card(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = LocalGripPalette.current.card,
        modifier = modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Preview(name = "MaxesTab", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun MaxesTabPreview() {
    PreviewWorld { feed ->
        MaxesTabScreen(onAddMax = {}, onMeasure = { _, _ -> }, feed = feed)
    }
}
