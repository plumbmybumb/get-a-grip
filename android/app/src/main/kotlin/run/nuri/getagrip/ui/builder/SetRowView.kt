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
import run.nuri.getagrip.engine.SetPlan
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

/// One row of the routine — collapsed it is a SENTENCE, expanded it is the whole set.
///
/// At most one row is open (the builder owns that), so one dense control cluster exists at a
/// time. **Two levels of disclosure, never three**: the set row, then the target row inside
/// it — which is why the grip picker is a panel.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetRowView(
    /// This row's set, as a value. It writes its edits back through one callback.
    set: SetPlan,
    /// **Only the routine-level fields the row resolves against** (inherited hold, ×2, lead-in).
    /// Given the WHOLE plan, every row redrew for a letter typed into the name. See `SetRowContext`.
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
    val sessionPlan = context.plan
    var menuOpen by remember { mutableStateOf(false) }

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
    val overrideText = overrideText(set, percentBandsVary)
    // Read outside the (non-composable) semantics lambda. Visual and spoken lines are the same
    // sentence, written once as prose.
    val spoken = tr(
        "%s. %s, %s%s. %s.",
        set.grip.spoken,
        repsText,
        tensionText,
        spokenOverride(set, percentBandsVary),
        PlanMath.durationText(PlanMath.setSeconds(set, sessionPlan)),
    )
    val disclosure = tr(if (isExpanded) "Expanded" else "Collapsed")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.radiusCard),
        color = palette.card,
    ) {
        Column(Modifier.fillMaxWidth()) {
            val interactionSource = remember { MutableInteractionSource() }
            Box {
                Row(
                    Modifier
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
                        .padding(16.dp)
                        // The visual line and the spoken line are the same sentence.
                        .semantics(mergeDescendants = true) {
                            contentDescription = spoken
                            // NOT the catalog's "Open": that is the open-hand GRIP POSITION ("Tendue"). Disclosure
                            // state has its own words in android_extra.json.
                            stateDescription = disclosure
                            role = Role.Button
                        },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    FingerGlyph(
                        fingers = set.grip.fingers,
                        position = set.grip.position,
                        dot = 6.dp,
                        gap = 3.dp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            set.grip.line,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                            maxLines = 2,
                        )
                        Text(
                            detailLine(repsText, tensionText, overrideText, palette.inkSecondary, palette.inkPrimary),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        PlanMath.clockText(PlanMath.setSeconds(set, sessionPlan)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkTertiary,
                    )
                    Icon(
                        if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = palette.inkTertiary,
                        modifier = Modifier.size(20.dp),
                    )
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
            enter = expandVertically(Motion.state(rememberReduceMotion())) +
                fadeIn(Motion.state(rememberReduceMotion())),
            exit = shrinkVertically(Motion.state(rememberReduceMotion())) +
                fadeOut(Motion.state(rememberReduceMotion())),
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
                        caption = tr("= %s", tensionText),
                        control = ValueControl.Stepper,
                    ) { onSetChange(set.copy(repsPerSide = it)) }

                    // **ONE ladder for both dials, differing only at the floor.** They sit stacked over the same
                    // sixty seconds and detents are spaced by INDEX, so different ladders read as two
                    // instruments (Nuri, 2026-08-18). The floor is the honest difference: a zero-second rest
                    // is a cadence, a zero-second hold is not a hold.
                    //
                    // `null` on a set means "follow the routine": each row READS the resolved value and WRITES
                    // this set's override.
                    IntValueRow(
                        title = tr("Hold"),
                        value = PlanMath.hold(set, sessionPlan),
                        range = 1..60,
                        unit = tr("s"),
                        limit = SetPlan.holdRange,
                        control = ValueControl.Dial(listOf(1.0) + SECONDS_LADDER),
                    ) { onSetChange(set.copy(holdSeconds = it)) }
                    IntValueRow(
                        title = tr("Rest between pulls"),
                        value = PlanMath.rest(set, sessionPlan),
                        range = 0..60,
                        unit = tr("s"),
                        limit = SetPlan.restRange,
                        control = ValueControl.Dial(listOf(0.0) + SECONDS_LADDER),
                    ) { onSetChange(set.copy(restSeconds = it)) }

                    TargetBandRow(set, maxes, sessionPlan.handMode, onChange = onSetChange)

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

/// The shared interior of the Hold and Rest dials — every detent the shipping protocols use —
/// defined once so the ladders cannot drift. Each dial prepends only its floor.
private val SECONDS_LADDER = listOf(3.0, 5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 30.0, 45.0, 60.0)

/// "6 per side · 1:00 under tension per side", plus any timing override.
///
/// **HARD RULE: a per-set override MUST render on the COLLAPSED row, in PRIMARY ink.** An
/// override hidden until the row opens produces a session nobody can explain.
private fun detailLine(
    repsText: String,
    tensionText: String,
    overrideText: String,
    secondary: androidx.compose.ui.graphics.Color,
    primary: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = secondary)) { append(L10n.tr("%s · %s", repsText, tensionText)) }
    if (overrideText.isNotEmpty()) {
        withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Medium)) { append(overrideText) }
    }
}

/// Every override this set carries, in editor order — the rule is VISIBILITY, so both timings.
///
/// **A PERCENTAGE shows only where the sets DISAGREE**: a max protocol's ramp (50–60, 65–75,
/// 80–90) must read down the list, but six identical 18–22 % is the card talking to itself.
internal fun overrideText(set: SetPlan, percentBandsVary: Boolean): String {
    val parts = mutableListOf<String>()
    set.holdSeconds?.let { parts.add(L10n.tr("%d s hold", it)) }
    set.restSeconds?.let { parts.add(L10n.tr("%d s rest", it)) }
    val kg = set.targetBand
    val percent = set.targetPercentBand
    if (kg != null) {
        parts.add(WeightUnits.tr("%s–%s kg", kgText(kg.start), kgText(kg.endInclusive)))
    } else if (percentBandsVary && percent != null) {
        parts.add(
            L10n.tr(
                "%d–%d %%",
                (percent.start * 100).roundToInt(),
                (percent.endInclusive * 100).roundToInt(),
            )
        )
    }
    return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
}

/// The spoken form of the same overrides, from the optionals and in whole words: "12 s hold"
/// reads aloud as "twelve ess hold".
internal fun spokenOverride(set: SetPlan, percentBandsVary: Boolean): String {
    val parts = mutableListOf<String>()
    set.holdSeconds?.let { parts.add(trQuantity("%d second hold", it)) }
    set.restSeconds?.let { parts.add(trQuantity("%d second rest", it)) }
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
