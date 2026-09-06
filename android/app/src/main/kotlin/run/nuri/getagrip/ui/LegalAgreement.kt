// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import run.nuri.getagrip.store.LegalAgreementStore
import run.nuri.getagrip.store.LegalBundle
import run.nuri.getagrip.ui.theme.LocalGripPalette

@Composable
fun LegalSettingsContent() {
    val context = LocalContext.current
    val language = if (LocalConfiguration.current.locales[0]?.language == "fr") "fr" else "en"
    val bundle = remember { runCatching { LegalBundle.load(context) }.getOrNull() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val store = remember { LegalAgreementStore(context) }
    // Keep genuine historical records available without requesting new acceptance.
    val keys = if (store.records.length() > 0) listOf("terms", "privacy", "receipt") else listOf("terms", "privacy")
    keys.forEach { key ->
        TextButton(onClick = { selected = key }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(bundle?.text(key, language) ?: if (language == "fr") "Documents juridiques" else "Legal documents", Modifier.weight(1f))
        }
    }
    selected?.let { kind ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LegalPanel(bundle, store, kind, language, onClose = { selected = null })
        }
    }
}

@Composable
private fun LegalPanel(bundle: LegalBundle?, store: LegalAgreementStore, kind: String,
                       language: String, onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    if (bundle == null) {
        Surface(color = palette.field, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                Text(if (language == "fr") "Documents indisponibles" else "Documents unavailable")
                TextButton(onClick = onClose) { Text(if (language == "fr") "Retour" else "Go back") }
            }
        }
        return
    }
    val text = { key: String -> bundle.text(key, language) }
    fun share(value: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        try { context.startActivity(Intent.createChooser(send, text("share"))) }
        catch (_: android.content.ActivityNotFoundException) { failed = true }
    }
    Surface(color = palette.field, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text(text("done")) }
                Text(text(kind), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(8.dp))
            }
            key(kind, language) {
                Column(Modifier.widthIn(max = 640.dp).weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (failed) Text(if (language == "fr") "Le partage est indisponible." else "Sharing is unavailable.", color = palette.alarm)
                    if (kind == "receipt") {
                        Text(text("recordNote"), color = palette.inkSecondary)
                        if (store.records.length() == 0) Text(text("noReceipt"))
                        else {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    for (i in 0 until store.records.length()) {
                                        val record = store.records.getJSONObject(i)
                                        Text("${text("version")}: ${record.getString("version")}\n${text("language")}: ${record.getString("language")}\n${text("date")}: ${record.getString("acceptedUTC")}\n${text("appVersion")}: ${record.getString("appVersion")}")
                                    }
                                }
                            }
                            OutlinedButton(onClick = { share(store.records.toString(2)) }) { Text(text("share")) }
                        }
                    } else {
                        val doc = bundle.document(kind, language)
                        Text("${text("version")}: ${bundle.version}", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text(doc.getString("summary"), style = MaterialTheme.typography.titleMedium)
                                val sections = doc.getJSONArray("sections")
                                for (i in 0 until sections.length()) {
                                    val section = sections.getJSONObject(i)
                                    Text(section.getString("title"), style = MaterialTheme.typography.titleMedium)
                                    val paragraphs = section.getJSONArray("paragraphs")
                                    for (j in 0 until paragraphs.length()) Text(paragraphs.getString(j))
                                }
                            }
                        }
                        OutlinedButton(onClick = { share(bundle.plainText(kind, language)) }) { Text(text("share")) }
                    }
                }
            }
        }
    }
}
