// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.Fmt
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.l10n.trQuantity
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// One past session, drawn as a MUSIC-APP ROW: artwork, two lines of text, a trailing
/// accessory (Nuri, 2026-08-08 — *"make the list of old sessions look just like the Apple
/// Music song list"*).
///
/// The anatomy is the point. Artwork identifies the item before you read anything, the
/// title says what it was, the subtitle carries the detail, and the date sits right where
/// Music puts its accessories. What it replaced — a card per row, stats on their own line,
/// the date floated top-right — made ten sessions read as ten documents and fitted four on
/// a screen.
///
/// A dumb leaf: it observes nothing and decodes nothing. Both facts it cannot derive
/// itself are passed IN, and the reason is the same for each — see `name` and
/// `leadingGrip`.
@Composable
fun SessionRow(
    log: WorkoutLogEntity,
    /// The routine's LIVE name while it exists, the log's frozen copy once it doesn't.
    /// Resolved by the caller so this row has nothing to observe.
    name: String,
    modifier: Modifier = Modifier,
    /// The session's first grip, for the artwork tile. From the caller's rep cache rather
    /// than decoded here: `resultsData` is write-once and already decoded once per log
    /// ever, and a row that re-parsed JSON would make the list slower every week Nuri
    /// trains.
    leadingGrip: GripSpec? = null,
) {
    val palette = LocalGripPalette.current
    // **CLAMPED**, the consistency strip's own fix: uncapped, this tile grows with every
    // row in the list and squeezes the routine name — the row's own title — hardest.
    //
    // TRANSLATION NOTE: iOS says `@ScaledMetric(relativeTo: .subheadline) 44`. Compose has
    // no environment-scaled Dp, so the font scale is read and applied by hand; the clamp is
    // the same 56 either way.
    val fontScale = LocalDensity.current.fontScale
    val artwork = minOf(ARTWORK_BASE * fontScale, ARTWORK_MAX)

    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
            // The row is ONE thing to TalkBack; four separate stops to read one session is
            // the classic list-accessibility failure.
            .clearAndSetSemantics { contentDescription = spokenSession(log, name) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkTile(log, leadingGrip, artwork)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title(log, name),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle(log),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Music's trailing accessory slot, carrying the two things you scan a log for.
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                shortDate(log),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkTertiary,
                maxLines = 1,
            )
            val grade = log.grade
            if (grade != null) {
                Text(
                    grade.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .border(1.dp, palette.inkTertiary.copy(alpha = 0.35f), CircleShape)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/// Music's artwork is a rounded square you read before the words. Ours is the app's own
/// iconography — the grip you pulled, or a climber for a gym session.
@Composable
private fun ArtworkTile(log: WorkoutLogEntity, leadingGrip: GripSpec?, side: androidx.compose.ui.unit.Dp) {
    val palette = LocalGripPalette.current
    Box(
        Modifier
            .size(side)
            .background(palette.inkTertiary.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            log.kind.isClimb -> Icon(
                Icons.Outlined.Terrain, contentDescription = null,
                tint = palette.graphite, modifier = Modifier.size(20.dp),
            )
            // The Maxes tab's own symbol — kilograms on a scale.
            log.kind == SessionKind.benchmark -> Icon(
                Icons.Outlined.MonitorWeight, contentDescription = null,
                tint = palette.graphite, modifier = Modifier.size(20.dp),
            )
            log.kind == SessionKind.hangManual -> Icon(
                Icons.Outlined.FitnessCenter, contentDescription = null,
                tint = palette.graphite, modifier = Modifier.size(20.dp),
            )
            leadingGrip != null ->
                FingerGlyph(leadingGrip.fingers, position = leadingGrip.position, dot = 5.dp, gap = 2.5.dp)
            // A session whose reps did not decode still gets a tile rather than a hole —
            // the row's shape must not depend on a blob surviving.
            else -> Icon(
                Icons.Outlined.PanTool, contentDescription = null,
                tint = palette.inkTertiary, modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val ARTWORK_BASE = 44.dp
private val ARTWORK_MAX = 56.dp

private fun title(log: WorkoutLogEntity, name: String): String =
    if (log.kind.isLoggedByHand) log.kind.displayName else name

/// Music's second line is the artist; ours is what the session cost. Compact, and
/// unit-labelled only where a bare number would be ambiguous.
private fun subtitle(log: WorkoutLogEntity): String {
    if (log.kind.isLoggedByHand) {
        val parts = handLoggedDetails(log)
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return if (log.kind.isClimb) L10n.tr("At the climbing gym") else L10n.tr("Away from the gauge")
    }
    if (log.kind == SessionKind.benchmark) return L10n.tr("Tested your maxes")
    val held = PlanMath.clockText(Math.round(log.totalHeldSeconds).toInt())
    if (log.peakKg <= 0) return L10n.tr("%d/%d pulls · %s", log.completedReps, log.plannedReps, held)
    return L10n.tr(
        "%d/%d pulls · %s · %s kg",
        log.completedReps,
        log.plannedReps,
        held,
        Fmt.fixed(log.peakKg, 1),
    )
}

private fun handLoggedDetails(log: WorkoutLogEntity): List<String> {
    val parts = mutableListOf<String>()
    log.sessionMinutes?.let { parts.add(durationText(it)) }
    log.grade?.let { parts.add(it.displayName) }
    log.fingerStrain?.let {
        parts.add(L10n.tr("Fingers %s", it.displayName.lowercase(Locale.getDefault())))
    }
    return parts
}

private fun durationText(minutes: Int): String = when {
    minutes % 60 == 0 -> L10n.tr("%dh", minutes / 60)
    minutes > 60 -> L10n.tr("%dh%d", minutes / 60, minutes % 60)
    else -> L10n.tr("%dm", minutes)
}

private val SHORT_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

private fun shortDate(log: WorkoutLogEntity): String =
    SHORT_DATE.format(log.historyDate())

private val LONG_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

private fun longDate(log: WorkoutLogEntity): String =
    LONG_DATE.format(log.historyDate())

/// The whole row as one sentence. Not private, so a swipe action or a summary can name a
/// session with the same words the row itself uses — and named `spokenSession` rather than
/// `spoken` because it is a top-level function in a shared package.
fun spokenSession(log: WorkoutLogEntity, name: String): String {
    val when_ = longDate(log)
    val parts = mutableListOf<String>()
    if (log.kind.isLoggedByHand) {
        parts.add(log.kind.displayName)
        parts.add(when_)
        log.sessionMinutes?.let { parts.add(L10n.tr("for %s", spokenDuration(it))) }
        log.grade?.let { parts.add(L10n.tr("felt %s", it.displayName)) }
        log.fingerStrain?.let {
            parts.add(L10n.tr("your fingers felt %s", it.displayName.lowercase(Locale.getDefault())))
        }
        if (parts.size == 2) {
            parts.add(if (log.kind.isClimb) L10n.tr("at the climbing gym") else L10n.tr("away from the gauge"))
        }
        return parts.joinToString(", ")
    }
    if (log.kind == SessionKind.benchmark) {
        return listOf(log.kind.displayName, when_, L10n.tr("tested your maxes")).joinToString(", ")
    }
    parts.add(name)
    parts.add(when_)
    parts.add(L10n.tr("%d of %d pulls completed", log.completedReps, log.plannedReps))
    parts.add(trQuantity("%d seconds under tension", Math.round(log.totalHeldSeconds).toInt()))
    if (log.peakKg > 0) parts.add(L10n.tr("peak %s kilograms", Fmt.fixed(log.peakKg, 1)))
    log.grade?.let { parts.add(L10n.tr("felt %s", it.displayName)) }
    return parts.joinToString(", ")
}

private fun spokenDuration(minutes: Int): String = when {
    minutes % 60 == 0 ->
        L10n.tr("%d %s", minutes / 60, L10n.tr(if (minutes == 60) "hour" else "hours"))
    minutes > 60 -> L10n.tr("%d hours and %d minutes", minutes / 60, minutes % 60)
    else -> trQuantity("%d minutes", minutes)
}

@Preview(name = "SessionRow", showBackground = true, widthDp = 400)
@Composable
private fun SessionRowPreview() {
    val day = DayStamp.today()
    val hang = WorkoutLogEntity.from(
        plan = SessionPlan(), templateID = null, templateName = tr("Daily no-hangs"),
        sessionsPerDayTarget = 2, reps = emptyList(),
        startedAt = day.startOfDay().toInstant(), finishedAt = day.startOfDay().toInstant(),
        day = day,
    ).copy(completedReps = 34, plannedReps = 36, totalHeldSeconds = 214.0, peakKg = 12.4)
    val climb = WorkoutLogEntity.logged(
        kind = SessionKind.climbLimit, day = day - 1, at = (day - 1).startOfDay().toInstant(),
        sessionsPerDayTarget = 2, minutes = 120,
    )
    GetAGripTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionRow(hang, name = tr("Daily no-hangs"), leadingGrip = GripSpec())
            SessionRow(climb, name = tr("Limit climbing"))
        }
    }
}
