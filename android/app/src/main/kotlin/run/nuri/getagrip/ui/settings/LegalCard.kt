// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.ui.LegalSettingsContent
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.Metrics

@Composable
internal fun LegalCard() {
    InstrumentSurface(shape = RoundedCornerShape(Metrics.radiusCard), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsLabel(tr("Privacy and terms"))
            LegalSettingsContent()
        }
    }
}
