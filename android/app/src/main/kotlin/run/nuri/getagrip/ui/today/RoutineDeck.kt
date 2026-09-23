// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.today

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.UUID
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.RoutineSummary
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import run.nuri.getagrip.ui.tour.TourTarget
import run.nuri.getagrip.ui.tour.tourAnchor

/// The routines as a PAGED DECK — Music's Top Picks, not a browse feed.
///
/// The card IS the chooser: the next routine peeks from the trailing edge, swiping to a card
/// chooses it (a day-scoped pin), and a new day or fresher reminder snaps back to `upNext`,
/// the bordered card. This replaced a rail of name-chips that only NAMED the card already
/// shown. Accepted cost: sibling names need a swipe to read.
///
/// **The deck exists the moment ANY routine does.** With one routine its neighbour is the
/// ghost card; without the deck "New routine" lived only in the ⋯ menu (Nuri, 2026-08-10).
///
/// TRANSLATION NOTE: iOS pages with `.viewAligned(limitBehavior: .always)` — one card per
/// gesture however hard the flick, because every settle is a deliberate pick.
/// `HorizontalPager` does this by default; do not "fix" it into a momentum carousel.
@Composable
fun RoutineDeck(
    routines: List<SessionTemplateEntity>,
    templates: TemplateStore,
    modifier: Modifier = Modifier,
    /// The card the app would front WITH NO HAND ON IT. The border wears this, never the card in
    /// front, so browsing still answers "which one is asked of me now".
    upNextID: UUID? = null,
    /// Where the deck should rest — a MIRROR of the selection rungs. Programmatic moves write it;
    /// only a settle on a different card (a person's swipe) reports back via `onSettled`.
    selectedID: UUID? = null,
    deviceState: ProgressorConnectionState = ProgressorConnectionState.Idle,
    battery: Double? = null,
    onSettled: (UUID) -> Unit = {},
    onStart: (SessionTemplateEntity) -> Unit = {},
    onStartTimerOnly: (SessionTemplateEntity) -> Unit = {},
    onOverview: (SessionTemplateEntity) -> Unit = {},
    onEdit: (SessionTemplateEntity) -> Unit = {},
    onDuplicate: (SessionTemplateEntity) -> Unit = {},
    onNew: () -> Unit = {},
    onMakePrimary: (SessionTemplateEntity) -> Unit = {},
    /// The routine as a QR code and link; the host freezes the request at the tap.
    onShare: (SessionTemplateEntity) -> Unit = {},
    /// Read somebody ELSE's code — about the app, not this routine, so it takes no argument.
    onScan: () -> Unit = {},
    onDelete: (SessionTemplateEntity) -> Unit = {},
    onDemo: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Keep natural sizes as pages leave composition. The create card borrows the final
    // real card's size, including connected/disconnected content and accessibility text.
    val cardHeights = remember(configuration, deviceState.isConnected) { mutableStateMapOf<UUID, Int>() }
    val newCardHeight = with(density) {
        (cardHeights[routines.lastOrNull()?.id] ?: cardHeights.values.maxOrNull() ?: 0).toDp()
    }
    val selectedIndex = routines.indexOfFirst { it.id == selectedID }.coerceAtLeast(0)
    // The ghost is the LAST page: creating waits BEHIND your routines, never at the ritual's
    // visual weight.
    val state = rememberPagerState(initialPage = selectedIndex) { routines.size + 1 }

    // Programmatic re-selection (day rolls, save lands, merge deletes the card). A settle on
    // the current page is filtered, so a swipe never loops through here.
    LaunchedEffect(selectedID, routines.size) {
        val target = routines.indexOfFirst { it.id == selectedID }
        if (target >= 0 && target != state.currentPage) {
            // Reduce Motion means NO TRAVEL, not a shorter one.
            if (reduceMotion) state.scrollToPage(target) else state.animateScrollToPage(target)
        }
    }

    // A settle on a swiped-to card is a pick. The ghost is excluded: parking on it is browsing
    // and must not become a stale pin.
    //
    // **Read through `rememberUpdatedState`, never captured.** The collector outlives many
    // compositions: a stale `selectedID` made a programmatic move read as a swipe (pinning, with
    // a tick nobody caused), and a stale `onSettled` pinned to the day the deck first appeared.
    val currentSelectedID by rememberUpdatedState(selectedID)
    val currentOnSettled by rememberUpdatedState(onSettled)
    LaunchedEffect(state, routines) {
        snapshotFlow { state.settledPage }.collect { page ->
            val routine = routines.getOrNull(page) ?: return@collect
            if (routine.id == currentSelectedID) return@collect
            // The tick names its cause: a swipe that changed which routine is up.
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            currentOnSettled(routine.id)
        }
    }

    HorizontalPager(
        state = state,
        modifier = modifier.fillMaxWidth(),
        // Full-bleed, so the neighbour peeks at the SCREEN edge; content padding puts a settled card
        // back on the house grid. The trailing margin is 8 dp wider: peek = margin − card gap, and
        // 12 dp of neighbour was a hairline where 20 reads as a card.
        contentPadding = PaddingValues(start = Metrics.hPadding, end = Metrics.hPadding + 8.dp),
        pageSpacing = 8.dp,
        key = { page -> routines.getOrNull(page)?.id?.toString() ?: "new-routine" },
        // Real cards measure their content; the create page keeps its neighbour's footprint.
        verticalAlignment = Alignment.Top,
    ) { page ->
        val routine = routines.getOrNull(page)
        val pageModifier = Modifier.graphicsLayer {
            // Both real and create cards follow the same depth change while swiping.
            val distance = abs((state.currentPage - page) + state.currentPageOffsetFraction).coerceIn(0f, 1f)
            scaleX = if (reduceMotion) 1f else 1f - distance * .02f
            scaleY = scaleX
        }
        if (routine == null) {
            NewRoutineGhost(onNew, onScan, pageModifier.heightIn(min = newCardHeight))
        } else {
            RoutineCard(
                modifier = pageModifier.onSizeChanged { cardHeights[routine.id] = it.height },
                summary = rememberRoutineSummary(templates, routine),
                completionText = templates.completionText(routine),
                // With one routine the border would mark the only real card.
                isUpNext = routines.size > 1 && routine.id == upNextID,
                deviceState = deviceState,
                battery = battery,
                onStart = { onStart(routine) },
                onStartTimerOnly = { onStartTimerOnly(routine) },
                onOverview = { onOverview(routine) },
                onEdit = { onEdit(routine) },
                onDuplicate = { onDuplicate(routine) },
                onNew = onNew,
                onMakePrimary = { onMakePrimary(routine) },
                onShare = { onShare(routine) },
                onScan = onScan,
                onDelete = { onDelete(routine) },
                onDemo = onDemo,
            )
        }
    }
}

