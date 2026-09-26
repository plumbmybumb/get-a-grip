// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import run.nuri.getagrip.ui.l10n.LocalizedPattern
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.MaxGripGroup
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.MaxChart
import run.nuri.getagrip.ui.components.MaxPoint
import run.nuri.getagrip.ui.components.MaxSeries
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.data.latestHands
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.ui.criticalforce.CriticalForceHistorySheet
import run.nuri.getagrip.ui.criticalforce.handName
import run.nuri.getagrip.ui.criticalforce.roundedPercent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
/// Each grip opens measurement directly, or its exact hand values and earlier records.
/// The toolbar + chooses another grip. All routes preserve the same max history.
///
/// **The WORKING max is the NEWEST record, not the highest**: a benchmark that tests lower
/// honestly lowers your percentage targets too. Best-ever is shown beside it as the PR.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxesTabScreen(
    onAddMax: (GripSpec?) -> Unit,
    onMeasure: (GripSpec, Side) -> Unit,
    onEdit: (GripSpec) -> Unit,
    modifier: Modifier = Modifier,
    feed: HistoryFeed = LocalHistoryFeed.current,
    /// The critical force test, for a grip and how its hands are tested. The host decides
    /// where it shows.
    onCriticalForce: (GripSpec, CriticalForceHands) -> Unit = { _, _ -> },
) {
    MaxesOverview(onAddMax, onMeasure, onEdit, modifier = modifier,
        feed = feed, onCriticalForce = onCriticalForce)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaxesOverview(
    onAddMax: (GripSpec?) -> Unit,
    onMeasure: (GripSpec, Side) -> Unit,
    onEdit: (GripSpec) -> Unit,
    modifier: Modifier = Modifier,
    feed: HistoryFeed = LocalHistoryFeed.current,
    onCriticalForce: (GripSpec, CriticalForceHands) -> Unit = { _, _ -> },
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Reread only when a write landed since the last read — a tab switch is free. Keyed on
    // the revision too, so a write made while this tab is up still reaches it.
    LaunchedEffect(feed, templates.writeRevision) { feed.refreshIfStale() }

    // Maxes grouped by the feed, off the main thread, beside the read — not here on every
    // visit. Critical force lives on the SAME card as the max it is a share of: the ratio is
    // the point of it, and a section of its own at the bottom left it far from that max.
    val tests = templates.criticalForceRecords
    val groups = remember(feed.maxGroups, tests) { benchmarkGroupsOf(feed.maxGroups, tests) }
    val tested = remember(groups) { groups.mapTo(HashSet()) { it.key } }
    /// The grip whose critical force history is open.
    var historyGrip by remember { mutableStateOf<GripSpec?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    /// The grip whose Measure asked "What are you measuring?".
    var choosing by remember { mutableStateOf<GripSpec?>(null) }
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
                title = { Text(tr("Benchmarks")) },
                actions = {
                    // The one door to both measurements. Critical force lives here, not on
                    // Today: a test every six to eight weeks is a measurement, not the ritual
                    // (Nuri, 2026-09-25).
                    Box {
                        IconButton(onClick = { addMenu = true },
                            modifier = Modifier.testTag("maxes.add")) {
                            Icon(Icons.Default.Add, contentDescription = tr("Add a benchmark"), tint = palette.inkPrimary)
                        }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Measure a max")) },
                                onClick = { addMenu = false; onAddMax(null) },
                                modifier = Modifier.testTag("maxes.add.max"),
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Test critical force")) },
                                onClick = {
                                    addMenu = false
                                    val (grip, hands) = newCriticalForceTest(tests, templates.recentGrips)
                                    onCriticalForce(grip, hands)
                                },
                                modifier = Modifier.testTag("maxes.add.criticalForce"),
                            )
                        }
                    }
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
            // The staleness line the soft nudge (`TemplateStore.benchmarkNudge`) is the icon-sized
            // version of. Nothing before the first test.
            templates.lastMeasuredMaxAt?.let { last ->
                item("subtitle") {
                    Text(
                        L10n.tr("Tested %s", relative(last)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkTertiary,
                    )
                }
            }

            if (groups.isEmpty() && invitations.isEmpty()) {
                item("empty") { EmptyCard() }
            } else {
                items(groups, key = { it.key }) { group ->
                    GripCard(group, onMeasure = { choosing = group.grip }, onEdit,
                        onHistory = { historyGrip = group.grip })
                }
                items(invitations, key = { "invite-${it.key}" }) { grip ->
                    InvitationCard(grip) { choosing = grip }
                }
            }


        }
    }

    // Outside the list, so the sheet outlives the card it came from: deleting a grip's last
    // test removes the card while its Undo is still on offer.
    historyGrip?.let { grip ->
        CriticalForceHistorySheet(gripKey = grip.key, title = grip.displayName, onClose = { historyGrip = null })
    }
    choosing?.let { grip ->
        // Both max choices open the max VISIT; see `MaxMeasureModeDialog`.
        MaxMeasureModeDialog(
            onMax = { side -> choosing = null; onMeasure(grip, side) },
            onCriticalForce = {
                choosing = null
                onCriticalForce(grip, criticalForceHandsFor(grip, tests))
            },
            onDismiss = { choosing = null },
        )
    }
}

