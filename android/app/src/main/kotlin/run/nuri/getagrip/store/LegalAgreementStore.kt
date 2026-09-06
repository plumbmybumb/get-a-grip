// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.store

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import run.nuri.getagrip.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.time.Instant

class LegalBundle(val json: JSONObject, val fingerprint: String) {
    val version: String = json.getString("version")
    val screenVersion: Int = json.getInt("screenVersion")
    fun text(key: String, language: String): String = json.getJSONObject("ui").getJSONObject(language).getString(key)
    fun document(kind: String, language: String): JSONObject = json.getJSONObject("documents").getJSONObject(language).getJSONObject(kind)
    fun plainText(kind: String, language: String): String {
        val doc = document(kind, language)
        return buildString {
            appendLine(version); appendLine(); appendLine(doc.getString("title")); appendLine(doc.getString("summary"))
            val sections = doc.getJSONArray("sections")
            for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                appendLine(); appendLine(section.getString("title"))
                val paragraphs = section.getJSONArray("paragraphs")
                for (j in 0 until paragraphs.length()) { appendLine(); appendLine(paragraphs.getString(j)) }
            }
        }
    }
    companion object {
        // Packaged documents cannot change during this app process. Reuse their parsed
        // content and fingerprint across session starts and Settings visits.
        @Volatile private var cached: LegalBundle? = null

        @Synchronized
        fun load(context: Context): LegalBundle {
            cached?.let { return it }
            val bytes = context.assets.open("agreement-2026-09-06-r2.json").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return LegalBundle(JSONObject(bytes.toString(Charsets.UTF_8)), hash).also { cached = it }
        }
    }
}

/** Local Terms acceptance, not proof of identity, a signature or health-data consent. */
class LegalAgreementStore(context: Context, file: File = File(context.filesDir, "legal-acceptances.json")) {
    private val storage = AtomicFile(file)
    var records by mutableStateOf(readRecords())
        private set
    private fun readRecords(): JSONArray = runCatching {
        val records = JSONArray(storage.readFully().toString(Charsets.UTF_8))
        // Valid JSON alone is not a valid receipt. The Settings screen renders these
        // fields directly; partial/restored records must fail closed, not crash there.
        val fields = listOf("version", "fingerprint", "language", "acceptedUTC", "appVersion", "platform")
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            require(fields.all { record.opt(it) is String })
            require(record.opt("screenVersion") is Int && record.getInt("screenVersion") > 0)
        }
        records
    }.getOrElse { JSONArray() }
    fun hasAccepted(bundle: LegalBundle): Boolean = (0 until records.length()).any { index ->
        val record = records.optJSONObject(index)
        record?.optString("version") == bundle.version && record.optString("fingerprint") == bundle.fingerprint &&
            record.optInt("screenVersion") == bundle.screenVersion
    }
    suspend fun accept(bundle: LegalBundle, language: String, now: Instant = Instant.now()) {
        require(language == "en" || language == "fr")
        if (hasAccepted(bundle)) return
        val updated = JSONArray(records.toString()).put(JSONObject().apply {
            put("version", bundle.version); put("fingerprint", bundle.fingerprint); put("language", language)
            put("acceptedUTC", now.toString()); put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            put("platform", "Android"); put("screenVersion", bundle.screenVersion)
        })
        withContext(Dispatchers.IO) {
            val stream = storage.startWrite()
            try { stream.write(updated.toString(2).toByteArray(Charsets.UTF_8)); storage.finishWrite(stream) }
            catch (error: Exception) { storage.failWrite(stream); throw error }
        }
        records = updated
    }
}
