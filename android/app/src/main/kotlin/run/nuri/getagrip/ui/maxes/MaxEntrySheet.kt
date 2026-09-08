// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.maxes

import run.nuri.getagrip.ui.units.WeightUnits

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.Fmt
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.components.FingerPips
import run.nuri.getagrip.ui.components.IntValueRow
import run.nuri.getagrip.ui.components.PositionChipRow
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SubmissionState
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.components.ValueRow
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// The composer's whole state, as a HOLDER rather than a value.
///
/// **It has to survive the measure hand-off.** "Measure on the gauge" is a full-screen
/// destination on Android (the phone is on a bench and you are on a fingerboard with both
/// hands), so the host tears this sheet out of composition while it is up. A draft held
/// inside the sheet would come back blank — the grip you were building, the hand you picked
/// and the number you had typed all gone — so the draft is `remember`ed by the HOST and
/// handed in, exactly as `RunRequest` and `MeasureRequest` are.
///
/// A holder rather than a `data class` for the same reason `MaxMeasurement` is one: the
/// values are snapshot state that several controls write independently, and copying a value
/// through four `onValueChange` lambdas would put the whole grip on every keystroke's path.
class MaxEntryDraft(seed: GripSpec = GripSpec()) {
    var grip: GripSpec by mutableStateOf(seed)
    var kg: Double by mutableDoubleStateOf(0.0)

    /// Defaults to `both`, which is what an untouched picker has always meant and what every
    /// record written before hands existed means. Nothing here is required.
    var side: Side by mutableStateOf(Side.both)

    /// What the gauge last handed back, if anything. **Provenance is DERIVED by comparing it
    /// to the live value rather than carried as a flag**, which makes the answer
    /// self-correcting: drag or type the number away from what was measured and the record
    /// honestly becomes `manual` again, with no ordering rules about which change runs first.
    var measuredKg: Double? by mutableStateOf(null)

    val source: MaxSource
        get() = if (measuredKg == kg) MaxSource.measured else MaxSource.manual

    /// `recordMax` rejects zero outright — a 0 kg max would make every percentage caption in
    /// the app divide by nothing — so Save is disabled on exactly that range.
    val canSave: Boolean get() = kg > 0

    /// The number the gauge produced, landing in the same field a typed one would.
    fun receiveMeasured(value: Double, measuredSide: Side = side) {
        kg = clamped(value)
        measuredKg = kg
        side = measuredSide
    }

    /// **`sliderRange` is what the SLIDER spans, `limit` is what a TYPED value is clamped
    /// to.** 60 was never a storage limit — typing already reached 200 — but a slider that
    /// stops is read as a ceiling, and being told your max is off-scale is a poor welcome
    /// (Nuri, 2026-08-04: "there are people who can do a 20 mil edge much more than 60 kg").
    /// 100 keeps a typical 25 kg pull at a quarter of the track, which is still a usable drag.
    companion object {
        val sliderRange = 0.0..100.0
        val limit = 0.0..250.0

        fun clamped(kg: Double): Double = kg.coerceIn(limit.start, limit.endInclusive)
    }
}

