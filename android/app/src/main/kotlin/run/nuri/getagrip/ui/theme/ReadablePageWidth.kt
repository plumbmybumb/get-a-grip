// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.theme

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Center the whole scrolling document, so its headings, cards and actions keep one
 * shared margin on tablets. Apply before the document's horizontal content padding. */
fun Modifier.readablePageWidth(): Modifier =
    wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = Metrics.maxContentWidth + Metrics.hPadding * 2)
