// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.UUID
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.LadderRung
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineSummary
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.ui.components.EdgeMark
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.climbNotch
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.components.tint
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.tour.TourTarget
import run.nuri.getagrip.ui.tour.tourAnchor

/// The routine you are committing to, as one card, in three ZONES: identity + today's
/// status (tight), the plan as one line (which IS the edit surface), and the one action.
/// Spacing does the grouping — 6 dp inside a zone, 16 dp between zones — because proximity
/// is how the eye assigns belonging: when every row sat a uniform 14 dp from its neighbour,
/// title, dots, plan and button read as six unrelated things "slapped in" (Nuri's words,
/// and he was right).
///
/// **The grip ladder and the platter it sat in left this card on 2026-08-17** — the per-set
/// finger diagrams read as clutter on a dashboard (Nuri's call). Today is a dashboard, not a
/// document: what a card here owes you is which routine, how much of it you have done, what
/// it costs and the way in. Which fingers on which edge is what the editor and the runner
/// are for, and both are one tap away.
///
/// Every input is a VALUE — `RoutineSummary` rather than a `SessionTemplateEntity` — so the
/// card previews and reasons without a store, and so every derived number is computed once
/// in `TemplateStore` instead of in a body that runs on every frame of a scroll.
/// `completionText` comes in for the same reason it exists there: the spoken sentence and
/// the "1 of 2" fragment beside it must never be able to drift apart, and rebuilding the
/// sentence here would be a second source of truth.
///
/// TRANSLATION NOTE: iOS splits `SolidPrimaryButton` from the glass one because the
/// context-menu lift re-composites this subtree and Liquid Glass ghosts through it. Android
/// has no glass and no lift preview, so the house `PrimaryButton`/`SecondaryButton` are the
/// only buttons there are and that whole distinction evaporates.
@Composable
fun RoutineCard(
    summary: RoutineSummary,
    /// `TemplateStore.completionText(_)` — a whole sentence, which is what TalkBack reads
    /// in place of the numeral fragment.
    completionText: String,
    modifier: Modifier = Modifier,
    /// Marks the deck's HOME card — the routine the app would front on its own (the last
    /// reminder to call, else the one mid-ritual, else the primary). One quiet graphite
    /// line, so swiping away to browse and back still answers "which one is being asked of
    /// me right now". Never set on a single-routine screen, where it would distinguish the
    /// only thing there is.
    isUpNext: Boolean = false,
    deviceState: ProgressorConnectionState = ProgressorConnectionState.Idle,
    battery: Double? = null,
    onStart: () -> Unit = {},
    /// The same session with no gauge — timers, count-in and hand prompts only.
    onStartTimerOnly: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onNew: () -> Unit = {},
    onMakePrimary: () -> Unit = {},
    /// The routine as a QR code and a link — the sheet is hosted by Today, which freezes the
    /// request at the tap so a swipe behind it cannot change the code on screen.
    onShare: () -> Unit = {},
    /// Read somebody ELSE's code. Android-only; see the note on `TodayMenu`.
    onScan: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDemo: () -> Unit = {},
) {
    val palette = LocalGripPalette.current
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
        modifier = modifier
            .fillMaxWidth()
            // Graphite, not bleu: bleu is the live-force signal, and "your next ritual" is
            // ink-family information like the done-dots. 1.5 dp at half strength sits one
            // clear step above the ghost card's hairline (0.35 tertiary) — the ghost
            // outline means "could exist", this means "is the one" — while staying far
            // below an alarm. A STROKE, so it survives greyscale and every colour vision;
            // TalkBack hears it on the title instead.
            .then(
                if (isUpNext) {
                    Modifier.border(
                        1.5.dp,
                        palette.graphite.copy(alpha = 0.5f),
                        RoundedCornerShape(Metrics.radiusCard),
                    )
                } else {
                    Modifier
                }
            )
            // The long-press mirror of the ⋯ menu: free discoverability at zero hit-target
            // cost, since a long press is not a gesture anything else on this screen wants.
            //
            // TRANSLATION NOTE: the obvious spelling is `combinedClickable`, but it REQUIRES
            // an `onClick`, and this card has no whole-surface tap — iOS's `.contextMenu`
            // adds none either. A no-op click would publish a phantom "double-tap to
            // activate" to TalkBack on a surface where nothing happens. `detectTapGestures`
            // carries the long press alone; the accessible door is the ⋯ button, which is
            // labelled and 44 dp.
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { menuOpen = true })
            }
            // A raw pointer gesture is invisible to TalkBack, so the long press is ALSO
            // published as a semantic action. The ⋯ button is still the primary accessible
            // door — this is the mirror, exactly as the gesture mirrors the button by sight.
            .semantics {
                onLongClick(label = L10n.tr("Routine options")) { menuOpen = true; true }
            }
            // The tour lights the WHOLE card for its first step: "one card, one ritual".
            .tourAnchor(TourTarget.RoutineCard),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Zone 1 — identity and today's status. The dots are a SUBTITLE, locked to the
            // name they qualify; letting them float equidistant between title and the plan
            // was half of what made them read as loose parts.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TitleRow(
                    summary = summary,
                    isUpNext = isUpNext,
                    menuOpen = menuOpen,
                    onMenuOpen = { menuOpen = true },
                    onMenuDismiss = { menuOpen = false },
                    onEdit = onEdit,
                    onDuplicate = onDuplicate,
                    onNew = onNew,
                    onMakePrimary = onMakePrimary,
                    onStartTimerOnly = onStartTimerOnly,
                    onShare = onShare,
                    onScan = onScan,
                    onDelete = onDelete,
                )
                CompletionRow(summary, completionText)
            }
            PlanRow(summary, onEdit)
            StartBlock(summary, deviceState, battery, onStart, onStartTimerOnly, onDemo)
        }
    }
}

