// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// One step of the builder's inline guide.
///
/// **Never a tooltip or a popup.** A popover installs a full-screen dismiss-catcher, so the
/// first tap anywhere — including on the control the tip is telling you to use — only
/// dismisses the tip and is swallowed, while the surface still lights up under the finger.
/// That is the documented sibling-app bug where a toolbar "+" read as pressed-but-dead on
/// the very first action a new user takes. This card draws IN the layout instead: it
/// intercepts nothing, it can be scrolled past, and it costs one card of height.
///
/// The card walking DOWN the page — Next scrolls to the next section — IS the step-by-step
/// setup, with no modal sequence to re-suffer on every edit.
@Composable
fun CoachCard(
    step: Int,
    total: Int,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val palette = LocalGripPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = palette.card,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                // One spoken sentence: "Step 2 of 5" and the title alone are fragments, and
                // three separate stops is three swipes to read one card.
                Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CapsLabel(tr("STEP %d OF %d", step, total))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoachButton(tr("Next"), FontWeight.SemiBold, palette.graphite, onNext)
                CoachButton(tr("Skip the guide"), FontWeight.Medium, palette.inkTertiary, onSkip)
            }
        }
    }
}

/// A bare text control still has to HIT like a control: the ≥44 dp height is what makes the
/// padding tappable, since the default hit area is the glyphs themselves.
@Composable
private fun CoachButton(
    label: String,
    weight: FontWeight,
    tint: Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = weight,
        color = tint,
        modifier = Modifier
            .heightIn(min = 44.dp)
            // WIDTH is a hit target too, and "Next" is about 34 dp of text. 44 tall and 34 wide
            // is not a legal target, and the padding has to be INSIDE the click region.
            .widthIn(min = 44.dp)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

/// The guide's closing note, drawn above the Save button.
///
/// Deliberately NOT a `CoachCard`: there is no step 6 of 5, and its "next" is the big
/// primary button directly beneath it, so a second Next here would be two buttons competing
/// to be the end of the same sentence.
@Composable
fun CoachClosingCard(modifier: Modifier = Modifier) {
    val palette = LocalGripPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.radiusInner),
        color = palette.card,
    ) {
        Column(
            Modifier.padding(16.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                tr("That's the whole routine."),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            Text(
                tr("You can change any of it later — this same screen is the editor."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.inkSecondary,
            )
        }
    }
}

/// Five cards, in DOCUMENT ORDER — name, rhythm, sets, totals, every day.
///
/// Card 1 was rewritten twice on iOS for the same reason: it described a prefill and then a
/// chooser that the document no longer has. **A card naming a control that is not on screen
/// is indistinguishable from a bug to the person reading it**, so this list changes whenever
/// the document does.
data class CoachScript(val title: String, val message: String)

/// A `get()`, not a stored list: a top-level `val` is initialised once when the file's class
/// loads, so its translated sentences would keep the language they were born in.
val coachScript: List<CoachScript>
    get() = listOf(
        CoachScript(
            L10n.tr("Name it first"),
            L10n.tr("This is what Today calls it. Everything else you build underneath, and nothing is saved until you tap Save."),
        ),
        CoachScript(
            L10n.tr("What every set shares"),
            L10n.tr("The break between sets, how your hands split the work, and whether a rest waits for you to let go. Everything else lives on each set."),
        ),
        CoachScript(
            L10n.tr("Your sets, in order"),
            L10n.tr("Tap a set for its grip, its pulls, its own hold and rest, and its target. Add a set copies the last one, so a uniform routine is quick."),
        ),
        CoachScript(
            L10n.tr("How much on each side"),
            L10n.tr("Each set says how many pulls you do per side. The line underneath adds up your total time under tension per side."),
        ),
        CoachScript(
            L10n.tr("Ritual or whenever"),
            L10n.tr("A daily ritual has a target and reminders. A whenever routine just waits on Today until you feel like it."),
        ),
    )

@Preview(name = "CoachCard", showBackground = true, widthDp = 360)
@Composable
private fun CoachCardPreview() {
    GetAGripTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CoachCard(1, 5, coachScript[0].title, coachScript[0].message, onNext = {}, onSkip = {})
            CoachClosingCard()
        }
    }
}