/// Adding a max, as one short document rather than a picker followed by a form.
///
/// Every control here is the same one the builder uses for the same job, deliberately: the
/// edge is an `IntValueRow`, the fingers are `FingerPips`, the position is a
/// `PositionChipRow`. Someone who has built a routine has already learned this screen.
///
/// TRANSLATION NOTE: iOS shows the receipt by swapping the sheet's own content and moving
/// the toolbar to a single Done. A bottom sheet has no toolbar, so the same swap happens in
/// the body and the actions live at its foot — one sheet, two faces, exactly as on iOS.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxEntrySheet(
    draft: MaxEntryDraft,
    /// Hands off to the full-screen measure host. The sheet leaves composition while it is
    /// up; the draft does not — see `MaxEntryDraft`.
    onMeasure: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val feed = LocalHistoryFeed.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // The form is three controls plus a rail; the partial detent cuts it in half, and a
    // section that opens under the fold reads as having done nothing.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var failed by remember { mutableStateOf(false) }
    val submission = remember { SubmissionState() }
    /// Set the moment Save lands with anything to report; the sheet then shows the receipt
    /// instead of dismissing. null = still editing.
    var impact by remember { mutableStateOf<TemplateStore.MaxImpact?>(null) }

    fun save() {
        if (!draft.canSave || submission.isRunning) return
        val grip = draft.grip
        val kg = draft.kg
        val side = draft.side
        val source = draft.source
        failed = false
        submission.launch(scope) {
            // Asked BEFORE the record lands — afterwards the old max is just history and the
            // ratio it anchors is gone.
            val previousMaxes = templates.maxTable.copy()
            if (!templates.recordMax(
                    kg = kg,
                    grip = grip,
                    // Spelled out rather than left to the default, and it is genuinely a
                    // choice: `source` is `measured` only while the value is still the one
                    // the gauge produced.
                    source = source,
                    side = side,
                )
            ) {
                // A rolled-back save leaves the sheet OPEN with the error inline — dismissing
                // on failure destroys the form and the number with it.
                failed = true
                return@launch
            }
            feed.refresh()
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            val computed = templates.maxImpact(grip = grip, previousMaxes = previousMaxes, newKg = kg, side = side)
            if (computed.isEmpty) onClose() else impact = computed
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.hPadding)
                .padding(bottom = Metrics.spacing),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                if (impact == null) tr("New max") else tr("Saved"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            // The grip as it currently stands, live — so the thing being recorded is stated
            // somewhere fixed while you are three controls deep changing it.
            Text(
                draft.grip.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary,
            )

            val receipt = impact
            if (receipt == null) {
                FormContent(draft, failed, onMeasure)
                PrimaryButton(tr("Save"), enabled = draft.canSave && !submission.isRunning, onClick = ::save)
                SecondaryButton(title = tr("Cancel"), modifier = Modifier.fillMaxWidth(), onClick = onClose)
            } else {
                ImpactContent(
                    draft = draft,
                    impact = receipt,
                    onScale = { ratio ->
                        scope.launch {
                            templates.scaleKgTargets(
                                grip = draft.grip,
                                ratio = ratio,
                                routineIDs = receipt.kgOffers.map { it.routineID },
                            )
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onClose()
                        }
                    },
                )
                // The max is already SAVED — there is no cancel any more, and the kg offer's
                // "Leave them as they are" is a button in the content, not chrome.
                PrimaryButton(tr("Done"), onClick = onClose)
            }
        }
    }
}

// MARK: - The form