// MARK: - 1 · Title

@Composable
private fun TitleRow(
    summary: RoutineSummary,
    isUpNext: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onNew: () -> Unit,
    onMakePrimary: () -> Unit,
    onStartTimerOnly: () -> Unit,
    onShare: () -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalGripPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The routine's SIGNATURE grip leads the name. When the grip ladder left, it took
        // every drawn element on the card with it and the surface went typographic (Nuri,
        // 2026-08-17: "bland and text heavy"); an identical badge on every card was the
        // first fix and failed the same day ("I don't like how all routines have the same
        // logo"). One derived mark per card: identity that differs exactly when the
        // routines do, never the per-set inventory that was removed as clutter.
        EdgeMark(
            fingers = summary.signatureFingers ?: FingerSet.four,
            modifier = Modifier.padding(end = 10.dp),
            rungTint = PlanMath.IntensityBand.band(summary.peakIntensity).tint(palette),
        )

        Text(
            summary.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                // The border's spoken counterpart — a stroke is invisible to TalkBack, and
                // "which card is being asked of me" must not be sighted-only.
                .semantics {
                    if (isUpNext) stateDescription = L10n.tr("Up next")
                },
        )

        Spacer(Modifier.width(8.dp))

        // 44 dp target around a bare glyph, and the menu ANCHORS here — so the long press on
        // the card body and the tap on this button raise the same menu in the same place.
        Box {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onMenuOpen() }
                    .pressFeedback(interaction),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.MoreHoriz,
                    contentDescription = tr("Routine options"),
                    tint = palette.inkSecondary,
                )
            }
            TodayMenu(
                expanded = menuOpen,
                onDismiss = onMenuDismiss,
                onEdit = onEdit,
                onDuplicate = onDuplicate,
                onNew = onNew,
                onMakePrimary = onMakePrimary,
                onStartTimerOnly = onStartTimerOnly,
                onShare = onShare,
                onScan = onScan,
                onDelete = onDelete,
            )
        }
    }
}

// MARK: - 2 · Completion

