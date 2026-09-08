// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.engine.Fmt
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.l10n.trQuantity
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.preview.PreviewWorld
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// Saved-record management, reached through Maxes → Manage.
/// Numbers are added, read back and deleted here.
///
/// Two decisions worth stating, because both look like omissions:
///
/// **This is not a grip library.** There is no saved-grip list to curate and no "create
/// grip" step — the thing this app exists to refuse. The composer opens on a grip you
/// already use and every field is editable in place. A grip is still a VALUE.
///
/// **Nothing is ever edited.** `MaxRecordEntity` is append-only, so recording again for the
/// same grip and hand adds a row and the previous one becomes history. That is why a row
/// with a past behind it OPENS: without somewhere to show the earlier records, they would
/// exist, count for nothing, and be impossible to delete.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxesListScreen(
    /// The composer door — see `MaxesTabScreen.onAddMax`. `MaxEntrySheet` belongs to the
    /// wave that owns the input controls, so this screen only owes a named entry point.
    onAddMax: (GripSpec?) -> Unit,
    modifier: Modifier = Modifier,
    feed: HistoryFeed = LocalHistoryFeed.current,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()

    /// The `maxKey` whose earlier records are showing — at most one open at a time, the same
    /// accordion rule the builder's set rows follow.
    var expanded by remember { mutableStateOf<String?>(null) }
    var deleteFailed by remember { mutableStateOf(false) }

    LaunchedEffect(feed) { feed.refresh() }

    // Newest first, which is what makes the fold below correct: the first record in each
    // bucket is that grip-and-hand's CURRENT max.
    val newestFirst = remember(feed.maxRecords) { feed.maxRecords.sortedByDescending { it.recordedAt } }
    val histories = remember(newestFirst) { historiesOf(newestFirst) }
    val rows = remember(histories, expanded) { entriesOf(histories, expanded) }

    LazyColumn(
        modifier.fillMaxSize().readablePageWidth(),
        contentPadding = PaddingValues(
            start = Metrics.hPadding, end = Metrics.hPadding, top = 12.dp,
            bottom = 12.dp + LocalFloatingTabBarInset.current,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (histories.isEmpty()) {
            item("empty") { EmptyCard() }
        }

        item("add") {
            // The screen's PRIMARY action, drawn as a row rather than hidden behind a
            // toolbar glyph: on a screen you visit in order to add something, the add must
            // be the first thing under your thumb and must never scroll away behind a list.
            AddRow { onAddMax(null) }
        }

        if (deleteFailed) {
            item("delete-error") {
                Text(tr("Couldn't delete this max. Please try again."),
                    style = MaterialTheme.typography.bodySmall, color = palette.alarm)
            }
        }
        if (histories.isNotEmpty()) {
            item("label") {
                CapsLabel(tr("YOUR MAXES"), Modifier.padding(top = 10.dp, bottom = 2.dp))
            }

            items(rows, key = { it.id }) { entry ->
                DeletableRow(
                    label = entry.deleteLabel,
                    onDelete = {
                        scope.launch {
                            deleteFailed = !templates.deleteMax(entry.record)
                            if (!deleteFailed) feed.refresh()
                        }
                    },
                ) {
                    when (entry) {
                        is MaxEntry.Current -> GripRow(
                            history = entry.history,
                            isExpanded = expanded == entry.history.key,
                            reduceMotion = reduceMotion,
                        ) {
                            expanded = if (expanded == entry.history.key) null else entry.history.key
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        is MaxEntry.Earlier -> EarlierRow(entry.record, entry.grip)
                    }
                }
            }

            item("footnote") {
                // Provenance, stated plainly, now that BOTH sources exist. The rows carry
                // the distinction individually ("measured"), so this only has to say that
                // the distinction is there and what these numbers are for.
                Text(
                    tr("A max is either measured on the gauge or set by you — the measured ones say so. The percentages elsewhere in the app are worked out from these."),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                    modifier = Modifier.padding(top = 14.dp, bottom = 24.dp),
                )
            }
        }
    }
}

// MARK: - Row model

/// One grip AND HAND's records: the newest, and everything it superseded.
data class GripHistory(
    val key: String,
    val grip: GripSpec,
    val current: MaxRecordEntity,
    val earlier: List<MaxRecordEntity>,
)

/// A single list row. The two cases look genuinely different and carry different delete
/// labels, so they stay separate rather than sharing one row builder with flags.
sealed interface MaxEntry {
    val id: String
    val record: MaxRecordEntity
    val deleteLabel: String

    data class Current(val history: GripHistory) : MaxEntry {
        override val id: String get() = "grip-${history.key}"
        override val record: MaxRecordEntity get() = history.current
        /// Names the HAND: with a left and a right row for one grip, a label that only said
        /// the grip would be the same on both.
        override val deleteLabel: String
            get() {
                val hand = if (history.current.side == Side.both) ""
                else L10n.tr(", %s hand", history.current.side.displayName.lowercase(Locale.getDefault()))
                return L10n.tr("Delete this max for %s%s", history.grip.spoken, hand)
            }
    }

    data class Earlier(override val record: MaxRecordEntity, val grip: GripSpec) : MaxEntry {
        override val id: String get() = "record-${record.id}"
        override val deleteLabel: String get() = L10n.tr("Delete this earlier max for %s", grip.spoken)
    }
}

/// One bucket per grip **AND HAND** — see `MaxRecordEntity.maxKey` — in order of each
/// bucket's most recent record.
///
/// Bucketing on the grip alone would file your left and right maxes together, make whichever
/// you recorded second "current", and demote the other to history — so one hand's number
/// would vanish from the screen it was entered on. They are separate rows because they are
/// separate facts.
///
/// `records` must arrive NEWEST FIRST.
internal fun historiesOf(newestFirst: List<MaxRecordEntity>): List<GripHistory> {
    val order = mutableListOf<String>()
    val buckets = HashMap<String, MutableList<MaxRecordEntity>>()
    for (record in newestFirst) {
        val key = record.maxKey
        if (!buckets.containsKey(key)) order.add(key)
        buckets.getOrPut(key) { mutableListOf() }.add(record)
    }
    return order.mapNotNull { key ->
        val bucket = buckets[key] ?: return@mapNotNull null
        val current = bucket.firstOrNull() ?: return@mapNotNull null
        GripHistory(key, current.grip, current, bucket.drop(1))
    }
}

/// Flattened to ONE entry per list row on purpose: a list item that renders two rows leaves
/// it ambiguous which row a swipe belongs to.
internal fun entriesOf(histories: List<GripHistory>, expanded: String?): List<MaxEntry> =
    histories.flatMap { history ->
        if (expanded != history.key) listOf(MaxEntry.Current(history))
        else listOf(MaxEntry.Current(history)) + history.earlier.map { MaxEntry.Earlier(it, history.grip) }
    }

// MARK: - Rows

/// A max has no Undo. Revealing Delete and tapping it are separate, deliberate actions.
@Composable
private fun DeletableRow(label: String, onDelete: () -> Unit, content: @Composable () -> Unit) {
    run.nuri.getagrip.ui.components.SwipeActionRow(onDelete = onDelete, deleteLabel = label, content = content)
}

/// One grip and hand, its current max, and — when there is a past — a tap that reveals it.
///
/// The row is only clickable when there is something to open. A control that draws like a
/// control and does nothing is worse than a plain row.
@Composable
private fun GripRow(
    history: GripHistory,
    isExpanded: Boolean,
    reduceMotion: Boolean,
    onToggle: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val interaction = remember { MutableInteractionSource() }
    val chevronAngle by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = Motion.state(reduceMotion),
        label = "chevron",
    )

    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = Modifier
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            .then(
                if (history.earlier.isEmpty()) Modifier
                else Modifier
                    .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onToggle,
            )
                    // `scales = false`: a row-sized card that scaled on press would drag its
                    // own backdrop out from under it.
                    .pressFeedback(interaction, scales = false),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = spokenGrip(history)
                // A row that opens and shuts has to SAY which it is: the chevron rotates and
                // the earlier records appear, and neither of those reaches TalkBack. Only
                // when there is something to disclose — a lone record has no state.
                if (history.earlier.isNotEmpty()) {
                    stateDescription = L10n.tr(if (isExpanded) "Expanded" else "Collapsed")
                }
            },
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FingerGlyph(
                history.grip.fingers,
                position = history.grip.position,
                dot = 6.dp,
                gap = 3.dp,
                modifier = Modifier.padding(top = 5.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    history.grip.line,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detailLine(history),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )
            }
            KgText(history.current.kg, prominent = true)
            if (history.earlier.isNotEmpty()) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = palette.inkTertiary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(18.dp)
                        .rotate(chevronAngle),
                )
            }
        }
    }
}

