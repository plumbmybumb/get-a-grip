// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import run.nuri.getagrip.R
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerStrain
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import run.nuri.getagrip.ui.l10n.LocalizedPattern
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.runner.PeakFigure
import run.nuri.getagrip.ui.runner.RoutinePills
import run.nuri.getagrip.ui.runner.SessionProgressModel
import run.nuri.getagrip.ui.runner.SessionSummaryHeader
import run.nuri.getagrip.ui.runner.SessionSummaryStats
import run.nuri.getagrip.ui.runner.SetBreakdown
import run.nuri.getagrip.ui.runner.handLabel
import run.nuri.getagrip.ui.runner.kgText
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth
import run.nuri.getagrip.ui.units.WeightUnits

// MARK: - What the detail shows, frozen at the tap

/// One past workout as a VALUE (iOS `SessionDetailContent`): everything the screen reads,
/// copied off the frozen log — the reps through the feed's once-ever cache, the denormalized
/// columns, and the max records the save wrote. Nothing else, and nothing invented.
data class SessionDetailContent(
    val id: UUID,
    /// The routine's live name, or the name frozen into the log once it is gone.
    val name: String,
    val kind: SessionKind,
    val day: DayStamp,
    val startedAt: Instant,
    val minutes: Int?,
    val reps: List<RepSummary>,
    /// Pull counts per set from the frozen PLAN, so pulls never reached still draw as track.
    val setSizes: List<Int>,
    val plannedReps: Int,
    val completedReps: Int,
    val heldSeconds: Int,
    val peakKg: Double,
    val avgKg: Double,
    val grade: RPE?,
    val fingerStrain: FingerStrain?,
    val notes: String,
    val savedMaxes: List<SavedMax>,
) {
    /// A max this session recorded (or, on a benchmark day, that the day measured).
    data class SavedMax(val grip: GripSpec, val side: Side, val kg: Double, val seconds: Int) {
        val id: String get() = "${grip.key}|${side.rawValue}|$seconds|$kg"
    }

    /// The facts about a stored max the matching rule reads — values, so it runs in a test.
    data class RecordFact(
        val grip: GripSpec,
        val side: Side,
        val kg: Double,
        val seconds: Int,
        val measured: Boolean,
        val recordedAt: Instant,
    ) {
        companion object {
            fun of(record: MaxRecordEntity) = RecordFact(record.grip, record.side, record.kg,
                record.durationSeconds, record.source == MaxSource.measured, record.recordedAt)
        }
    }

    val isRunnerHang: Boolean get() = kind == SessionKind.hang

    /// INFERRED exactly as the analysis export infers it: a hang whose pulls read no load ran
    /// on the timer alone, and "0.0 kg" would state a measurement nobody took.
    val measured: Boolean get() = peakKg > 0

    /// The runner's pills, still, with no current one — nothing is being pulled.
    val progress: SessionProgressModel?
        get() = if (!isRunnerHang || reps.isEmpty()) null else SessionProgressModel(
            setSizes = setSizes.ifEmpty { setSizes(reps.map { it.setIndex }) },
            finished = reps.map { it.outcome == RepOutcome.completed },
            current = null,
        )

    companion object {
        /// How long after finishing a summary can still be saved and count as this session's:
        /// a day covers a summary left open and the unsaved-session recovery on next launch.
        const val SAVE_WINDOW_SECONDS = 24L * 3600

        fun of(
            log: WorkoutLogEntity,
            name: String,
            reps: List<RepSummary>,
            records: List<RecordFact>,
            zone: ZoneId = ZoneId.systemDefault(),
        ) = SessionDetailContent(
            id = log.id,
            name = name,
            kind = log.kind,
            day = log.day,
            startedAt = log.startedAt,
            minutes = log.sessionMinutes,
            reps = reps,
            setSizes = if (log.kind == SessionKind.hang) {
                log.plan?.let { plan -> setSizes(PlanMath.sequence(plan).map { it.setIndex }) } ?: emptyList()
            } else emptyList(),
            plannedReps = log.plannedReps,
            completedReps = log.completedReps,
            heldSeconds = Math.round(log.totalHeldSeconds).toInt(),
            peakKg = log.peakKg,
            avgKg = log.avgKg,
            grade = log.grade,
            fingerStrain = log.fingerStrain,
            notes = log.notes.trim(),
            savedMaxes = savedMaxes(log.kind, log.day, log.finishedAt, reps, records, zone),
        )

        /// Which max records belong to this session. No column links a max to the session
        /// that produced it, so the link is RECOVERED from facts the save froze (iOS twin):
        /// - a hangboard session's saved max is a MEASURED peak copied bit for bit from one
        ///   completed pull on that grip and hand, stamped when the summary was saved;
        /// - a benchmark day lists the measured maxes of that training day;
        /// - climbs and hand-logged hangs record no maxes.
        fun savedMaxes(
            kind: SessionKind,
            day: DayStamp,
            finishedAt: Instant,
            reps: List<RepSummary>,
            records: List<RecordFact>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<SavedMax> {
            val matched = when (kind) {
                SessionKind.hang -> {
                    val from = finishedAt.minusSeconds(60)
                    val to = finishedAt.plusSeconds(SAVE_WINDOW_SECONDS)
                    records.filter { record ->
                        record.measured && record.seconds == 0 &&
                            !record.recordedAt.isBefore(from) && !record.recordedAt.isAfter(to) &&
                            reps.any { rep ->
                                rep.outcome == RepOutcome.completed && rep.side == record.side &&
                                    rep.grip.key == record.grip.key && rep.peakKg == record.kg
                            }
                    }
                }
                SessionKind.benchmark -> records.filter {
                    it.measured && DayStamp.trainingDayOf(it.recordedAt, zone) == day
                }
                else -> emptyList()
            }
            return matched.map { SavedMax(it.grip, it.side, it.kg, it.seconds) }
                .distinctBy { it.id }
                .sortedWith(compareByDescending<SavedMax> { it.kg }.thenBy { it.id })
        }

        /// Pull counts per set, in order, from consecutive set indices.
        fun setSizes(setIndices: List<Int>): List<Int> {
            val sizes = mutableListOf<Int>()
            var last: Int? = null
            for (index in setIndices) {
                if (index == last) sizes[sizes.size - 1] += 1 else sizes += 1
                last = index
            }
            return sizes
        }
    }
}

// MARK: - The screen

/// A past workout, read back: the finish summary's own pieces with nothing left to decide.
///
/// TRANSLATION NOTE: iOS pushes this onto History's `NavigationStack`; here it is a route in
/// History's own NavHost, so the tab bar stays and predictive back pops it. Export and Delete
/// sit on the top bar; Delete goes through the store's `deleteSession` and History's Undo
/// snackbar, never a dialog. A climb or a hand-logged session shows what was logged — the
/// kind, the day, the duration, the effort — and nothing invented.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    content: SessionDetailContent,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGripPalette.current
    // Open: the per-set account is what someone opening an old session came for.
    var setsExpanded by rememberSaveable { mutableStateOf(true) }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        // `RootTabView`'s Scaffold already inset this subtree; a second inset would pad twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                actions = {
                    IconButton(onClick = onExport, modifier = Modifier.testTag("sessionDetail.export")) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_glyph_export),
                            contentDescription = tr("Export workout"), tint = palette.graphite)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.testTag("sessionDetail.delete")) {
                        Icon(Icons.Outlined.Delete, contentDescription = tr("Delete workout"), tint = palette.alarm)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = palette.card,
                    navigationIconContentColor = palette.inkPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .readablePageWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding)
                .padding(top = 4.dp, bottom = Metrics.spacing + LocalFloatingTabBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Metrics.spacing),
        ) {
            SessionSummaryHeader(caption = caption(content), title = dateTitle(content.day))
            if (content.isRunnerHang) {
                SessionSummaryStats(
                    completed = content.completedReps,
                    planned = content.plannedReps,
                    heldSeconds = content.heldSeconds,
                    peakKg = if (content.measured) content.peakKg else null,
                )
                content.progress?.let { progress ->
                    InstrumentSurface(shape = RoundedCornerShape(Metrics.radiusInner), color = palette.card,
                        modifier = Modifier.fillMaxWidth()) {
                        RoutinePills(progress, currentTint = Color.Transparent,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp), thickness = 8.dp)
                    }
                }
            }
            Facts(content)
            if (content.savedMaxes.isNotEmpty()) SavedMaxes(content)
            if (content.reps.isNotEmpty()) {
                SetBreakdown(content.reps, palette, setsExpanded, showsReps = true,
                    measured = content.measured) { setsExpanded = !setsExpanded }
            }
        }
    }
}

