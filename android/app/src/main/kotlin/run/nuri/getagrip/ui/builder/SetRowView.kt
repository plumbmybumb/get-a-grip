// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import run.nuri.getagrip.ui.units.WeightUnits

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlin.math.roundToInt
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.l10n.trQuantity
import run.nuri.getagrip.ui.components.FingerGlyph
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.ValueControl
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// One set on the Sets page — closed it is TWO LINES (the grip, then pulls · load · any
/// custom timing), open it is the whole set.
///
/// At most one row is open (the builder owns that), so one dense control cluster exists at a
/// time. **Two levels of disclosure, never three**: the set row, then the target row inside
/// it — which is why the grip picker is a panel.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetRowView(
    /// This row's set, as a value. It writes its edits back through one callback.
    set: SetPlan,
    /// **Only the routine-level fields the row resolves against** (inherited hold, rest and band,
    /// ×2, lead-in). Given the WHOLE plan, every row redrew for a letter typed into the name.
    context: SetRowContext,
    isExpanded: Boolean,
    /// Every max on file, by grip and hand, as a VALUE so the row never touches a store.
    maxes: MaxTable,
    /// Folded once by the builder; per row, both summaries rescanned every set.
    percentBandsVary: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    /// Asks the BUILDER to open the grip panel: nothing in a scrolling row reaches the top of the screen.
    onEditGrip: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onSetChange: (SetPlan) -> Unit,
) {
    val palette = LocalGripPalette.current
    val reduceMotion = rememberReduceMotion()
    val sessionPlan = context.plan
    var menuOpen by remember { mutableStateOf(false) }
    val stacked = LocalDensity.current.fontScale >= 1.5f

    val repsText = if (sessionPlan.handMode.sideCount > 1) {
        L10n.tr("%d per side", set.repsPerSide)
    } else {
        // One-sided modes drop "per side" rather than halving a number never doubled.
        L10n.tr("%d %s", set.repsPerSide, L10n.tr(if (set.repsPerSide == 1) "pull" else "pulls"))
    }
    val tensionText = PlanMath.tensionSecondsPerSide(set, sessionPlan)?.let {
        L10n.tr("%s under tension per side", PlanMath.clockText(it))
    } ?: run {
        val reps = PlanMath.repCount(set, sessionPlan.handMode)
        L10n.tr("%s under tension", PlanMath.clockText(reps * PlanMath.hold(set, sessionPlan)))
    }
    // The visual line is terse; the spoken one stays the full sentence, written once as prose.
    val spoken = tr(
        "%s. %s, %s%s. %s.",
        set.grip.spoken,
        repsText,
        tensionText,
        spokenOverride(set, context, percentBandsVary),
        PlanMath.durationText(PlanMath.setSeconds(set, sessionPlan)),
    )
    val disclosure = tr(if (isExpanded) "Expanded" else "Collapsed")
    val detail = compactDetail(
        repsText = repsText,
        load = loadText(set, context),
        inheritsLoad = !set.hasTarget && !set.hasPercentTarget,
        timing = timingOverrideText(set, context),
        secondary = palette.inkSecondary,
        tertiary = palette.inkTertiary,
        primary = palette.inkPrimary,
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
    ) {
        Column(Modifier.fillMaxWidth()) {
            val interactionSource = remember { MutableInteractionSource() }
            Box {
                val headerModifier = Modifier
                    .fillMaxWidth()
                    // The long-press menu lives ON THE HEADER: on the whole row, a hold anywhere in the
                    // EXPANDED editor would grab touches from the controls being dragged.
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onTap,
                        onLongClick = { menuOpen = true },
                    )
                    .pressFeedback(interactionSource, scales = false)
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        // NOT the catalog's "Open": that is the open-hand GRIP POSITION ("Tendue").
                        stateDescription = disclosure
                        role = Role.Button
                    }
                val chevron: @Composable () -> Unit = {
                    Icon(
                        if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = palette.inkTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                val glyph: @Composable () -> Unit = {
                    FingerGlyph(fingers = set.grip.fingers, position = set.grip.position, dot = 6.dp, gap = 3.dp)
                }
                val gripLine: @Composable () -> Unit = {
                    Text(
                        set.grip.line,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                        maxLines = if (stacked) Int.MAX_VALUE else 2,
                    )
                }
                val detailLine: @Composable () -> Unit = {
                    Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                }
                if (stacked) {
                    // Large text: glyph and chevron, then the words, each free to wrap.
                    Column(headerModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            glyph()
                            Box(Modifier.weight(1f))
                            chevron()
                        }
                        gripLine()
                        detailLine()
                    }
                } else {
                    Row(
                        headerModifier
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        glyph()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            gripLine()
                            detailLine()
                        }
                        chevron()
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Move up")) },
                        enabled = canMoveUp,
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null) },
                        onClick = { menuOpen = false; onMoveUp() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Move down")) },
                        enabled = canMoveDown,
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) },
                        onClick = { menuOpen = false; onMoveDown() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Duplicate")) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Remove"), color = palette.alarm) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = palette.alarm) },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }

            // `Motion.state`, not Compose's unguarded 400 ms default — see `TargetBandRow`.
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(Motion.state(reduceMotion)) + fadeIn(Motion.state(reduceMotion)),
                exit = shrinkVertically(Motion.state(reduceMotion)) + fadeOut(Motion.state(reduceMotion)),
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.22f))

                    // ONE 60 dp control where edge slider + finger pad + position chips stacked to ~400; all
                    // three live in the grip panel.
                    GripToken(set.grip, onEdit = onEditGrip)

                    // A STEPPER: a small integer you want EXACTLY (see `ValueControl.Stepper`). Repeats while held.
                    IntValueRow(
                        title = tr("Pulls per side"),
                        value = set.repsPerSide,
                        range = 1..40,
                        limit = 1..SetPlan.repsRange.last,
                        control = ValueControl.Stepper,
                    ) { onSetChange(set.copy(repsPerSide = it)) }

                    CustomTiming(set, sessionPlan, onSetChange)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TargetBandRow(set, maxes, sessionPlan.handMode, onChange = onSetChange)
                        // With no band of its own the set follows the routine's, said under the row that
                        // could override it.
                        val inherited = inheritedTargetText(set, sessionPlan, maxes)
                        if (inherited != null) {
                            Text(inherited, style = MaterialTheme.typography.bodySmall, color = palette.inkTertiary)
                        }
                    }

                    // Chevrons cover reordering, so the long-press menu is a convenience, never the only way in.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoveButton(tr("Move up"), Icons.Filled.KeyboardArrowUp, canMoveUp, tr("Already the first set"), onMoveUp)
                        MoveButton(tr("Move down"), Icons.Filled.KeyboardArrowDown, canMoveDown, tr("Already the last set"), onMoveDown)
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable(onClick = onRemove)
                            .semantics { role = Role.Button },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Delete, null, tint = palette.alarm, modifier = Modifier.size(18.dp))
                        Text(
                            tr("Remove this set"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.alarm,
                        )
                    }
                }
            }
        }
    }
}