/// A superseded record: indented, quieter, and dated, so the current one keeps the row's
/// weight. It is here to be READ and to be DELETABLE — nothing more.
@Composable
private fun EarlierRow(record: MaxRecordEntity, grip: GripSpec) {
    val palette = LocalGripPalette.current
    // The grip is here for the SPOKEN row only: an indented "12.0 kg · 3 May" read on its
    // own says nothing about which grip it belongs to. Read outside the semantics lambda,
    // which is not composable.
    val spoken = tr(
        "Earlier max for %s. %s kilograms, recorded %s.",
        grip.spoken,
        Fmt.fixed(record.kg, 1),
        relative(record.recordedAt),
    )
    Surface(
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = palette.card,
        // OUTSIDE the surface, so the indent is empty space rather than a wider card with
        // its content pushed over.
        modifier = Modifier
            .padding(start = 22.dp)
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
            },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(2.dp, 16.dp)
                    .background(palette.inkTertiary.copy(alpha = 0.35f))
                    .clearAndSetSemantics {},
            )
            Text(
                relative(record.recordedAt),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
                modifier = Modifier.weight(1f),
            )
            KgText(record.kg, prominent = false)
        }
    }
}

@Composable
private fun AddRow(onAdd: () -> Unit) {
    val palette = LocalGripPalette.current
    val interaction = remember { MutableInteractionSource() }
    // Captured HERE: a `drawBehind` lambda has no composition to read a CompositionLocal
    // from, so the dash's ink is resolved beside the row rather than inside its draw.
    val dashInk = palette.inkTertiary.copy(alpha = 0.45f)
    Row(
        Modifier
            .widthIn(max = Metrics.maxContentWidth)
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onAdd,
            )
            .pressFeedback(interaction, scales = false)
            .drawBehind {
                // A DASHED outline rather than a filled card: this is an invitation to add,
                // not one of the things that has been added.
                val stroke = Stroke(
                    width = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                )
                val r = Metrics.radiusCard.toPx()
                drawRoundRect(
                    color = dashInk,
                    style = stroke,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                )
            }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = palette.graphite, modifier = Modifier.size(20.dp))
        Text(
            tr("Add a max"),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = palette.graphite,
        )
    }
}

