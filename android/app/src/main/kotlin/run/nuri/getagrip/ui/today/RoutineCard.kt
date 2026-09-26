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
import androidx.compose.ui.platform.testTag
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

/// The routine you are committing to, as one card in three ZONES: identity + today's status,
/// the plan as one line (which IS the edit surface), and the one action. Spacing does the
/// grouping — 6 dp inside a zone, 16 dp between — because at a uniform 14 dp every row read
/// as parts "slapped in" (Nuri).
///
/// **No grip ladder** (removed 2026-08-17, Nuri's call): per-set finger diagrams were clutter
/// on a dashboard. The card owes which routine, how much is done, what it costs and the way
/// in; the editor and runner show the fingers.
///
/// Inputs are VALUES (`RoutineSummary`), so the card previews without a store and derived
/// numbers are computed once in `TemplateStore`, not per scroll frame. `completionText`
/// comes from there too, so the spoken sentence and "1 of 2" cannot drift apart.
///
/// TRANSLATION NOTE: iOS splits `SolidPrimaryButton` from the glass one because the
/// context-menu lift ghosts Liquid Glass. Android has neither, so the house buttons suffice.
@Composable
fun RoutineCard(
    summary: RoutineSummary,
    /// `TemplateStore.completionText(_)` — a whole sentence, which is what TalkBack reads
    /// in place of the numeral fragment.
    completionText: String,
    modifier: Modifier = Modifier,
    /// Marks the deck's HOME card (last reminder to call, else mid-ritual, else primary), so
    /// swiping away and back still answers "which one now". Never set with a single routine.
    isUpNext: Boolean = false,
    deviceState: ProgressorConnectionState = ProgressorConnectionState.Idle,
    battery: Double? = null,
    onStart: () -> Unit = {},
    /// The same session with no gauge — timers, count-in and hand prompts only.
    onStartTimerOnly: () -> Unit = {},
    onOverview: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onNew: () -> Unit = {},
    onMakePrimary: () -> Unit = {},
    /// The routine as a QR code and link; Today freezes the request at the tap so a swipe
    /// behind the sheet cannot change the code.
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
            // Graphite, not bleu (bleu is live force). 1.5 dp at half strength sits a clear step above
            // the ghost card's hairline — "could exist" versus "is the one" — and far below an alarm.
            // A STROKE survives greyscale; TalkBack hears it on the title.
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
            // The long-press mirror of the ⋯ menu: free discoverability, no hit-target cost.
            //
            // TRANSLATION NOTE: not `combinedClickable`, which REQUIRES an `onClick`; this card has no
            // whole-surface tap, and a no-op click would publish a phantom "double-tap to activate" to
            // TalkBack. The accessible door is the labelled 44 dp ⋯ button.
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { menuOpen = true })
            }
            // A raw gesture is invisible to TalkBack, so the long press is also a semantic action,
            // mirroring the ⋯ button.
            .semantics {
                onLongClick(label = L10n.tr("Routine options")) { menuOpen = true; true }
            },
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Zone 1 — identity and status. The dots are a SUBTITLE locked to the name; floating
            // between title and plan they read as loose parts.
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
            PlanRow(summary, onOverview)
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
        // The routine's SIGNATURE grip leads the name. Without the ladder the card went
        // "bland and text heavy", and an identical badge on every card failed the same day
        // (Nuri, 2026-08-17). One derived mark per card: it differs exactly when routines do.
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
                // The border's spoken counterpart: a stroke is invisible to TalkBack.
                .semantics {
                    if (isUpNext) stateDescription = L10n.tr("Up next")
                },
        )

        Spacer(Modifier.width(8.dp))

        // 44 dp target around a bare glyph; the menu ANCHORS here, so long press and tap raise it
        // in the same place.
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
            // ONE element: dots and fragment are two readings of one fact, not two swipes.
            .clearAndSetSemantics { contentDescription = completionText },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionDots(summary)

        // A CLIMB DAY says what happened. "0 of 2 today" beside a checkmark contradicts itself and
        // is the "you didn't train" reading this feature exists to stop.
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
                // Same anatomy as the climb line. Never "at the gym": a testing day (a max or critical force) is its own kind of day.
                Text(
                    tr("Testing day"),
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
                // No target, no tally, NO GUILT: never "not done yet today" for a whenever routine.
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
                    // NO ROLL: the Compose digit roll read "really bad and super laggy" on the Realme.
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
                // "1 of 1" is silly; the one-a-day routine says what the number stood for.
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
            // Graphite, never green: green is not chrome here, and filled ink already means "happened".
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = palette.graphite,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/// "at the gym", plus any hang rounds anyway — said second, as the extra on a day that
/// already counted.
private fun hangSuffix(completed: Int): String = when (completed) {
    0 -> L10n.tr("at the gym")
    1 -> L10n.tr("at the gym · 1 hang")
    else -> L10n.tr("at the gym · %d hangs", completed)
}

/// **CIRCLES ARE SESSIONS. BARS ARE FINGERS.** 16 dp: at 9 dp small round marks read as
/// decoration, not a COUNT. Spacing scales with the dot, since touching circles read as one.
@Composable
private fun SessionDots(summary: RoutineSummary) {
    val palette = LocalGripPalette.current
    // Scaled off body text (iOS `@ScaledMetric(relativeTo: .body)`) and CAPPED like
    // `ConsistencyCard`'s strip: a repeated cell multiplies and widens the card.
    val scale = LocalDensity.current.fontScale.coerceAtMost(1.4f)
    val dot = 16.dp * scale

    Row(horizontalArrangement = Arrangement.spacedBy(dot * 0.45f)) {
        when {
            // ONE notched dot on a climb day: the rings count hang sessions owed, and none are. A
            // benchmark day gets one plain dot — the notch is a climbing mark.
            summary.climbedToday != null -> Box(
                Modifier.size(dot).clip(CircleShape).background(palette.graphite).climbNotch(true),
            )
            summary.benchmarkedToday -> Box(
                Modifier.size(dot).clip(CircleShape).background(palette.graphite),
            )
            summary.isOnDemand -> {
                // No slots owed, so no empty rings; one dot appears once a session happened.
                if (summary.completedToday > 0) {
                    Box(Modifier.size(dot).clip(CircleShape).background(palette.graphite))
                }
            }
            else -> repeat(maxOf(1, summary.sessionsPerDay)) { index ->
                if (index < summary.completedToday) {
                    Box(Modifier.size(dot).clip(CircleShape).background(palette.graphite))
                } else {
                    // 1.5 dp to match the filled dot's optical weight. 0.85 opacity, measured on the dark
                    // screenshot: 0.5 hit 2.1:1 and 0.75 only 2.98:1, because a thin ring is mostly
                    // antialiased edge. A mark that MEANS "not done yet" must clear 3:1.
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
/// editor: the door to the plan is the plan itself.
///
/// **No platter.** The inset well framed a DIAGRAM; around a single line it makes a sentence
/// look like a text field. The chevron and press feedback say this row is a door.
@Composable
private fun PlanRow(summary: RoutineSummary, onOverview: () -> Unit) {
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
                role = Role.Button,
                onClick = onOverview,
            )
            .pressFeedback(interaction, scales = false)
            .testTag("today.routineOverview")
            .semantics {
                contentDescription = L10n.tr("Routine overview")
                // The card no longer draws the grips, so it does not speak them. The intensity suffix
                // is here because the rung's colour is invisible to TalkBack and greyscale.
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
                // At accessibility sizes this is the ONLY statement of the plan; truncating "≈21 min"
                // loses the fact the row exists for. Two lines only appear on a screen that scrolls.
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

/// The plan's numbers with a symbol each. The VALUE carries the ink; the symbol is decorative
/// (tertiary) since each sits beside the word naming it, so the 3:1 floor does not apply.
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
        // `edgeLine`, not `sharedEdgeMM`: a mixed ladder states its span ("20–10 mm"); dropping it
        // read as the app not knowing its own routine.
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

/// **Which of three forms the plan row draws — full glyph row, COMPACT glyph row, or the
/// sentence — as a pure function of width and font scale.**
///
/// iOS uses `ViewThatFits(in: .horizontal)`. Compose has no such container (`SubcomposeLayout`
/// costs an extra measure pass), so the choice is arithmetic, which a JVM test can pin (as
/// `DialLadder` does).
///
/// The estimate only has to fall to the next form before the last stat truncates. On iOS the
/// edge SPAN plus four symbols truncated its own stat ("20–10…"), and the first fix skipped
/// compact straight to the sentence (Nuri: "the little icons being gone is sad").
internal object PlanRowFit {

    enum class Form { Full, Compact, Sentence }

    /// The accessibility rung, the same 1.3 `Chips.kt` reads as `.accessibility1`: pictograms
    /// cannot wrap, and big-text readers are served by words.
    const val SENTENCE_FONT_SCALE = 1.3f

    /// ~One `bodySmall` character: 12 sp at ~0.55 advance. Over-estimating is safe (compact a
    /// few points early); under-estimating truncates.
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
                modifier = Modifier.fillMaxWidth(),
                onClick = onStart,
            )
        } else {
            // Graphite, not bleu (bleu is live force). ALWAYS enabled: the runner's first phase is
            // connect-and-tare, so tapping while disconnected is the common path, and disabling the
            // ritual's one button turns it into a chore.
            PrimaryButton(
                title = startTitle(summary, deviceState),
                icon = Icons.Filled.PlayArrow,
                onClick = onStart,
            )
        }

        connectionNote(deviceState, palette.inkTertiary, palette.alarm)?.let { NoteRow(it) }
        batteryNote(battery, palette.armed)?.let { NoteRow(it) }

        // **Only while no gauge is connected** — flat battery, left at home, Bluetooth off (Nuri,
        // 2026-08-09). Beside a connected Progressor it would offer to throw the measurement away;
        // it stays in the ⋯ menu.
        if (!deviceState.isConnected) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    // 44 tall for the target, pulled tight to the note above: the 10 dp stack gap was pure
                    // spend on a page that has to fit.
                    .padding(top = 0.dp)
                    .clip(RoundedCornerShape(Metrics.radiusInner))
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onStartTimerOnly,
                    )
                    .pressFeedback(interaction, scales = false)
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
            // Always compiled in: without hardware, on an emulator or in review, this is the only way to see it work.
            is ProgressorConnectionState.Unsupported ->
                SecondaryButton(title = tr("Try demo mode"), modifier = Modifier.fillMaxWidth(), onClick = onDemo)
            else -> Unit
        }
    }
}

/// No ordinal once disconnected: "Connect and start second session" is too long, and the line
/// above says which session this is.
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
        L10n.tr("Gauge battery at %d%% — charge it soon.", run.nuri.getagrip.ui.components.BatteryDisplay.percentage(battery)),
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
