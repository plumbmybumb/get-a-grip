// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.units.WeightUnit
import run.nuri.getagrip.ui.units.WeightUnits

/// Body weight as a number you TYPE, never a slider (Nuri, 2026-09-25): a slider is a poor
/// way to state an exact personal number, and a track whose far end reads as a verdict on
/// your body is worse. Empty until entered, with no invented default; in the user's weight
/// unit; the decimal keypad.
///
/// `kilograms` null = not entered. The entry commits when the field loses focus or on the
/// keyboard's Done. An empty or unreadable entry keeps what was there.
@Composable
fun BodyWeightField(
    kilograms: Double?,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    title: String = tr("Body weight"),
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
) {
    val palette = LocalGripPalette.current
    val unit = WeightUnits.current
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(BodyWeightEntry.display(kilograms, unit)) }
    val latestKilograms by rememberUpdatedState(kilograms)

    // Follow the stored value (and the unit) while not being edited.
    LaunchedEffect(kilograms, unit) { if (!focused) text = BodyWeightEntry.display(kilograms, unit) }

    fun commit() {
        val kg = BodyWeightEntry.parse(text, unit)
        if (kg == null) {
            text = BodyWeightEntry.display(latestKilograms, unit)
            return
        }
        onChange(kg)
        text = BodyWeightEntry.display(kg, unit)
    }

    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = titleStyle, color = palette.inkPrimary, modifier = Modifier.weight(1f))
        val numberStyle = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum",
            color = palette.inkPrimary, textAlign = TextAlign.End,
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(6) },
            singleLine = true,
            textStyle = numberStyle,
            cursorBrush = SolidColor(palette.graphite),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier
                .widthIn(min = 60.dp, max = 110.dp)
                .testTag("bodyWeight.field")
                .semantics { contentDescription = title }
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text("—", style = numberStyle.copy(color = palette.inkTertiary),
                        modifier = Modifier.fillMaxWidth())
                }
                inner()
            },
        )
        Text(unit.symbol, style = MaterialTheme.typography.bodyMedium, color = palette.inkTertiary)
    }
}

/// The field's arithmetic, pure so it can be tested without a screen.
object BodyWeightEntry {
    /// Stored kilograms are clamped to what a person can weigh; a typo is not a weight.
    val limitKg: ClosedFloatingPointRange<Double> = 25.0..250.0

    /// Typed text in `unit` → kilograms, clamped; null for empty, unreadable, zero or
    /// negative. Either decimal separator is accepted, whatever the phone's locale.
    fun parse(text: String, unit: WeightUnit): Double? {
        val typed = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!typed.isFinite() || typed <= 0) return null
        return unit.toKg(typed).coerceIn(limitKg)
    }

    /// Kilograms → the text shown in `unit`: at most one decimal, empty when not entered.
    fun display(kilograms: Double?, unit: WeightUnit): String {
        val kg = kilograms ?: return ""
        val shown = unit.fromKg(kg)
        val rounded = Math.round(shown * 10) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString()
        else WeightUnits.formatDisplayed(rounded, 1)
    }
}