@Composable
private fun CompletionRow(summary: RoutineSummary, completionText: String) {
    val palette = LocalGripPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            // ONE element with the store's sentence as its label. The dots and the fragment
            // beside them are two readings of one fact; spoken twice that is a duplicate
            // swipe, not extra information.
            .clearAndSetSemantics { contentDescription = completionText },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionDots(summary)

        // A CLIMB DAY says what happened, not what didn't. "0 of 2 today" beside a
        // checkmark is the card contradicting itself, and it is precisely the "you didn't
        // train" reading this feature exists to stop — the tally counts hang sessions, and
        // on this day the training was somewhere else.
        val climb = summary.climbedToday
        when {
            climb != null -> {
                Text(
                    if (climb == SessionKind.climbLimit) tr("Limit session") else tr("Volume session"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
                Text(
                    hangSuffix(summary.completedToday),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary,
                )
            }
            summary.benchmarkedToday -> {
                // Same anatomy as the climb line: what the day WAS, then any hangs as the
                // extra. Never "at the gym" — testing maxes is a different day.
                Text(
                    tr("Maxes tested"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
                if (summary.completedToday > 0) {
                    Text(
                        if (summary.completedToday == 1) tr("· 1 hang") else tr("· %d hangs", summary.completedToday),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary,
                    )
                }
            }
            summary.isOnDemand -> {
                // No target, so no tally and NO GUILT — "not done yet today" is exactly the
                // sentence a whenever routine exists to never say.
                val done = summary.completedToday > 0
                Text(
                    if (done) tr("Done today") else tr("Whenever you're fresh"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (done) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (done) palette.inkPrimary else palette.inkSecondary,
                )
            }
            summary.sessionsPerDay > 1 -> {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // NO ROLL. Every numeral in this app is a plain text swap — the Compose
                    // digit roll measured "really bad and super laggy" on the Realme.
                    Text(
                        "${summary.completedToday}",
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = palette.inkPrimary,
                    )
                    Text(
                        tr("of %d today", summary.sessionsPerDay),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary,
                    )
                }
            }
            else -> {
                // A "1 of 1" readout is silly, so the one-a-day routine says the thing the
                // number was standing in for.
                val done = summary.completedToday > 0
                Text(
                    if (done) tr("Done today") else tr("Not done yet today"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done) palette.inkPrimary else palette.inkSecondary,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (summary.targetMet) {
            // Graphite, never green: green is not chrome in this palette, and filled ink
            // already means "a session that happened" in the dots and the strip.
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = palette.graphite,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/// "at the gym", plus any hang rounds that happened anyway — said second, because they are
/// the extra on a day the training already counted.
private fun hangSuffix(completed: Int): String = when (completed) {
    0 -> L10n.tr("at the gym")
    1 -> L10n.tr("at the gym · 1 hang")
    else -> L10n.tr("at the gym · %d hangs", completed)
}

/// **CIRCLES ARE SESSIONS. BARS ARE FINGERS.** 16 dp, not the caption-sized mark this
/// started as: at 9 dp a row of small round marks reads as decoration rather than as a
/// COUNT, and "two of these" is the entire message. Spacing scales with the dot for the
/// same reason — two touching circles read as one shape.
@Composable
private fun SessionDots(summary: RoutineSummary) {
    val palette = LocalGripPalette.current
    // Scaled off the body text like iOS's `@ScaledMetric(relativeTo: .body)`, and CAPPED
    // for the reason `ConsistencyCard`'s strip is: a repeated cell multiplies, and a row of
    // marks that widens the card is the accessibility3 clipping bug.
    val scale = LocalDensity.current.fontScale.coerceAtMost(1.4f)
    val dot = 16.dp * scale

    Row(horizontalArrangement = Arrangement.spacedBy(dot * 0.45f)) {
        when {
            // ONE notched dot on a climb day, not a row of empty rings: the rings count
            // hang sessions owed, and nothing is owed. A benchmark day gets the same single
            // dot, plain — the notch stays a climbing mark.
            summary.climbedToday != null -> Box(
                Modifier.size(dot).clip(CircleShape).background(palette.graphite).climbNotch(true),
            )
            summary.benchmarkedToday -> Box(
                Modifier.size(dot).clip(CircleShape).background(palette.graphite),
            )
            summary.isOnDemand -> {
                // No slots owed, so no empty rings to fill — one dot appears only once a
                // session happened.
                if (summary.completedToday > 0) {
                    Box(Modifier.size(dot).clip(CircleShape).background(palette.graphite))
                }
            }
            else -> repeat(maxOf(1, summary.sessionsPerDay)) { index ->
                if (index < summary.completedToday) {
                    Box(Modifier.size(dot).clip(CircleShape).background(palette.graphite))
                } else {
                    // 1.5 dp, not 2: the hollow ring has to sit at the same optical weight
                    // as the filled dot beside it. 0.85 opacity, measured twice on the dark
                    // screenshot — 0.5 hit 2.1:1 and 0.75 still only 2.98:1, because a
                    // 1.5 dp ring is mostly antialiased edge and its peak pixel never
                    // reaches the stroke colour. Under the 3:1 floor a mark that MEANS
                    // something ("not done yet") is decoration; this clears it with margin.
                    Box(
                        Modifier
                            .size(dot)
                            .border(1.5.dp, palette.inkTertiary.copy(alpha = 0.85f), CircleShape),
                    )
                }
            }
        }
    }
}

// MARK: - 2 · The plan, in one line — and the line IS the editor entry

/// What the routine costs — "20 mm · 6 sets · 36 pulls · ≈21 min" — and tapping it opens the
/// editor. The door to change the plan is still the plan itself, which is the rule that
/// retired the old chevron footnote stranded at the card's foot, three rows away from the
/// thing it edited.
///
/// **No platter.** The inset well existed because the card had a DIAGRAM in it and a diagram
/// floating on the same surface as text reads as debris; with the ladder gone there is
/// nothing to frame, and a platter drawn around a single footnote makes a sentence look
/// like a text field. The chevron and the press feedback are what say this row is a door.
@Composable
private fun PlanRow(summary: RoutineSummary, onEdit: () -> Unit) {
    val palette = LocalGripPalette.current
    val interaction = remember { MutableInteractionSource() }
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Metrics.radiusInner))
            .clickable(
                interactionSource = interaction,
                indication = null,
                // The row IS the door to the editor — a full-width row that opens something
                // is a button, and without this TalkBack never says the control type.
                role = Role.Button,
                onClick = onEdit,
            )
            .pressFeedback(interaction, scales = false)
            .tourAnchor(TourTarget.GripLadder)
            .semantics {
                contentDescription = L10n.tr("Edit routine")
                // The card no longer draws the grips, so it must not speak them either.
                // The intensity suffix rides here because the rung's colour is invisible to
                // TalkBack and to greyscale — the number is the fact, the colour the glance.
                stateDescription = summary.metaLine + intensitySuffix(summary)
            },
    ) {
        val stats = PlanRowStats.of(summary)
        val form = PlanRowFit.form(
            availableDp = maxWidth.value,
            fontScale = fontScale,
            full = stats.map { it.full },
            compact = stats.map { it.compact },
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (form == PlanRowFit.Form.Sentence) {
                // At accessibility sizes this is the ONLY statement of the plan on the card,
                // and truncating "≈21 min" off the end of it costs the reader the fact this
                // row exists for. Two lines only ever appear on a screen that already
                // scrolls.
                Text(
                    summary.metaLine,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = palette.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                val compact = form == PlanRowFit.Form.Compact
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stats.forEach { stat ->
                        Stat(stat.icon, if (compact) stat.compact else stat.full)
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/// The plan's numbers with a symbol each. The VALUE carries the ink (secondary, medium);
/// the symbol is quiet (tertiary, deliberately decorative — each one sits beside the word
/// that names it, so it owes nothing to the 3:1 graphics floor).
@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    val palette = LocalGripPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = palette.inkTertiary, modifier = Modifier.size(13.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
            maxLines = 1,
        )
    }
}

/// The spoken (and colourblind-proof) form of the rung's colour.
private fun intensitySuffix(summary: RoutineSummary): String {
    val peak = summary.peakIntensity ?: return ""
    return L10n.tr(". Peak target %d percent of max.", Math.round(peak * 100))
}

/// One glyphed stat, in both of its written forms.
///
/// Compact keeps every symbol and sheds only what the symbol makes redundant: the count
/// words go ("6 sets" → "6" beside the stack the reader learned from the full row), and the
/// units that disambiguate a bare number stay, glued to it ("20–10mm", "≈11min").
internal data class PlanRowStat(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val full: String,
    val compact: String,
)

internal object PlanRowStats {
    fun of(summary: RoutineSummary): List<PlanRowStat> = buildList {
        // `edgeLine`, not `sharedEdgeMM`: a mixed ladder states its span in ladder order
        // ("20–10 mm") — dropping the edge because sets disagree read as the app not
        // knowing its own routine.
        summary.edgeLine?.let {
            add(PlanRowStat(Icons.Outlined.Straighten, it, it.replace(" mm", "mm")))
        }
        add(
            PlanRowStat(
                Icons.Outlined.Layers,
                L10n.tr("%d %s", summary.setCount, L10n.tr(if (summary.setCount == 1) "set" else "sets")),
                "${summary.setCount}",
            )
        )
        add(
            PlanRowStat(
                Icons.Outlined.Repeat,
                L10n.tr("%d %s", summary.totalReps, L10n.tr(if (summary.totalReps == 1) "pull" else "pulls")),
                "${summary.totalReps}",
            )
        )
        val minutes = PlanMath.approxMinutes(summary.estimatedSeconds)
        add(PlanRowStat(Icons.Outlined.Schedule, minutes, minutes.replace(" min", "min").replace(" s", "s")))
    }
}

/// **Which of the three forms the plan row draws — full glyph row, COMPACT glyph row, or
/// the plain sentence — as a pure function of the width and the font scale.**
///
/// iOS spells this `ViewThatFits(in: .horizontal)` with the three candidates in order. There
/// is no such measuring container in Compose (`SubcomposeLayout` is the nearest thing and
/// costs an extra measure pass on a card that redraws with every completion), so the choice
/// is arithmetic — and being arithmetic is what lets a JVM test pin it rather than a
/// screenshot, exactly as `DialLadder` does for the dial's drawing.
///
/// The estimate is deliberately a LOWER-CASE fact: it does not need to know the real font
/// metrics, only to fall to the next form before the last stat truncates. The three
/// failures it exists to prevent are all recorded on iOS — the edge SPAN plus four symbols
/// overflowed on hardware and truncated its own stat ("20–10…"), and the first fix fell
/// straight past compact to the sentence, which threw the icons away on exactly the routine
/// the span exists for (Nuri: "the little icons being gone is sad").
internal object PlanRowFit {

    enum class Form { Full, Compact, Sentence }

    /// The accessibility rung. `Chips.kt` already reads 1.3 as Android's `.accessibility1`,
    /// and this is the same threshold: a row of pictograms cannot wrap, and someone who
    /// asked for big text is served by words.
    const val SENTENCE_FONT_SCALE = 1.3f

    /// Roughly what one character of the stat's `bodySmall` costs. 12 sp at ~0.55 advance —
    /// mixed digits and short words at medium weight. Over-estimating is the safe direction:
    /// it falls to compact a few points early, where under-estimating truncates.
    private const val CHAR_DP_AT_SCALE_ONE = 12f * 0.55f

    /// Symbol plus its 4 dp gap.
    private const val GLYPH_DP = 13f + 4f

    /// The chevron and the 6 dp before it.
    private const val CHEVRON_DP = 18f + 6f

    fun form(
        availableDp: Float,
        fontScale: Float,
        full: List<String>,
        compact: List<String>,
    ): Form {
        if (fontScale >= SENTENCE_FONT_SCALE) return Form.Sentence
        if (width(full, fontScale, spacingDp = 13f) <= availableDp) return Form.Full
        if (width(compact, fontScale, spacingDp = 10f) <= availableDp) return Form.Compact
        return Form.Sentence
    }

    /// What a glyph row of these labels costs, chevron included.
    fun width(labels: List<String>, fontScale: Float, spacingDp: Float): Float {
        if (labels.isEmpty()) return CHEVRON_DP
        val text = labels.sumOf { it.length } * CHAR_DP_AT_SCALE_ONE * fontScale
        val glyphs = labels.size * GLYPH_DP * fontScale
        val gaps = (labels.size - 1) * spacingDp
        return text + glyphs + gaps + CHEVRON_DP
    }
}

// MARK: - 3 · Start

@Composable
private fun StartBlock(
    summary: RoutineSummary,
    deviceState: ProgressorConnectionState,
    battery: Double?,
    onStart: () -> Unit,
    onStartTimerOnly: () -> Unit,
    onDemo: () -> Unit,
) {
    val palette = LocalGripPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (summary.targetMet) {
            // Demoted, never gone: a bonus session must stay possible, and nagging must not.
            SecondaryButton(
                title = tr("Start another"),
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.fillMaxWidth().tourAnchor(TourTarget.StartButton),
                onClick = onStart,
            )
        } else {
            // Graphite, not bleu — bleu is the live-force signal and is spent the moment the
            // runner opens. And ALWAYS enabled: the runner's first phase is
            // connect-and-tare, so tapping while disconnected is the common path. Disabling
            // the ritual's one button because a peripheral has not been asked for yet turns
            // the ritual into a chore.
            PrimaryButton(
                title = startTitle(summary, deviceState),
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.tourAnchor(TourTarget.StartButton),
                onClick = onStart,
            )
        }

        connectionNote(deviceState, palette.inkTertiary, palette.alarm)?.let { NoteRow(it) }
        batteryNote(battery, palette.armed)?.let { NoteRow(it) }

        // **Only while there is no gauge on the line.** Flat battery, left at home, Bluetooth
        // off — the cases where the ritual would otherwise just not happen (Nuri,
        // 2026-08-09). Offering it beside a connected Progressor would be offering to throw
        // the measurement away, which nobody wants at 8 a.m.; it stays reachable there
        // through the ⋯ menu.
        if (!deviceState.isConnected) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    // 44 tall for the target, but pulled up tight against the note above it:
                    // the row's own height is the floor, and the 10 dp stack gap on top of
                    // it was pure spend on a page that has to fit.
                    .padding(top = 0.dp)
                    .clip(RoundedCornerShape(Metrics.radiusInner))
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onStartTimerOnly,
                    )
                    .pressFeedback(interaction, scales = false)
                    .tourAnchor(TourTarget.StartWithoutGauge)
                    .semantics {
                        stateDescription = L10n.tr("Runs the timers and hand prompts only. Nothing is measured.")
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tr("Start without a gauge"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.graphite,
                )
            }
        }

        when (deviceState) {
            // Always compiled in, never behind a debug flag — without hardware, on an
            // emulator or in review, this is the only way to see it work.
            is ProgressorConnectionState.Unsupported ->
                SecondaryButton(title = tr("Try demo mode"), modifier = Modifier.fillMaxWidth(), onClick = onDemo)
            else -> Unit
        }
    }
}

/// The ordinal drops out entirely once disconnected: "Connect and start second session" is
/// thirty characters, and the line above already says which session this is.
private fun startTitle(summary: RoutineSummary, deviceState: ProgressorConnectionState): String {
    if (!deviceState.isConnected) return L10n.tr("Connect and start")
    if (summary.sessionsPerDay <= 1) return L10n.tr("Start session")
    return when (summary.completedToday) {
        0 -> L10n.tr("Start first session")
        1 -> L10n.tr("Start second session")
        2 -> L10n.tr("Start third session")
        3 -> L10n.tr("Start fourth session")
        else -> L10n.tr("Start session %d", summary.completedToday + 1)
    }
}

private data class Note(
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val text: String,
    val tint: Color,
)

private fun connectionNote(
    state: ProgressorConnectionState,
    quiet: Color,
    alarm: Color,
): Note? = when (state) {
    is ProgressorConnectionState.Connected -> null
    is ProgressorConnectionState.Idle, is ProgressorConnectionState.Disconnected ->
        Note(null, L10n.tr("Keep the gauge unloaded while it connects and tares."), quiet)
    is ProgressorConnectionState.Scanning, is ProgressorConnectionState.Connecting ->
        Note(null, L10n.tr("Searching for your gauge…"), quiet)
    is ProgressorConnectionState.BluetoothOff ->
        Note(Icons.Outlined.Warning, L10n.tr("Bluetooth is off — turn it on to measure."), alarm)
    is ProgressorConnectionState.Unauthorized ->
        Note(Icons.Outlined.Warning, L10n.tr("Bluetooth access is off for Get a Grip."), alarm)
    is ProgressorConnectionState.Unsupported ->
        Note(null, L10n.tr("This device has no Bluetooth radio."), quiet)
}

/// Non-blocking, and amber rather than red: a low gauge battery is a thing to know before
/// you start, not a reason not to.
private fun batteryNote(battery: Double?, armed: Color): Note? {
    if (battery == null || battery >= 0.15) return null
    return Note(
        Icons.Outlined.BatteryAlert,
        L10n.tr("Gauge battery at %d%% — charge it soon.", Math.round(battery * 100)),
        armed,
    )
}

@Composable
private fun NoteRow(note: Note) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        note.icon?.let {
            Icon(it, contentDescription = null, tint = note.tint, modifier = Modifier.size(15.dp))
        }
        Text(note.text, style = MaterialTheme.typography.bodySmall, color = note.tint)
    }
}

// MARK: - Previews

private fun previewSummary(
    name: String = L10n.tr("Daily no-hangs"),
    sessionsPerDay: Int = 2,
    completedToday: Int = 0,
    climb: SessionKind? = null,
    benchmarked: Boolean = false,
    peak: Double? = 0.25,
): RoutineSummary {
    val grips = listOf(
        GripSpec(edgeMM = 20),
        GripSpec(edgeMM = 20, fingers = FingerSet.frontTwo),
        GripSpec(edgeMM = 20, fingers = FingerSet.backThree),
    )
    return RoutineSummary(
        id = UUID.randomUUID(),
        name = name,
        ladder = grips.mapIndexed { index, grip -> LadderRung(index, grip, 3) },
        setCount = 6,
        totalReps = 36,
        sharedEdgeMM = 20,
        estimatedSeconds = 21 * 60,
        sessionsPerDay = sessionsPerDay,
        completedToday = completedToday,
        nextReminder = null,
        climbedToday = climb,
        benchmarkedToday = benchmarked,
        peakIntensity = peak,
    )
}

@Preview(name = "Card · first session", showBackground = true, widthDp = 380)
@Composable
private fun RoutineCardFirstPreview() {
    GetAGripTheme {
        Box(Modifier.padding(20.dp)) {
            RoutineCard(
                previewSummary(),
                "No sessions done today, 2 planned",
                isUpNext = true,
                deviceState = ProgressorConnectionState.Connected,
            )
        }
    }
}

@Preview(name = "Card · one done", showBackground = true, widthDp = 380)
@Composable
private fun RoutineCardOneDonePreview() {
    GetAGripTheme {
        Box(Modifier.padding(20.dp)) {
            RoutineCard(
                previewSummary(completedToday = 1),
                "1 of 2 sessions done today",
                deviceState = ProgressorConnectionState.Connected,
                battery = 0.08,
            )
        }
    }
}

@Preview(name = "Card · done for the day", showBackground = true, widthDp = 380)
@Composable
private fun RoutineCardDonePreview() {
    GetAGripTheme {
        Box(Modifier.padding(20.dp)) {
            RoutineCard(
                previewSummary(completedToday = 2),
                tr("Both sessions done today"),
                deviceState = ProgressorConnectionState.Connected,
            )
        }
    }
}

@Preview(name = "Card · climbed today", showBackground = true, widthDp = 380)
@Composable
private fun RoutineCardClimbedPreview() {
    GetAGripTheme {
        Box(Modifier.padding(20.dp)) {
            RoutineCard(
                previewSummary(climb = SessionKind.climbLimit, peak = 0.85),
                "Limit session at the gym today",
                deviceState = ProgressorConnectionState.Connected,
            )
        }
    }
}

@Preview(name = "Card · disconnected", showBackground = true, widthDp = 380)
@Composable
private fun RoutineCardDisconnectedPreview() {
    GetAGripTheme {
        Box(Modifier.padding(20.dp)) {
            RoutineCard(
                previewSummary(),
                "No sessions done today, 2 planned",
                deviceState = ProgressorConnectionState.Idle,
            )
        }
    }
}