/// The routine for a session the runner timed; the kind for anything logged by hand, whose
/// frozen name was the kind's name in whatever language it was logged in.
private fun caption(content: SessionDetailContent): String =
    if (content.kind.isLoggedByHand || content.kind == SessionKind.benchmark) content.kind.displayName
    else content.name

private val LONG_DATE = LocalizedPattern("EEEE d MMMM")
private val LONG_DATE_YEAR = LocalizedPattern("EEEE d MMMM y")

/// The TRAINING day — the one the grid and the row count by — with the year only once it is
/// not this one.
private fun dateTitle(day: DayStamp): String {
    val date = day.localDate()
    val title = (if (date.year == LocalDate.now().year) LONG_DATE else LONG_DATE_YEAR).format(date)
    return title.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

private data class Fact(val label: String, val value: String, val spoken: String = value)

private fun durationText(minutes: Int, width: MeasureFormat.FormatWidth): String {
    val format = MeasureFormat.getInstance(Locale.getDefault(), width)
    val hours = minutes / 60
    val rest = minutes % 60
    val parts = buildList {
        if (hours > 0) add(Measure(hours, MeasureUnit.HOUR))
        if (rest > 0 || hours == 0) add(Measure(rest, MeasureUnit.MINUTE))
    }
    return format.formatMeasures(*parts.toTypedArray())
}

private fun facts(content: SessionDetailContent): List<Fact> = buildList {
    if (content.isRunnerHang) {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
            .format(content.startedAt.atZone(ZoneId.systemDefault()))
        add(Fact(L10n.tr("Started"), time))
    }
    content.minutes?.let {
        add(Fact(L10n.tr("Duration"), durationText(it, MeasureFormat.FormatWidth.SHORT),
            durationText(it, MeasureFormat.FormatWidth.WIDE)))
    }
    if (content.isRunnerHang && content.measured && content.avgKg > 0) {
        add(Fact(L10n.tr("Average"), "${kgText(content.avgKg)} ${WeightUnits.symbol}",
            "${kgText(content.avgKg)} ${WeightUnits.spoken}"))
    }
    content.grade?.let { add(Fact(L10n.tr("Effort"), it.displayName)) }
    content.fingerStrain?.let { add(Fact(L10n.tr("Fingers"), it.displayName)) }
}

/// What the log says beyond the numbers. A row with nothing logged says where it happened,
/// in the row's own words — never a blank card.
@Composable
private fun Facts(content: SessionDetailContent) {
    val palette = LocalGripPalette.current
    val rows = facts(content)
    val stacked = LocalDensity.current.fontScale >= 1.6f
    InstrumentSurface(shape = RoundedCornerShape(Metrics.radiusInner), color = palette.card,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (content.isRunnerHang && !content.measured) {
                Text(tr("Timing only"), style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, color = palette.inkSecondary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            rows.forEachIndexed { index, fact ->
                if (index > 0) HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.15f))
                FactRow(fact, stacked)
            }
            if (rows.isEmpty() && content.notes.isEmpty()) {
                Text(
                    when {
                        content.kind == SessionKind.benchmark -> tr("Tested your maxes")
                        content.kind.isClimb -> tr("At the climbing gym")
                        else -> tr("Away from the gauge")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
            if (content.notes.isNotEmpty()) {
                if (rows.isNotEmpty()) HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.15f))
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Note"), style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
                    Text(content.notes, style = MaterialTheme.typography.bodyLarge, color = palette.inkPrimary)
                }
            }
        }
    }
}

