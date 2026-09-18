// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.readablePageWidth

/// The note Frez asks every app on its Dyno API to show ONCE, the first time someone
/// picks the Dyno. A letter, so it reads as one: the words are Donghyun Kim's, verbatim,
/// and stay in English in every locale — only the title and the button are ours.
///
/// Next is the only way out. Swiping or backing out would leave the selection half-made,
/// and the note is the maker's one ask for lending its calibration service; after Next the
/// flag is persisted and the sheet never appears again on this device.
///
/// TRANSLATION NOTE: iOS presents this as a `.sheet` with `interactiveDismissDisabled` and
/// the `.large` detent. The Android twin of "a full-height presentation you cannot swipe
/// away" is a `Dialog` with `usePlatformDefaultWidth = false` — the same shape the legal
/// documents use — because a `ModalBottomSheet` is dismissible by gesture by construction
/// and refusing that gesture fights the component rather than using it.
@Composable
fun FrezIntroSheet(onNext: () -> Unit) {
    val palette = LocalGripPalette.current

    // Verbatim, in every locale: the catalog carries these four paragraphs with their
    // English as the French, which is the maker's ask and not an untranslated string.
    val paragraphs = listOf(
        tr("Hello, I’m Donghyun Kim, founder of Frez."),
        tr(
            "We keep our hardware margins low because we believe everyone should have " +
                "access to their own data. Frez Pro helps us develop new features and " +
                "provide reliable devices. In fact, Frez Dyno was made possible by our " +
                "early Pro subscribers.",
        ),
        tr(
            "If you enjoy using Frez Dyno and would like to support the future of Frez, " +
                "please consider trying Frez Pro.",
        ),
        tr("I hope Frez will be with you for many years to come."),
    )

    Dialog(
        // Nothing to answer: there is no dismissal this can be called for.
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        InstrumentSurface(
            modifier = Modifier.fillMaxSize(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = palette.field,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .safeDrawingPadding()
                    .fillMaxSize()
                    .readablePageWidth()
                    .padding(horizontal = Metrics.hPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tr("A note from Frez"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    modifier = Modifier
                        .widthIn(max = Metrics.maxContentWidth)
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    InstrumentSurface(
                        modifier = Modifier
                            .widthIn(max = Metrics.maxContentWidth)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            for (paragraph in paragraphs) {
                                Text(
                                    paragraph,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.inkPrimary,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tr("Thank you,"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.inkPrimary,
                                )
                                Text(
                                    tr("Donghyun Kim"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.inkPrimary,
                                )
                            }
                        }
                    }
                }
                PrimaryButton(
                    tr("Next"),
                    modifier = Modifier
                        .widthIn(max = Metrics.maxContentWidth)
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .testTag("frez.intro.next"),
                    onClick = onNext,
                )
            }
        }
    }
}
