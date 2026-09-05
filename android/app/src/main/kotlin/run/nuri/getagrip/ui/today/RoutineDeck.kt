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
import androidx.compose.runtime.remember
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
/// The card that used to sit under a rail of name-chips IS the chooser now: the next routine
/// peeks in from the trailing edge, swiping to a card chooses it (the same day-scoped pin the
/// old rail tap wrote), and a new day — or a fresher reminder — snaps the deck back to
/// `upNext`, the card wearing the border. This folds two controls that were pretending not to
/// be one: the chip NAMED a routine the card was already showing. The honest cost, accepted:
/// sibling NAMES aren't readable without a swipe. At the two or three routines this app is
/// built around, the peek plus each card's own done-dots carry what the rail carried.
///
/// **The deck exists the moment ANY routine does.** With one routine its only neighbour is
/// the ghost card, and that peek is honest: there genuinely is something behind the ritual
/// (creating the next one). Without it a single-routine user had no swipe to the ghost at
/// all and "New routine" lived only in the ⋯ menu (Nuri, 2026-08-10).
///
/// TRANSLATION NOTE: iOS is a paging `ScrollView` with `.viewAligned(limitBehavior: .always)`
/// — one card per gesture however hard the flick, because every settle here is a deliberate
/// pick rather than browsing. `HorizontalPager` is that behaviour by default (its snap
/// distance is one page), so the rule needs no code, only this note so nobody "fixes" it into
/// a momentum carousel.
@Composable
fun RoutineDeck(
    routines: List<SessionTemplateEntity>,
    templates: TemplateStore,
    modifier: Modifier = Modifier,
    /// The card the app would front WITH NO HAND ON IT — the border wears this, never the
    /// card currently in front, so swiping away to browse still answers "which one is being
    /// asked of me right now".
    upNextID: UUID? = null,
    /// Where the deck should be resting. A MIRROR of the selection rungs: programmatic moves
    /// (a new day, a deleted routine) write it in, and only a settle on a card that is NOT
    /// this one — i.e. a swipe a person made — reports back through `onSettled`.
    selectedID: UUID? = null,
    deviceState: ProgressorConnectionState = ProgressorConnectionState.Idle,
    battery: Double? = null,
    onSettled: (UUID) -> Unit = {},
    onStart: (SessionTemplateEntity) -> Unit = {},
    onStartTimerOnly: (SessionTemplateEntity) -> Unit = {},
    onEdit: (SessionTemplateEntity) -> Unit = {},
    onDuplicate: (SessionTemplateEntity) -> Unit = {},
    onNew: () -> Unit = {},
    onMakePrimary: (SessionTemplateEntity) -> Unit = {},
    /// The routine as a QR code and a link. The host freezes the request at the tap, so the
    /// deck can keep moving without changing the code somebody is pointing a camera at.
    onShare: (SessionTemplateEntity) -> Unit = {},
    /// Read somebody ELSE's code — a fact about the app, not about this routine, which is
    /// why it takes no argument.
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
    // The ghost is the LAST page, never a real routine's neighbour: the next thing waits
    // BEHIND the things you have, so creating never sits at the same visual weight as the
    // ritual (the rule the rail enforced by having no `+` at all).
    val state = rememberPagerState(initialPage = selectedIndex) { routines.size + 1 }

    // Programmatic re-selection: the day rolling over, a save landing, a merge deleting the
    // card under you. The swipe direction never loops through here — a settle on the page we
    // are already on is filtered, and pinning is what makes `selectedID` equal the settle.
    LaunchedEffect(selectedID, routines.size) {
        val target = routines.indexOfFirst { it.id == selectedID }
        if (target >= 0 && target != state.currentPage) {
            // Reduce Motion means NO TRAVEL, not a shorter one — a page that flies past
            // three cards is exactly the motion the setting exists to refuse.
            if (reduceMotion) state.scrollToPage(target) else state.animateScrollToPage(target)
        }
    }

    // A settle on a card someone swiped to is the old rail tap. The ghost is excluded on
    // purpose: parking on it is browsing, not picking a ritual, and it must not survive as a
    // stale pin.
    LaunchedEffect(state, routines) {
        snapshotFlow { state.settledPage }.collect { page ->
            val routine = routines.getOrNull(page) ?: return@collect
            if (routine.id == selectedID) return@collect
            // The tick names its cause: a swipe that actually changed which routine is up.
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSettled(routine.id)
        }
    }

    HorizontalPager(
        state = state,
        modifier = modifier.fillMaxWidth(),
        // Full-bleed: the deck escapes the column's padding so the neighbour peeks at the
        // SCREEN edge (the Music carousel move), then the content padding puts a settled card
        // back on the house grid, aligned with the title above it. The trailing margin is
        // deliberately 8 dp wider than the grid: peek = trailing margin − card gap, and 12 dp
        // of neighbour was a hairline where 20 reads as a card.
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
                summary = templates.summary(routine),
                completionText = templates.completionText(routine),
                // The border only exists where there are siblings to distinguish — with one
                // routine it would mark the only real card there is.
                isUpNext = routines.size > 1 && routine.id == upNextID,
                deviceState = deviceState,
                battery = battery,
                onStart = { onStart(routine) },
                onStartTimerOnly = { onStartTimerOnly(routine) },
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

/// Deliberately NOT a filled card: a hairline outline against the tonal surfaces of its
/// neighbours is the same "provisional" reading as an unselected chip — this is a routine
/// that COULD exist, next to ones that do.
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

/// With no routine at all there is nothing to start, and a card offering to would be lying.
///
/// Exactly ONE PRIMARY button. No "start from a preset" second door — the prefill is the
/// document's initial state INSIDE the builder, where it can be edited in place, and
/// rendering six read-only set rows here would duplicate the document you are one tap from
/// while pushing the button a screen and a half down at accessibility sizes.
///
/// **"Scan a shared routine" is the one exception, and it is not a second way to do the same
/// thing.** The menu that normally carries it hangs off a routine CARD, and on this screen
/// there is no card — so without this row somebody whose friend has just sent them a code
/// has no door at all, on the one screen where they are most likely to be looking for one.
/// It stays a small text action under the primary: building your own is what the app is for, and
/// importing somebody else's is a thing you do once.
@Composable
fun EmptyRoutineCard(
    modifier: Modifier = Modifier,
    /// Shown only while nothing is connected — it removes the "do I need the hardware in my
    /// hand first?" hesitation at exactly the moment it occurs.
    showsGaugeNote: Boolean = true,
    onBuild: () -> Unit,
    /// Null in a preview, and null is what removes the row rather than drawing a dead one.
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

            // ACT ONE's only lit control. On a genuine first launch this card is all there
            // is, so the tour hands you to the builder from here — and this step is
            // `interactive`, so the scrim's hit region is punched and the tap actually lands.
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