/// On a grip already tested, the hands of its last visit; otherwise one at a time.
internal fun criticalForceHandsFor(grip: GripSpec, tests: List<CriticalForceRecordEntity>): CriticalForceHands =
    tests.filter { it.gripKey == grip.key }.latestHands ?: CriticalForceHands.OneAtATime(Side.left)

/// A new test from the Benchmarks "+": the grip and hands of the last test, else the
/// routines' first grip, one hand at a time.
fun newCriticalForceTest(
    tests: List<CriticalForceRecordEntity>,
    recentGrips: List<GripSpec>,
): Pair<GripSpec, CriticalForceHands> {
    val grip = tests.lastOrNull()?.grip ?: recentGrips.firstOrNull() ?: GripSpec()
    return grip to (tests.latestHands ?: CriticalForceHands.OneAtATime(Side.left))
}

// MARK: - Benchmark groups

/// One grip's card: its maxes (possibly none) and its critical force tests (possibly none),
/// each oldest first with every hand mixed.
internal class BenchmarkGroup(val maxes: MaxGripGroup, val tests: List<CriticalForceRecordEntity>) {
    val key: String get() = maxes.key
    val grip: GripSpec get() = maxes.grip
    val lastActivity: Instant =
        listOfNotNull(maxes.records.lastOrNull()?.recordedAt, tests.lastOrNull()?.recordedAt).maxOrNull() ?: Instant.MIN
}

/// Every grip with a max OR a critical force test — a grip with only a test still gets its
/// card. Most recently active first; a date tie (same morning) breaks on the key, so grips
/// don't swap between launches.
internal fun benchmarkGroupsOf(maxGroups: List<MaxGripGroup>, tests: List<CriticalForceRecordEntity>): List<BenchmarkGroup> {
    val maxesByKey = maxGroups.associateBy { it.key }
    val testsByKey = tests.sortedBy { it.recordedAt }.groupBy { it.gripKey }
    return (maxesByKey.keys + testsByKey.keys).map { key ->
        val cf = testsByKey[key].orEmpty()
        val maxes = maxesByKey[key] ?: MaxGripGroup(key, cf.last().grip, emptyList())
        BenchmarkGroup(maxes, cf)
    }.sortedWith(compareByDescending<BenchmarkGroup> { it.lastActivity }.thenBy { it.key })
}

/// The hands with a test, in the house order: both, left, right.
private fun criticalForceSides(group: BenchmarkGroup): List<Side> =
    listOf(Side.both, Side.left, Side.right).filter { side -> group.tests.any { it.side == side } }

/// "Critical force up 1.2 kg since 12 Jul" — the newest test against the one before it, on
/// the same hand.
internal fun criticalForceProgressLine(tests: List<CriticalForceRecordEntity>): String? {
    val newest = tests.lastOrNull() ?: return null
    val series = tests.filter { it.side == newest.side }
    if (series.size < 2) return L10n.tr("Critical force tested %s", relative(newest.recordedAt))
    val previous = series[series.size - 2]
    val delta = newest.criticalForceKg - previous.criticalForceKg
    val when_ = SHORT_DATE.format(previous.recordedAt.atZone(ZoneId.systemDefault()))
    if (Math.abs(delta) < 0.05) return L10n.tr("Critical force held since %s", when_)
    val verb = if (delta > 0) L10n.tr("up") else L10n.tr("down")
    return L10n.tr("Critical force %s %s %s since %s", verb, WeightUnits.number(Math.abs(delta)), WeightUnits.symbol, when_)
}

// MARK: - Grouping (`MaxGripGroup` and `groupsOf` live beside the read, in `HistoryFeed`)

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