@Composable
private fun ColumnScope.FormContent(draft: MaxEntryDraft, failed: Boolean, onMeasure: () -> Unit) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current

    GripRail(draft)

    IntValueRow(
        title = tr("Edge"),
        value = draft.grip.edgeMM,
        range = 4..45,
        unit = tr("mm"),
        limit = GripSpec.edgeRange,
        presets = listOf(6, 10, 20, 30),
    ) { draft.grip = draft.grip.withEdgeMM(it) }

    Block(tr("FINGERS")) {
        FingerPips(draft.grip.fingers, draft.grip.position) { draft.grip = draft.grip.withFingers(it) }
    }

    Block(tr("GRIP")) {
        PositionChipRow(draft.grip.position) { draft.grip = draft.grip.withPosition(it) }
    }

    HorizontalDivider(color = palette.inkTertiary.copy(alpha = 0.22f))

    HandBlock(draft)

    MeasureRow(onMeasure)

    // No section label above it: the row states its own subject, and a "MAX" caps label over
    // a row titled "Max on this grip" is the same word twice in eighteen points of height.
    ValueRow(
        title = tr("Max on this grip"),
        value = WeightUnits.fromKg(draft.kg),
        range = WeightUnits.sliderRange(MaxEntryDraft.sliderRange),
        unit = WeightUnits.symbol,
        limit = WeightUnits.fromKg(MaxEntryDraft.limit),
        step = 0.5,
        decimals = 1,
        caption = bandCaption(draft.kg),
    ) { draft.kg = MaxEntryDraft.clamped(WeightUnits.toKg(it)) }

    // Append, never edit — so the sheet says so before you tap Save rather than leaving you
    // to discover a second row afterwards. Keyed by grip AND hand: the record this save
    // supersedes is the one for the SAME hand, and quoting the other hand's number here would
    // read as a contradiction of what you are about to type.
    templates.currentMaxes[MaxTable.key(draft.grip.key, draft.side)]?.let { existing ->
        val hand = if (draft.side == Side.both) "" else tr(" for that hand")
        Text(
            WeightUnits.tr(
                "Your current max on this grip%s is %s kg, recorded %s. Saving adds a new one and keeps the old as history.",
                hand,
                WeightUnits.number(existing.kg, 1),
                relative(existing.recordedAt),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
        )
    }

    if (failed) {
        Text(
            tr("That couldn't be saved — nothing was recorded. Try again."),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = palette.alarm,
        )
    }

    // Says which of the two this number IS, and keeps saying it as the number changes. It
    // used to read "the app does not measure it for you", which was load-bearing while that
    // was true and would be a lie the moment measuring shipped — so it MOVED with the feature
    // rather than being deleted by it.
    Text(
        if (draft.source == MaxSource.measured) {
            tr("Measured on the gauge — your hardest pull on this grip.")
        } else {
            tr("A number you entered. Check its value and units before using it for targets.")
        },
        style = MaterialTheme.typography.bodySmall,
        color = palette.inkTertiary,
    )
}

/// What this max BUYS you, stated while you are still setting it: the low-intensity band is
/// the reason the number is being asked for at all.
///
/// **Also the disabled Save's only explanation.** Save disables on `kg <= 0`, and
/// `suggestedBand` returns null for exactly that range — so before this an empty sheet showed
/// a dimmed Save with nothing on screen to say why.
private fun bandCaption(kg: Double): String? {
    if (kg <= 0) return L10n.tr("Enter a max above zero to save it.")
    val band = PlanMath.suggestedBand(kg) ?: return null
    return WeightUnits.tr(
        "20–30 %% of that is %s–%s kg",
        WeightUnits.number(band.start, 1),
        WeightUnits.number(band.endInclusive, 1),
    )
}

/// Grips you already train, offered as a starting point — NOT a library. Nothing here is
/// stored, nothing is curated, and the pick is only a seed: every field below stays editable,
/// so a grip you have never used costs three taps rather than a setup step.
@Composable
private fun GripRail(draft: MaxEntryDraft) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    if (templates.recentGrips.isEmpty()) return
    Block(tr("START FROM")) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            templates.recentGrips.forEach { candidate ->
                Chip(
                    candidate.shortName,
                    candidate.key == draft.grip.key,
                    Modifier
                        .widthIn(min = 88.dp)
                        .semantics { contentDescription = candidate.spoken },
                ) { draft.grip = candidate }
            }
        }
        Text(
            tr("Grips from your routines. Tap one, then change anything you like."),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkTertiary,
        )
    }
}

