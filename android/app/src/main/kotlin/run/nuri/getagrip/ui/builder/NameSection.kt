// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// **THE DOCUMENT OPENS ON THE NAME** (Nuri, 2026-08-19: "get rid of all the templates and
/// just start with routine name").
///
/// A START FROM row of prefill chips used to sit above this — starter plan, max day, blank,
/// a copy of your first routine. It cost the top of the first screenful to ask a question
/// that has only one honest answer for somebody who already knows what they train, and
/// answering it wrong replaced the document under you. Adding a set is two taps; a routine
/// you did not write is not a shortcut. The seeds themselves live on in `SessionPlan` —
/// nothing but the OFFER went away.
@Composable
fun NameSection(
    name: String,
    modifier: Modifier = Modifier,
    onNameChange: (String) -> Unit,
) {
    val palette = LocalGripPalette.current
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        singleLine = true,
        placeholder = {
            Text(
                tr("Daily no-hangs"),
                style = MaterialTheme.typography.titleMedium,
                color = palette.inkTertiary,
            )
        },
        textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        // **The keyboard's own Done only ends editing.** A commit button hovering over a
        // keyboard reads as "stop typing", and on iOS tapping it once saved the routine
        // mid-thought (Nuri, 2026-08-10). Save stays in the top bar.
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        shape = RoundedCornerShape(Metrics.radiusInner),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.inkPrimary,
            unfocusedTextColor = palette.inkPrimary,
            focusedContainerColor = palette.card,
            unfocusedContainerColor = palette.card,
            focusedBorderColor = palette.graphite,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            cursorColor = palette.graphite,
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = L10n.tr("Routine name") },
    )
}