/// CUSTOM TIMING — off, the set follows the Rhythm page's hold and rest; on, it has its own, on
/// the same ladder steppers (Nuri, 2026-09-26).
///
/// Per-set timing is real (a max ramp, a repeater block), but two dials in every set was the
/// rule for a routine whose sets almost never differ. A switch states the exception; the closed
/// row still shows it in primary ink. Save clears overrides equal to the routine, so an ON
/// switch left untouched comes back OFF — intended.
@Composable
private fun CustomTiming(set: SetPlan, plan: SessionPlan, onSetChange: (SetPlan) -> Unit) {
    val reduceMotion = rememberReduceMotion()
    Column {
        ToggleRow(title = tr("Custom timing"), checked = set.overridesTiming, minHeight = 46.dp) { on ->
            onSetChange(BuilderDraft.withCustomTiming(set, plan, on))
        }
        AnimatedVisibility(
            visible = set.overridesTiming,
            enter = expandVertically(Motion.state(reduceMotion)) + fadeIn(Motion.state(reduceMotion)),
            exit = shrinkVertically(Motion.state(reduceMotion)) + fadeOut(Motion.state(reduceMotion)),
        ) {
            Column(Modifier.padding(start = 12.dp)) {
                // `null` on a set means "follow the routine": each row READS the resolved value and
                // WRITES this set's override.
                TimingStepper(TimingKind.Hold, PlanMath.hold(set, plan)) { onSetChange(set.copy(holdSeconds = it)) }
                TimingStepper(TimingKind.Rest, PlanMath.rest(set, plan)) { onSetChange(set.copy(restSeconds = it)) }
            }
        }
    }
}

@Composable
private fun MoveButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    disabledReason: String,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Metrics.buttonHorizontalPadding, vertical = Metrics.buttonVerticalPadding)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                // Say WHY it is dim: at either end of the list one of these is always disabled.
                if (!enabled) stateDescription = disabledReason
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) palette.graphite else palette.inkTertiary.copy(alpha = 0.5f)
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

/// Pulls, then the load — in TERTIARY when it is only the routine's band repeated, so six rows
/// of the same percentage do not shout — then any custom timing in PRIMARY.
///
/// **HARD RULE: a per-set timing override MUST render on the CLOSED row, in primary ink.** An
/// override hidden until the row opens produces a session nobody can explain.
private fun compactDetail(
    repsText: String,
    load: String?,
    inheritsLoad: Boolean,
    timing: String,
    secondary: androidx.compose.ui.graphics.Color,
    tertiary: androidx.compose.ui.graphics.Color,
    primary: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = secondary)) { append(repsText) }
    if (load != null) {
        withStyle(SpanStyle(color = if (inheritsLoad) tertiary else secondary)) { append(" · $load") }
    }
    if (timing.isNotEmpty()) {
        withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Medium)) { append(" · $timing") }
    }
}

