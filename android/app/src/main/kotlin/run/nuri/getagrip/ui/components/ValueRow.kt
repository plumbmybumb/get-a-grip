// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette

/// Which control a `ValueRow` carries, because not every quantity wants the same one
/// (Nuri, 2026-08-04: "not loving how everything is a button").
sealed interface ValueControl {
    /// A continuous physical quantity where "about right" is the usual intent. Almost
    /// nothing in a routine is like this — see `Dial`.
    data object Slider : ValueControl

    /// A small integer you want EXACTLY, nudged around a common one (pulls per side) — the HIG's
    /// case for a stepper. Four chips could never have held 4.
    data object Stepper : ValueControl

    /// A `DialTrack` over the ladder — one evenly spaced detent per value. Right for EXACT
    /// quantities drawn from a handful of real numbers: nearly every quantity in a routine.
    data class Dial(val ladder: List<Double>) : ValueControl

    /// When the presets genuinely are the vocabulary.
    data object None : ValueControl
}

/// A number you can drag, tap or type — the app's control for every quantity: the dial (or
/// slider) for the coarse move, at most four presets (more is a menu again), and **tap the
/// number to type an exact one**.
@Composable
fun ValueRow(
    title: String,
    value: Double,
    /// The range the SLIDER spans — values you reach for, not what storage allows. The storage
    /// clamp (rest tolerates 600 s) put a 20 s rest at 3 % of the track and made every drag jump.
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier,
    unit: String = "",
    /// The hard clamp a TYPED value is held to, when the storage range is wider than
    /// anything worth dragging to. Defaults to the slider's range.
    limit: ClosedFloatingPointRange<Double>? = null,
    step: Double = 1.0,
    presets: List<Double> = emptyList(),
    decimals: Int = 0,
    /// Shown under the row when the value deserves a consequence ("Measured: 42.0 kg").
    caption: String? = null,
    control: ValueControl = ValueControl.Slider,
    onValueChange: (Double) -> Unit,
) {
    val palette = LocalGripPalette.current
    val bounds = limit ?: range

    /// Whether the number is a field. It changes twice per edit, so it lives here; the per-key
    /// DRAFT STRING does not — see `ValueField`.
    var isTyping by remember { mutableStateOf(false) }

    /// Only when a slider remains. Where presets ARE the control, hiding them leaves typing —
    /// the most demanding path — as the only way in.
    val hidesPresets = control == ValueControl.Slider && LocalDensity.current.fontScale >= 1.5f

    /// Whether a full-width track draws UNDER the title row; the gap keeps a draggable strip clear
    /// of the numbers. A stepper sits IN the row.
    val hasTrack = control is ValueControl.Slider || control is ValueControl.Dial

    // Reuse the locale's formatter rather than building one per label per pointer update.
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale, decimals) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            isGroupingUsed = false
        }
    }
    fun formattedValue(number: Double) = formatter.format(number)
    val text = formattedValue(value)

    fun rounded(raw: Double) = ValueFieldParser.rounded(raw, decimals)

    fun stepBy(delta: Double): Boolean {
        val next = rounded(value + delta).coerceIn(bounds.start, bounds.endInclusive)
        if (next == value) return false
        onValueChange(next)
        return true
    }

    fun canStep(delta: Double): Boolean {
        val next = rounded(value + delta)
        return next >= bounds.start && next <= bounds.endInclusive
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = if (hasTrack) 4.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (hasTrack) 8.dp else 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.inkPrimary,
                modifier = Modifier.weight(1f),
            )
            if (control == ValueControl.Stepper && !isTyping) {
                RepeatingStep(
                    glyph = StepGlyph.Minus,
                    enabled = canStep(-step),
                    contentDescription = L10n.tr("Decrease %s", title),
                ) { stepBy(-step) }
            }
            if (isTyping) {
                ValueField(
                    placeholder = text,
                    unit = unit,
                    decimals = decimals,
                    modifier = Modifier.weight(1f),
                ) { typed ->
                    // `null` means untouched: changing your mind must not zero it.
                    if (typed != null) {
                        val next = typed.coerceIn(bounds.start, bounds.endInclusive)
                        if (next != value) onValueChange(next)
                    }
                    isTyping = false
                }
            } else {
                // The value doubles as the button that types it: read first, edited second.
                Row(
                    Modifier
                        // 44 both ways: a one-glyph label with 20 dp padding is under the house floor.
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .clickable(role = Role.Button) { isTyping = true }
                        .padding(horizontal = 10.dp)
                        .semantics {
                            contentDescription =
                                L10n.tr("%s, %s %s. Double tap to type a value.", title, text, unit)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary,
                    )
                    if (unit.isNotEmpty()) {
                        Text(
                            unit,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkTertiary,
                        )
                    }
                }
            }
            if (control == ValueControl.Stepper && !isTyping) {
                RepeatingStep(
                    glyph = StepGlyph.Plus,
                    enabled = canStep(step),
                    contentDescription = L10n.tr("Increase %s", title),
                ) { stepBy(step) }
            }
        }

        when (control) {
            is ValueControl.Slider -> {
                val steps = sliderSteps(range, step)
                Slider(
                    // Clamps only what the SLIDER sees: a typed 90 s hold stays 90 s; the thumb parks at the end.
                    value = value.coerceIn(range.start, range.endInclusive).toFloat(),
                    onValueChange = {
                        val next = rounded(it.toDouble())
                        if (next != value) onValueChange(next)
                    },
                    valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                    steps = steps,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.graphite,
                        activeTrackColor = palette.graphite,
                    ),
                    modifier = Modifier.semantics { contentDescription = title },
                )
            }
            is ValueControl.Dial -> {
                DialTrack(
                    value = value,
                    // The LADDER only, filtered to what this row can hold. Splicing in the current value
                    // re-spaced the stops under your finger; see `DialTrack.values`.
                    values = remember(control.ladder, bounds) { control.ladder.filter { it in bounds } },
                    format = { formattedValue(it) },
                    spokenUnit = unit,
                    label = title,
                    onValueChange = onValueChange,
                )
            }
            ValueControl.Stepper, ValueControl.None -> Unit
        }

        if (presets.isNotEmpty() && !hidesPresets) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    Chip(
                        title = formattedValue(preset),
                        isSelected = abs(value - preset) < 0.001,
                        modifier = Modifier.weight(1f),
                    ) { onValueChange(preset) }
                }
            }
        }

        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }
    }
}

