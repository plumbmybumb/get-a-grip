// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import run.nuri.getagrip.store.LegalAgreementStore
import run.nuri.getagrip.store.LegalBundle
import run.nuri.getagrip.ui.theme.LocalGripPalette

@Composable
fun TrainingAgreementGate(onCancel: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val bundle = remember { runCatching { LegalBundle.load(context) }.getOrNull() }
    val store = remember { LegalAgreementStore(context) }
    if (bundle != null && store.hasAccepted(bundle)) content()
    else LegalPanel(bundle = bundle, store = store, agreement = true, onClose = onCancel)
}

@Composable
fun LegalSettingsContent() {
    val context = LocalContext.current
    val language = if (LocalConfiguration.current.locales[0]?.language == "fr") "fr" else "en"
    val bundle = remember { runCatching { LegalBundle.load(context) }.getOrNull() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val keys = listOf("terms", "privacy", "receipt")
    keys.forEach { key ->
        TextButton(onClick = { selected = key }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(bundle?.text(key, language) ?: if (language == "fr") "Documents juridiques" else "Legal documents", Modifier.weight(1f))
        }
    }
    selected?.let { kind ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val store = remember { LegalAgreementStore(context) }
            LegalPanel(bundle, store, agreement = false, initialKind = kind, documentLanguage = language, onClose = { selected = null })
        }
    }
}

@Composable
private fun LegalPanel(bundle: LegalBundle?, store: LegalAgreementStore, agreement: Boolean,
                       initialKind: String? = null, documentLanguage: String? = null, onClose: () -> Unit) {
    val configuredLanguage = if (LocalConfiguration.current.locales[0]?.language == "fr") "fr" else "en"
    val language = documentLanguage ?: configuredLanguage
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var checked by rememberSaveable(language) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun close() { if (agreement && kind != null) kind = null else onClose() }
    BackHandler { close() }
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
                TextButton(onClick = { close() }) { Text(text(if (agreement && kind == null) "cancel" else "done")) }
                Text(text(kind ?: "heading"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(8.dp))
            }
            key(kind, language) {
                Column(Modifier.widthIn(max = 640.dp).weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (kind == null) {
                        Text(text("intro"), style = MaterialTheme.typography.titleLarge)
                        Text(text("risk"), color = palette.inkSecondary)
                        Text(text("choice"), style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                        listOf("terms", "privacy").forEach { doc ->
                            OutlinedButton(onClick = { kind = doc }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(doc)) }
                        }
                        Text("${text("version")}: ${bundle.version}", style = MaterialTheme.typography.bodySmall)
                        Row(modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it }).testTag("legal.agree"), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(text("checkbox"), Modifier.weight(1f))
                        }
                        if (failed) Text(text("error"), color = palette.alarm)
                        Button(onClick = {
                            saving = true
                            scope.launch {
                                try { store.accept(bundle, language) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failed = true }
                                finally { saving = false }
                            }
                        }, enabled = checked && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("legal.continue")) {
                            Text(text("accept"))
                        }
                        Text(text("access"), style = MaterialTheme.typography.bodySmall, color = palette.inkSecondary)
                    } else if (kind == "receipt") {
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
                        val doc = bundle.document(kind!!, language)
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
                        OutlinedButton(onClick = { share(bundle.plainText(kind!!, language)) }) { Text(text("share")) }
                    }
                }
            }
        }
    }
}