/// Label and value on one line; at the largest text the value moves under its label rather
/// than squeezing a number. One TalkBack stop, label then value.
@Composable
private fun FactRow(fact: Fact, stacked: Boolean) {
    val palette = LocalGripPalette.current
    val semantics = Modifier.clearAndSetSemantics { contentDescription = "${fact.label}, ${fact.spoken}" }
    if (stacked) {
        Column(semantics.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(fact.label, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
            Text(fact.value, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
        }
    } else {
        Row(semantics.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(fact.label, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
            Spacer(Modifier.weight(1f))
            Text(fact.value, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold, color = palette.inkPrimary, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun SavedMaxes(content: SessionDetailContent) {
    val palette = LocalGripPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsLabel(if (content.kind == SessionKind.benchmark) tr("Maxes tested") else tr("Maxes saved"))
        InstrumentSurface(shape = RoundedCornerShape(Metrics.radiusInner), color = palette.card,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                content.savedMaxes.forEachIndexed { index, saved ->
                    if (index > 0) HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.15f))
                    val spoken = WeightUnits.tr("%s · %s · %s kg", handLabel(saved.side),
                        saved.grip.shortName, kgText(saved.kg)) +
                        if (saved.seconds > 0) ", " + tr("%d s", saved.seconds) else ""
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            .clearAndSetSemantics { contentDescription = spoken },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FingerGlyph(saved.grip.fingers, position = saved.grip.position,
                            dot = 8.dp, gap = 3.dp, tint = palette.armed)
                        PeakFigure(saved.grip, saved.side, saved.kg, previous = null,
                            modifier = Modifier.weight(1f), seconds = saved.seconds)
                    }
                }
            }
        }
    }
}
