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
import androidx.compose.foundation.layout.widthIn
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

    /// A small integer you want EXACTLY, nudged around a common one: pulls per side. That
    /// is the HIG's own description of when a stepper is the control, and four chips could
    /// never have held 4, which is what a max protocol asks for.
    data object Stepper : ValueControl

    /// A `DialTrack` over the given ladder — evenly-spaced detents, one per value it can
    /// produce. The right control for a quantity that is EXACT and drawn from a handful of
    /// real-world numbers, which is nearly every quantity in a routine.
    data class Dial(val ladder: List<Double>) : ValueControl

    /// When the presets genuinely are the vocabulary.
    data object None : ValueControl
}

/// A number you can drag, tap or type — the app's control for every quantity.
///
/// Three ways in: drag the dial (or slider) for the coarse move, tap a preset for the
/// values you use most (kept to four; more is a menu again), and **tap the number to type
/// an exact one**, which is the escape hatch a real range genuinely needs.
@Composable
fun ValueRow(
    title: String,
    value: Double,
    /// The range the SLIDER spans — the values you actually reach for, not what the column
    /// can store. Handing the slider the storage clamp (rest tolerates 600 s) puts a 20 s
    /// rest at 3 % of the track: the whole useful span squeezed into a few pixels, and
    /// every drag a wild jump.
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier,
    unit: String = "",
    /// The hard clamp a TYPED value is held to, when the storage range is wider than
    /// anything worth dragging to. Defaults to the slider's range.
    limit: ClosedFloatingPointRange<Double>? = null,
    step: Double = 1.0,
    presets: List<Double> = emptyList(),
    decimals: Int = 0,
    /// Shown under the row when the value deserves a consequence ("= 1:00 under tension
    /// per side", "Below this, the clock stops").
    caption: String? = null,
    control: ValueControl = ValueControl.Slider,
    onValueChange: (Double) -> Unit,
) {
    val palette = LocalGripPalette.current
    val bounds = limit ?: range

    /// Whether the number has become a field. Two changes per edit — the tap and the
    /// commit — so it stays here; the DRAFT STRING, which changes on every keypress, does
    /// not. See `ValueField`.
    var isTyping by remember { mutableStateOf(false) }

    /// Only when there is a slider to fall back on. Where the presets ARE the control,
    /// hiding them would leave tap-to-type as the single way to change a value — the one
    /// path needing the most dexterity and the most prior knowledge of what to enter.
    val hidesPresets = control == ValueControl.Slider && LocalDensity.current.fontScale >= 1.5f

    /// Whether a full-width track draws UNDER the title row. It decides the row's own
    /// spacing: the gap exists to keep a draggable strip clear of the numbers above it,
    /// and a stepper sits IN that row rather than under it.
    val hasTrack = control is ValueControl.Slider || control is ValueControl.Dial

    // Each dial draws a whole ladder on every landing. Reuse its locale formatter
    // rather than constructing one for every label on every pointer update.
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
                    // `null` means the field was left as it was found — tapping the number
                    // and changing your mind must not zero it.
                    if (typed != null) {
                        val next = typed.coerceIn(bounds.start, bounds.endInclusive)
                        if (next != value) onValueChange(next)
                    }
                    isTyping = false
                }
            } else {
                // The value doubles as the button that lets you type it. It reads as a
                // value first and a control second, which is the right emphasis — most of
                // the time you are reading it, not editing it.
                Row(
                    Modifier
                        // 44 both ways — a short unitless value is a one-glyph label with
                        // 20 dp of padding, well under the house floor, sandwiched
                        // between two correctly-sized steppers.
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
                    // Clamps only what the SLIDER sees. A typed 90 s hold stays 90 s in
                    // the model and on the face; the thumb just parks at the end of its
                    // track rather than being handed an out-of-range value.
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
                    // The LADDER, and nothing but the ladder — filtered only to what this
                    // row can legally hold. Splicing the current value in as a ninth stop
                    // re-spaced the other eight under your finger; see `DialTrack.values`.
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
    /// The stepper's increment, when it differs from the slider's. A hold slider moves in
    /// fives because that is how you think about it; a stepper that also moved in fives
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
/// **The draft string lives here rather than on `ValueRow` because the house rule is that
/// high-frequency state belongs in a leaf.** With it on the row, every character re-ran the
/// row's whole body: the dial and its eleven detents plus eleven formatted scale labels,
/// the preset capsules and the caption — none of which the text you are typing can touch.
/// Same fix the 80 Hz force readout got, applied to the one place in the app where the
/// "sensor" is somebody's thumb.
///
/// **It opens EMPTY, with the current value as its placeholder.** Pre-filling puts the
/// caret after the existing digits, so typing 22 over a 15 produced "1522". Leaving the
/// field COMMITS rather than discarding — a typed number that silently vanishes is worse
/// than one clamped into range — and an untouched field reports null, so tapping a number
/// and changing your mind cannot zero it. Clamping stays with the CALLER, which is the only
/// place that knows `limit` versus `range`.
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
    /// The first `onFocusChanged` fires with `false` before the requester runs, and an
    /// unguarded commit there would close the field on the frame it opened.
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
                // Tapping elsewhere COMMITS rather than discarding — a typed number that
                // silently vanishes is worse than one clamped into range. An empty draft
                // parses to null, so the same path also just closes the field.
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

/// The typed-number contract, with no view around it — lifted out so every rule below is
/// asserted in a JVM test rather than by typing into a phone.
object ValueFieldParser {

    /// Accepts a comma as well as a point: the same phone reads "2,5" in French and "2.5"
    /// in English, and a keypad does not care which one you were taught.
    ///
    /// **Deliberately NOT snapped to the row's `step`.** The dial lands on the ladder so it
    /// is easy to reach a round number by dragging; typing is the escape hatch for
    /// everything else, and a field that silently turns 7 into 5 is not an escape hatch.
    /// Only the display precision is enforced.
    ///
    /// Returns null for an untouched or nonsense field, never 0 — a commit that read an
    /// empty string as zero would silently wipe the row.
    fun parse(raw: String, decimals: Int): Double? {
        val cleaned = raw.trim().replace(',', '.')
        val typed = cleaned.toDoubleOrNull() ?: return null
        if (!typed.isFinite()) return null
        return rounded(typed, decimals)
    }

    /// Rounds to what the row can DISPLAY (whole numbers, or one decimal for kilograms),
    /// never to the control's step.
    fun rounded(raw: Double, decimals: Int): Double {
        val scale = 10.0.pow(decimals)
        return Math.round(raw * scale).toDouble() / scale
    }
}

/// Material's `steps` counts the stops BETWEEN the ends, so a 0.5…10 range in halves has
/// eighteen of them. Zero means a continuous slider, which is what a nonsensical step
/// should degrade to rather than a crash.
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
                caption = tr("Below this, the clock stops."),
            ) { threshold = it }
            IntValueRow(
                title = tr("Pulls per side"),
                value = pulls,
                range = 1..12,
                limit = 1..20,
                control = ValueControl.Stepper,
                caption = L10n.tr("= %s", L10n.tr("%s under tension per side", "1:00")),
            ) { pulls = it }
            IntValueRow(
                title = tr("Hold"),
                value = hold,
                range = 1..60,
                unit = tr("s"),
                limit = 3..120,
                control = ValueControl.Dial(listOf(1.0, 3.0, 5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 30.0, 45.0, 60.0)),
            ) { hold = it }
        }
    }
}