/// NOT a filled card: a hairline outline reads "provisional", like an unselected chip — a
/// routine that COULD exist.
@Composable
private fun NewRoutineGhost(onNew: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    val interaction = remember { MutableInteractionSource() }
    // Read outside the semantics lambda, which is not composable.
    val spoken = tr("New routine. Opens the routine builder.")
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.radiusCard))
            .border(1.dp, palette.inkTertiary.copy(alpha = 0.35f), RoundedCornerShape(Metrics.radiusCard))
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        // Separate actions: the create region owns its tap and accessibility label;
        // scanning never bubbles into the routine builder.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Metrics.radiusInner))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onNew,
                )
                .pressFeedback(interaction, scales = false)
                .padding(12.dp)
                .semantics { contentDescription = spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = palette.inkSecondary, modifier = Modifier.size(26.dp))
            Text(
                tr("New routine"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            Text(
                tr("A rest-day plan, a max day —\nwhatever this one isn't."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
                textAlign = TextAlign.Center,
            )
        }
        SubtleScanAction(tr("Scan a routine"), onScan)
    }
}

/** Quiet visual weight with a full-size touch target. Creating remains the primary action. */
@Composable
private fun SubtleScanAction(title: String, onClick: () -> Unit) {
    val palette = LocalGripPalette.current
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.TextButton(
            onClick = onClick,
            interactionSource = interaction,
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = palette.inkSecondary),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.heightIn(min = 48.dp).pressFeedback(interaction, scales = false),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

/// With no routine there is nothing to start, and a card offering to would be lying.
///
/// Exactly ONE PRIMARY button; no "start from a preset" door — that would duplicate the
/// document one tap away and push the button off screen at accessibility sizes.
///
/// **"Scan a shared routine" is the exception**: its usual menu hangs off a routine card, and
/// here there is none, so a friend's code would have no door. It stays a small text action:
/// importing is something you do once.
@Composable
fun EmptyRoutineCard(
    modifier: Modifier = Modifier,
    /// Only while nothing is connected: answers "do I need the hardware first?" when it arises.
    showsGaugeNote: Boolean = true,
    onBuild: () -> Unit,
    /// Null (previews) removes the row rather than drawing a dead one.
    onScan: (() -> Unit)? = null,
) {
    val palette = LocalGripPalette.current
    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CapsLabel(tr("Your routine"))

            FingerGlyph(
                fingers = FingerSet.four,
                dot = 12.dp,
                tint = palette.inkTertiary.copy(alpha = 0.55f),
            )

            Text(
                tr("Get a Grip is built around one routine you actually commit to."),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            Text(
                tr("Start with a name and one set. Make it yours, then come back and pull."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkSecondary,
            )

            // ACT ONE's only lit control: on first launch the tour hands you to the builder from here,
            // and the step is `interactive`, so the tap lands.
            PrimaryButton(
                tr("Build my routine"),
                modifier = Modifier.tourAnchor(TourTarget.BuildRoutine),
                onClick = onBuild,
            )

            if (onScan != null) {
                SubtleScanAction(tr("Scan a shared routine"), onScan)
            }

            if (showsGaugeNote) {
                Text(
                    tr("You can connect your gauge later."),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                )
            }
        }
    }
}

@Preview(name = "Empty routine card", showBackground = true, widthDp = 380)
@Composable
private fun EmptyRoutineCardPreview() {
    GetAGripTheme {
        Box(Modifier.padding(20.dp)) { EmptyRoutineCard(onBuild = {}) }
    }
}

/// `TemplateStore.summary`, folded only when something it reads has moved.
///
/// A summary decodes the set blob and walks the plan three times, and the deck asked per card
/// on every composition (every swipe offset, every device-chip tick). Keyed on what `summary`
/// reads, so logs, climbs, benchmarks and maxes still redraw. `nextReminder` would go stale in
/// a memo; safe only because Today's header folds its own.
@Composable
internal fun rememberRoutineSummary(templates: TemplateStore, routine: SessionTemplateEntity): RoutineSummary =
    remember(
        routine,
        templates.completionsToday,
        templates.unattributedHangsToday,
        templates.climbToday,
        templates.benchmarkedToday,
        templates.maxTable,
    ) { templates.summary(routine) }