/// Integer flavour, so callers binding an `Int` do not each write the same conversion.
@Composable
fun IntValueRow(
    title: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    unit: String = "",
    limit: IntRange? = null,
    step: Int = 1,
    presets: List<Int> = emptyList(),
    caption: String? = null,
    control: ValueControl = ValueControl.Slider,
    /// The stepper's increment when it differs from the slider's: a stepper moving in fives
    /// could never reach 12.
    stepBy: Int? = null,
    onValueChange: (Int) -> Unit,
) {
    ValueRow(
        title = title,
        value = value.toDouble(),
        range = range.first.toDouble()..range.last.toDouble(),
        modifier = modifier,
        unit = unit,
        limit = limit?.let { it.first.toDouble()..it.last.toDouble() },
        step = (stepBy ?: step).toDouble(),
        presets = presets.map { it.toDouble() },
        decimals = 0,
        caption = caption,
        control = control,
    ) { onValueChange(it.roundToInt()) }
}

/// The typed number, and NOTHING else — the one thing on the row that changes per keypress.
///
/// **The draft string lives in this leaf** (house rule for high-frequency state): on the row,
/// every character re-ran the dial, its detents and labels, presets and caption.
///
/// **It opens EMPTY, with the current value as placeholder**: pre-filling put the caret after
/// the digits, so typing 22 over 15 gave "1522". Leaving the field COMMITS (a vanished number
/// is worse than a clamped one); an untouched field reports null. Clamping stays with the
/// CALLER, which knows `limit` versus `range`.
@Composable
private fun ValueField(
    placeholder: String,
    unit: String,
    decimals: Int,
    modifier: Modifier = Modifier,
    onCommit: (Double?) -> Unit,
) {
    val palette = LocalGripPalette.current
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var committed by remember { mutableStateOf(false) }
    /// The first `onFocusChanged` fires `false` before the requester runs; committing then would
    /// close the field on the frame it opened.
    var everFocused by remember { mutableStateOf(false) }

    fun commit(dismissKeyboard: Boolean = true) {
        if (committed) return
        committed = true
        // Focus may already belong to the next field. Do not hide its keyboard.
        if (dismissKeyboard) keyboard?.hide()
        onCommit(ValueFieldParser.parse(draft, decimals))
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(palette.graphite),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimals > 0) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 44.dp)
                .focusRequester(focusRequester)
                // Tapping elsewhere COMMITS (see above); an empty draft parses to null and just closes.
                .onFocusChanged { state ->
                    if (state.isFocused) everFocused = true else if (everFocused) commit(dismissKeyboard = false)
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.fillMaxWidth()) {
                    if (draft.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkTertiary,
                        )
                    }
                    inner()
                }
            },
        )
        if (unit.isNotEmpty()) {
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = palette.inkTertiary)
        }
        TextButton(
            onClick = { commit() },
            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
        ) {
            Text(tr("Done"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/// The typed-number contract, lifted out so every rule is asserted in a JVM test.
object ValueFieldParser {

    /// Accepts a comma as well as a point ("2,5" in French, "2.5" in English).
    ///
    /// **NOT snapped to the row's `step`.** The dial lands on round numbers; typing is the escape
    /// hatch, and one that turns 7 into 5 is not one. Only display precision is enforced.
    ///
    /// Returns null for an untouched or nonsense field, never 0, which would wipe the row.
    fun parse(raw: String, decimals: Int): Double? {
        val cleaned = raw.trim().replace(',', '.')
        val typed = cleaned.toDoubleOrNull() ?: return null
        if (!typed.isFinite() || !(typed * 10.0.pow(decimals)).isFinite()) return null
        return rounded(typed, decimals)
    }

    /// Rounds to what the row can DISPLAY (whole numbers, or one decimal for kilograms),
    /// never to the control's step.
    fun rounded(raw: Double, decimals: Int): Double {
        val scale = 10.0.pow(decimals)
        return Math.round(raw * scale).toDouble() / scale
    }
}

/// Material's `steps` counts stops BETWEEN the ends (0.5…10 in halves has eighteen). A
/// nonsensical step degrades to continuous (0), not a crash.
internal fun sliderSteps(range: ClosedFloatingPointRange<Double>, step: Double): Int {
    if (step <= 0.0) return 0
    val intervals = ((range.endInclusive - range.start) / step).roundToInt()
    return maxOf(0, intervals - 1)
}

@Preview(name = "ValueRow", showBackground = true, widthDp = 380)
@Composable
private fun ValueRowPreview() {
    GetAGripTheme {
        var threshold by remember { mutableStateOf(2.0) }
        var pulls by remember { mutableStateOf(6) }
        var hold by remember { mutableStateOf(10) }
        Column(
            Modifier.padding(20.dp).width(340.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ValueRow(
                title = tr("A pull counts above"),
                value = threshold,
                range = 0.5..10.0,
                unit = tr("kg"),
                limit = 0.5..30.0,
                step = 0.5,
                presets = listOf(1.0, 2.0, 3.0, 5.0),
                decimals = 1,
            ) { threshold = it }
            IntValueRow(
                title = tr("Pulls per side"),
                value = pulls,
                range = 1..40,
                limit = 1..run.nuri.getagrip.engine.SetPlan.repsRange.last,
                control = ValueControl.Stepper,
            ) { pulls = it }
            IntValueRow(
                title = tr("Hold"),
                value = hold,
                range = 1..60,
                unit = tr("s"),
                limit = run.nuri.getagrip.engine.SetPlan.holdRange,
                control = ValueControl.Dial(listOf(1.0, 3.0, 5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 30.0, 45.0, 60.0)),
            ) { hold = it }
        }
    }
}