/// WHICH HAND this max is for. Visible rather than folded behind a disclosure: it is
/// pre-answered with "Both hands", so it costs nothing to ignore, and a control hidden behind
/// a chevron is one nobody discovers — which would waste the whole feature on the people
/// whose hands differ enough to need it.
///
/// The caption is where the consequence lives, because the chips cannot say it: a
/// side-specific max is a statement that your OTHER hand is different, and it stops applying
/// to that hand the moment you pick one.
@Composable
private fun HandBlock(draft: MaxEntryDraft) {
    val palette = LocalGripPalette.current
    Block(tr("THIS MAX IS FOR")) {
        MaxHandPicker(selectedSide = draft.side, onSelected = { draft.side = it })
        Text(
            if (draft.side == Side.both) {
                tr("Used for both hands. Pick a hand if yours differ — most people's do.")
            } else {
                tr(
                    "Only your %s hand. Its targets come from this number; your other hand needs its own.",
                    draft.side.displayName.lowercase(),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkTertiary,
        )
    }
}

/// The way in to measuring. Offered whatever the gauge is doing — `MaxMeasureScreen` handles
/// a missing connection with a Connect button and a way back, which is more use than a
/// disabled control that explains nothing.
@Composable
private fun MeasureRow(onMeasure: () -> Unit) {
    val palette = LocalGripPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            title = tr("Measure on the gauge"),
            icon = Icons.Outlined.MonitorHeart,
            modifier = Modifier.fillMaxWidth(),
            onClick = onMeasure,
        )
        Text(
            tr("Pull as hard as you can — Get a Grip keeps the hardest the gauge sees. Or set it by hand below."),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkTertiary,
        )
    }
}

// MARK: - The receipt

/// **What the number you just saved moves** — shown INSTEAD of dismissing, and only when
/// there is something to say. Two sections with two different verbs: percent bands already
/// moved (they follow the newest max by design — this is visibility, not a question), while
/// typed-kilogram sets are OFFERED a rescale, because a number a person typed is never
/// rewritten by arithmetic without a yes.
@Composable
private fun ColumnScope.ImpactContent(
    draft: MaxEntryDraft,
    impact: TemplateStore.MaxImpact,
    onScale: (Double) -> Unit,
) {
    val palette = LocalGripPalette.current

    Block(tr("Saved")) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                WeightUnits.number(draft.kg, 1),
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            Text(
                WeightUnits.tr("kg · %s", draft.grip.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary,
            )
        }
    }

    if (impact.percentMoves.isNotEmpty()) {
        Block(tr("TARGETS THAT FOLLOWED")) {
            impact.percentMoves.forEach { move ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (move.side == Side.both) move.routineName
                        else "${move.routineName} · ${move.side.displayName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                    )
                    Text(
                        percentLine(move),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = palette.inkSecondary,
                    )
                }
            }
            Text(
                tr("Percent targets always follow your newest max — nothing to do."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
    }

    val ratio = impact.ratio
    if (impact.kgOffers.isNotEmpty() && ratio != null) {
        Block(tr("Weight targets").uppercase()) {
            impact.kgOffers.forEach { offer ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        offer.routineName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                    )
                    offer.moves.forEach { move ->
                        Text(
                            WeightUnits.tr("%s kg  →  %s kg", bandText(move.oldBand), bandText(move.newBand)),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = palette.inkSecondary,
                        )
                    }
                }
            }
            Text(
                tr("These were typed by hand, so they never move on their own. Scale them with the new max, or leave them."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
            PrimaryButton(tr("Scale with the new max")) { onScale(ratio) }
        }
    }
}

private fun percentLine(move: TemplateStore.MaxImpact.PercentMove): String {
    val pct = L10n.tr("%d–%d %%", Math.round(move.loPercent * 100), Math.round(move.hiPercent * 100))
    var line = WeightUnits.tr("%s · now %s kg", pct, bandText(move.newBand))
    val old = move.oldBand
    if (old != null && old != move.newBand) line += L10n.tr(" · was %s", bandText(old))
    return line
}

private fun bandText(band: ClosedFloatingPointRange<Double>): String =
    L10n.tr("%s–%s", WeightUnits.number(band.start, 1), WeightUnits.number(band.endInclusive, 1))

@Composable
private fun Block(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsLabel(label)
        content()
    }
}

@Preview(name = "Max entry · hand block", showBackground = true, widthDp = 380)
@Composable
private fun MaxEntryPreview() {
    GetAGripTheme {
        val draft = remember { MaxEntryDraft(GripSpec(edgeMM = 20, position = GripPosition.halfCrimp)) }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            HandBlock(draft)
            MeasureRow {}
        }
    }
}