@Composable
private fun EmptyCard() {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = Modifier.widthIn(max = Metrics.maxContentWidth).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CapsLabel(tr("No maxes yet"))
            FingerGlyph(
                run.nuri.getagrip.engine.FingerSet.four,
                dot = 12.dp,
                gap = 6.dp,
                tint = palette.inkTertiary.copy(alpha = 0.55f),
            )
            Text(
                tr("A max records your hardest measured pull on a grip."),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            Text(
                tr("Percentage targets use the max saved for that grip and hand. A saved max is a reference, not a safe-load limit."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkSecondary,
            )
            Text(
                tr("Measure a peak on your gauge, or record a previous measurement. You can update it whenever you need."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
    }
}

/// Only the DEPARTURES from the default are marked — a named hand, and a measured
/// provenance. "Both hands" and "typed" are the unremarkable cases, and labelling them would
/// put two words on every row to distinguish nothing.
internal fun detailLine(history: GripHistory): String {
    val parts = mutableListOf(relative(history.current.recordedAt))
    if (history.current.side != Side.both) {
        parts += L10n.tr("%s hand", history.current.side.displayName.lowercase(Locale.getDefault()))
    }
    if (history.current.source == MaxSource.measured) parts += L10n.tr("measured")
    if (history.earlier.isNotEmpty()) parts += trQuantity("%d earlier", history.earlier.size)
    return parts.joinToString(" · ")
}

internal fun spokenGrip(history: GripHistory): String {
    val hand = if (history.current.side == Side.both) ""
    else L10n.tr(", %s hand", history.current.side.displayName.lowercase(Locale.getDefault()))
    // The trailing space is INSIDE the key, exactly as it is on iOS: French puts no space
    // before the date the way English does not either, but the word itself is what varies.
    val provenance =
        if (history.current.source == MaxSource.measured) L10n.tr("measured ") else L10n.tr("recorded ")
    var sentence = L10n.tr(
        "%s%s. Max %s kilograms, %s%s.",
        history.grip.spoken,
        hand,
        Fmt.fixed(history.current.kg, 1),
        provenance,
        relative(history.current.recordedAt),
    )
    if (history.earlier.isNotEmpty()) {
        val count = history.earlier.size
        sentence += L10n.tr(" %d earlier %s.", count, L10n.tr(if (count == 1) "max" else "maxes"))
    }
    return sentence
}

@Preview(name = "MaxesList", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun MaxesListPreview() {
    PreviewWorld { feed -> MaxesListScreen(onAddMax = {}, feed = feed) }
}
