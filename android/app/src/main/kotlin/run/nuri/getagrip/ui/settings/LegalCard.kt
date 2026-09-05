// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

@Composable
internal fun LegalCard() {
    val palette = LocalGripPalette.current
    val uriHandler = LocalUriHandler.current
    val french = LocalConfiguration.current.locales[0]?.language == "fr"
    var unavailableUrl by remember { mutableStateOf<String?>(null) }
    fun open(path: String) {
        val url = "https://nuri.run/getagrip/$path" + if (french) "/fr" else ""
        // Devices without a browser should still be able to read/copy the address.
        try { uriHandler.openUri(url) } catch (_: IllegalArgumentException) { unavailableUrl = url }
    }

    InstrumentSurface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsLabel(tr("Privacy and terms"))
            TextButton(onClick = { open("privacy") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(tr("Privacy policy"), modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { open("terms") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(tr("Terms of use"), modifier = Modifier.weight(1f))
            }
            Text(tr("Get a Grip is not a medical device and does not diagnose, treat, cure or prevent any medical condition. Consult a healthcare professional for medical advice."),
                style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
        }
    }

    unavailableUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { unavailableUrl = null },
            title = { Text(tr("Open in a browser")) },
            text = { SelectionContainer { Text(url) } },
            confirmButton = { TextButton(onClick = { unavailableUrl = null }) { Text(tr("Done")) } },
        )
    }
}