/// The set's load as it will run: its own kilograms, its own percentage, or the routine's
/// percentage it follows. null = no target.
internal fun loadText(set: SetPlan, context: SetRowContext): String? {
    set.targetBand?.let { return WeightUnits.band(it) }
    val percent = set.targetPercentBand ?: context.plan.targetPercentBand ?: return null
    return L10n.tr(
        "%d–%d %%",
        (percent.start * 100).roundToInt(),
        (percent.endInclusive * 100).roundToInt(),
    )
}

/// Custom timing, only where it DIFFERS from the routine's.
internal fun timingOverrideText(set: SetPlan, context: SetRowContext): String {
    val parts = mutableListOf<String>()
    set.holdSeconds?.takeIf { it != context.holdSeconds }?.let { parts.add(L10n.tr("%d s hold", it)) }
    set.restSeconds?.takeIf { it != context.restSeconds }?.let { parts.add(L10n.tr("%d s rest", it)) }
    return parts.joinToString(" · ")
}

/// What the routine's percentage means for THIS grip, per hand — or null when the set has a
/// target of its own or the routine has none. Without a max it says so rather than showing a
/// percentage that resolves to nothing at session time.
internal fun inheritedTargetText(set: SetPlan, plan: SessionPlan, maxes: MaxTable): String? {
    if (set.hasTarget || set.hasPercentTarget) return null
    val percent = plan.targetPercentBand ?: return null
    val range = "${(percent.start * 100).roundToInt()}–${(percent.endInclusive * 100).roundToInt()} %"
    val load = perHandLoadText(set, plan, maxes)
        ?: return L10n.tr("Routine target: %s of max. No max for this grip yet.", range)
    return L10n.tr("Routine target: %s, so %s.", range, load)
}

/// "L 4.0–6.0 · R 4.5–6.5 kg", or one band when the hands agree or share the edge.
internal fun perHandLoadText(set: SetPlan, plan: SessionPlan, maxes: MaxTable): String? {
    if (plan.handMode.sideCount <= 1) {
        return PlanMath.targetBand(set, plan, Side.both, maxes)?.let { WeightUnits.band(it) }
    }
    val left = PlanMath.targetBand(set, plan, Side.left, maxes)
    val right = PlanMath.targetBand(set, plan, Side.right, maxes)
    return when {
        left == null && right == null -> null
        left != null && right != null && left == right -> WeightUnits.band(left)
        left != null && right != null -> L10n.tr("L %s · R %s", WeightUnits.band(left, withUnit = false), WeightUnits.band(right))
        left != null -> L10n.tr("L %s", WeightUnits.band(left))
        else -> L10n.tr("R %s", WeightUnits.band(right!!))
    }
}

/// The spoken form of the same overrides, from the optionals and in whole words: "12 s hold"
/// reads aloud as "twelve ess hold".
internal fun spokenOverride(set: SetPlan, context: SetRowContext, percentBandsVary: Boolean): String {
    val parts = mutableListOf<String>()
    // Only timing that DIFFERS from the routine, as the closed row shows it.
    set.holdSeconds?.takeIf { it != context.holdSeconds }?.let { parts.add(trQuantity("%d second hold", it)) }
    set.restSeconds?.takeIf { it != context.restSeconds }?.let { parts.add(trQuantity("%d second rest", it)) }
    val kg = set.targetBand
    val percent = set.targetPercentBand
    if (kg != null) {
        parts.add(WeightUnits.tr("target %s to %s kilograms", kgText(kg.start), kgText(kg.endInclusive)))
    } else if (percentBandsVary && percent != null) {
        parts.add(
            L10n.tr(
                "target %s to %s percent of your max",
                (percent.start * 100).roundToInt(),
                (percent.endInclusive * 100).roundToInt(),
            )
        )
    }
    return if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")
}

@Preview(name = "SetRowView", showBackground = true, widthDp = 380)
@Composable
private fun SetRowViewPreview() {
    GetAGripTheme {
        val plan = run.nuri.getagrip.engine.RoutineDraft.starter.plan
        var expanded by remember { mutableStateOf(plan.sets[0].id) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            plan.sets.take(2).forEachIndexed { index, set ->
                SetRowView(
                    set = set,
                    context = SetRowContext.of(plan),
                    isExpanded = expanded == set.id,
                    maxes = MaxTable(),
                    percentBandsVary = false,
                    canMoveUp = index > 0,
                    canMoveDown = index < plan.sets.size - 1,
                    onTap = { expanded = if (expanded == set.id) UUID.randomUUID() else set.id },
                    onEditGrip = {},
                    onMoveUp = {},
                    onMoveDown = {},
                    onDuplicate = {},
                    onRemove = {},
                    onSetChange = {},
                )
            }
        }
    }
}
