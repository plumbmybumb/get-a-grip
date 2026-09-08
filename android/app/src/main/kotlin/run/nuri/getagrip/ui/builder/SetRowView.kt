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
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
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
/// The accordion is not only a readability device: at most one row is open at a time (the
/// builder owns that state), which is what guarantees only one dense control cluster exists
/// on screen at any moment.
///
/// **Two levels of disclosure, never three.** The set row opens, and the target row opens
/// inside it. That is the ceiling the research names, and it is why the grip picker is a
/// panel rather than a third level.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetRowView(
    /// **The WHOLE plan, wrapped so Compose can prove it unchanged** — see `StablePlan`.
    /// The row reads it for every resolved number (the inherited hold, the ×2, this row's
    /// clock) and writes its own set back through one callback.
    plan: StablePlan,
    /// Which set this row draws — an ID, never an index, so a reorder cannot point a row at
    /// its neighbour.
    setID: UUID,
    isExpanded: Boolean,
    /// Every max on file, by grip and hand. Passed as a VALUE so the row stays previewable
    /// and never touches a store.
    maxes: MaxTable,
    /// Folded once by the builder that owns the whole plan. Computing it in every row made
    /// both the visible and the spoken summaries scan every set again.
    percentBandsVary: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    /// Asks the BUILDER to open the grip panel for this set — nothing in a scrolling row can
    /// reach the top of the screen.
    onEditGrip: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onSetChange: (SetPlan) -> Unit,
) {
    val palette = LocalGripPalette.current
    val sessionPlan = plan.plan
    /// This row's set. A row whose set has just been removed keeps drawing an empty one for
    /// the frame before the list drops it, rather than trapping on a stale index.
    val set = sessionPlan.sets.firstOrNull { it.id == setID } ?: SetPlan()
    var menuOpen by remember { mutableStateOf(false) }

    val repsText = if (sessionPlan.handMode.sideCount > 1) {
        L10n.tr("%d per side", set.repsPerSide)
    } else {
        // One-sided modes have no side to divide by, so the copy drops "per side" rather
        // than halving a number that was never doubled.
        L10n.tr("%d %s", set.repsPerSide, L10n.tr(if (set.repsPerSide == 1) "pull" else "pulls"))
    }
    val tensionText = PlanMath.tensionSecondsPerSide(set, sessionPlan)?.let {
        L10n.tr("%s under tension per side", PlanMath.clockText(it))
    } ?: run {
        val reps = PlanMath.repCount(set, sessionPlan.handMode)
        L10n.tr("%s under tension", PlanMath.clockText(reps * PlanMath.hold(set, sessionPlan)))
    }
    val overrideText = overrideText(set, percentBandsVary)
    // Both read outside the semantics lambda, which is not composable. The visual line and
    // the spoken line are the same sentence, written once as prose.
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
                        // The long-press menu lives ON THE HEADER, never on the whole row:
                        // attached to the row, a hold anywhere in the EXPANDED editor —
                        // exactly where you are right after Add a set — would grab touches
                        // from the controls you are trying to drag.
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onTap,
                            onLongClick = { menuOpen = true },
                        )
                        .pressFeedback(interactionSource, scales = false)
                        .padding(16.dp)
                        // The visual line and the spoken line are the same sentence; it
                        // comes free from having written the row as prose in the first place.
                        .semantics(mergeDescendants = true) {
                            contentDescription = spoken
                            // NOT the catalog's "Open": that key is the open-hand GRIP
                            // POSITION and translates to "Tendue". A disclosure's state has
                            // its own words — android_extra.json.
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

            // A disclosure is the ladder's DEFAULT motion — critically damped, and flat under
        // Reduce Motion. Compose's own default here is an unguarded 400 ms tween nothing in
        // this app chose.
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

                    // ONE control, 60 dp, where an edge slider + finger pad + position chips
                    // used to stack to about 400. All three still exist, in the grip panel.
                    GripToken(set.grip, onEdit = onEditGrip)

                    // A STEPPER, not a slider with chips: this is a small integer you want
                    // EXACTLY, nudged around a common one — the HIG's own description of
                    // when a stepper is the control. It repeats while held.
                    IntValueRow(
                        title = tr("Pulls per side"),
                        value = set.repsPerSide,
                        range = 1..12,
                        limit = 1..20,
                        caption = tr("= %s", tensionText),
                        control = ValueControl.Stepper,
                    ) { onSetChange(set.copy(repsPerSide = it)) }

                    // **ONE ladder for both dials, differing only at the floor.** They sit
                    // stacked, cover the same sixty seconds, and the dial spaces detents by
                    // INDEX — so different ladders render different notches for the same
                    // span and read as two different instruments (Nuri, 2026-08-18: "why is
                    // the notches on the slider for hold and rest different"). The floor is
                    // the one honest difference: a zero-second rest is a real cadence, a
                    // zero-second hold is not a hold.
                    //
                    // `null` on a set means "follow the routine", so each row READS the
                    // resolved value and WRITES this set's own override.
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

                    // Chevrons in the expanded row cover reordering, so no drag gesture is
                    // load-bearing and the long-press menu is a convenience rather than the
                    // only way in.
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
                // The dim state otherwise says nothing about WHY: at either end of the list
                // one of these two is always disabled with no caption on screen.
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

/// The shared interior of the Hold and Rest dials — every detent the shipping protocols use
/// (3 s C4 holds, 5/7/10/12 s repeaters, 15–60 s rests), defined once so the two ladders
/// cannot drift apart again. Each dial prepends only its floor.
private val SECONDS_LADDER = listOf(3.0, 5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 30.0, 45.0, 60.0)

/// "6 per side · 1:00 under tension per side", plus any timing override.
///
/// **HARD RULE: a per-set override MUST render on the COLLAPSED row, in PRIMARY ink.** An
/// override that is invisible until you open the row produces a session nobody can explain
/// — including whoever set it.
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

/// Every override this set carries, in the order they appear in the editor. Both timings are
/// listed because the rule is about VISIBILITY, not about the hold alone.
///
/// **A PERCENTAGE shows only where the sets DISAGREE.** A ramp is the whole shape of a max
/// protocol and has to be readable straight down the list — 50–60, 65–75, 80–90 — whereas
/// six sets that all say 18–22 % is the card talking to itself.
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

/// The spoken form of the same overrides — built from the optionals rather than by unpicking
/// the visual string, and in whole words, because "12 s hold" is read out as "twelve ess
/// hold".
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
                    plan = StablePlan(plan),
                    setID = set.id,
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