private val SHORT_DATE = LocalizedPattern("d MMM")

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
    benchmark: BenchmarkGroup,
    onMeasure: () -> Unit,
    onEdit: (GripSpec) -> Unit,
    onHistory: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val group = benchmark.maxes
    val tests = benchmark.tests
    val hasMax = group.records.isNotEmpty()
    val sides = remember(group) { presentSides(group) }
    val cfSides = remember(benchmark) { criticalForceSides(benchmark) }
    val line = remember(group, WeightUnits.current) { if (hasMax) progressLine(group) else null }
    val cfLine = remember(tests, WeightUnits.current) { criticalForceProgressLine(tests) }

    Card {
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
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tests.isNotEmpty()) CapsLabel(tr("Max"))
            if (hasMax) {
                CurrentReadout(group, sides)
            } else {
                Text(
                    tr("No max yet. Measure one to see critical force as a share of it."),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                )
            }
        }
        if (tests.isNotEmpty()) CriticalForceReadout(benchmark, cfSides)

        // A chart needs two points to have a direction; one record is a fact, not a trend.
        if (group.records.size + tests.size >= 2) {
            MaxChart(
                sides.map { side ->
                    MaxSeries(
                        side = side,
                        points = group.records.filter { it.side == side }
                            .map { MaxPoint(it.recordedAt.toEpochMilli().toDouble(), it.kg) },
                    )
                },
                Modifier.fillMaxWidth(),
                criticalForce = cfSides.map { side ->
                    MaxSeries(
                        side = side,
                        points = tests.filter { it.side == side }
                            .map { MaxPoint(it.recordedAt.toEpochMilli().toDouble(), it.criticalForceKg) },
                    )
                },
            )
        }

        line?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkTertiary)
        }
        cfLine?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkTertiary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (hasMax) {
                TextButton(onClick = { onEdit(group.grip) }, modifier = Modifier.weight(1f)
                    .testTag("maxes.edit.${group.key}")) {
                    Text(tr("Edit"), color = palette.inkPrimary, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            SecondaryButton(title = if (hasMax) tr("Measure again") else tr("Measure max"),
                modifier = Modifier.weight(1f).testTag("maxes.measure.${group.key}")) {
                onMeasure()
            }
        }
        // Only on a grip that has been tested: a critical force door on every card was an
        // orphan row, and the test's own setup reaches any grip.
        if (tests.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val historyLabel = L10n.tr("All critical force tests on %s", group.grip.spoken)
                TextButton(onClick = onHistory, modifier = Modifier.weight(1f)
                    .semantics { contentDescription = historyLabel }
                    .testTag("maxes.cf.history.${group.key}")) {
                    Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, tint = palette.inkPrimary,
                        modifier = Modifier.size(18.dp))
                    // Standing alone now that Measure asks max or critical force.
                    Text(tr("Critical force history"), color = palette.inkPrimary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/// Critical force per hand, each with its share of that hand's max when it was tested.
@Composable
private fun CriticalForceReadout(group: BenchmarkGroup, sides: List<Side>) {
    val palette = LocalGripPalette.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    @Composable fun Readout(side: Side, modifier: Modifier = Modifier) {
        group.tests.lastOrNull { it.side == side }?.let { test ->
            val pct = test.percentOfMax?.let { roundedPercent(it) }
            val label = L10n.tr("Critical force, %s",
                if (side == Side.both) L10n.tr("both hands") else side.displayName)
            val value = WeightUnits.text(test.criticalForceKg) +
                (pct?.let { ", " + L10n.tr("%d %% of max", it) } ?: "")
            Column(modifier.testTag("maxes.cf.current.${group.key}.${side.rawValue}")
                .clearAndSetSemantics { contentDescription = "$label, $value" },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(handName(side), style = MaterialTheme.typography.labelMedium, color = palette.inkSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    KgText(test.criticalForceKg, prominent = true)
                    pct?.let {
                        Text(tr("%d %%", it), style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CapsLabel(tr("Critical force"))
        if (largeText) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { sides.forEach { Readout(it) } }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                sides.forEach { Readout(it, Modifier.weight(1f)) }
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
private fun EmptyCard() {
    Card {
        CapsLabel(tr("No maxes yet"))
    }
}

/// The current WORKING numbers, trailing the title. One value for a both-hands grip; a
/// compact L/R pair once the hands have their own records — because 25 % of the left max and
/// 25 % of the right max are simply different kilograms, and this is where you see that.
@Composable
private fun CurrentReadout(group: MaxGripGroup, sides: List<Side>) {
    val palette = LocalGripPalette.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    @Composable fun Readout(side: Side, modifier: Modifier = Modifier) {
        newest(group, side)?.let { record ->
            val label = if (side == Side.both) tr("Shared max") else side.displayName
            Column(modifier.testTag("maxes.current.${group.key}.${side.rawValue}")
                .clearAndSetSemantics { contentDescription = "$label, ${WeightUnits.text(record.kg)}" },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = palette.inkSecondary)
                KgText(record.kg, prominent = true)
            }
        }
    }
    if (largeText) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { sides.forEach { Readout(it) } }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            sides.forEach { Readout(it, Modifier.weight(1f)) }
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
        MaxesTabScreen(onAddMax = {}, onMeasure = { _, _ -> }, onEdit = {}, feed = feed)
    }
}
