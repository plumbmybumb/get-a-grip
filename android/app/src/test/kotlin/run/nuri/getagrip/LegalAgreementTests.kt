// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import run.nuri.getagrip.store.LegalAgreementStore
import run.nuri.getagrip.store.LegalBundle
import java.io.File
import java.time.Instant
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class LegalAgreementTests {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun bundledDocumentsAreReadableInBothLanguages() {
        val bundle = LegalBundle.load(context)
        assertEquals(64, bundle.fingerprint.length)
        for (language in listOf("en", "fr")) for (kind in listOf("terms", "privacy")) {
            assertContains(bundle.plainText(kind, language), "Oregon")
            assertTrue(bundle.document(kind, language).getJSONArray("sections").length() > 0)
        }
    }
    @Test fun acceptancePersistsWithoutDuplicatingAndChangedTermsRequireAgreement() = runTest {
        val folder = kotlin.io.path.createTempDirectory().toFile()
        try {
            val file = File(folder, "receipt.json")
            val bundle = LegalBundle.load(context)
            val store = LegalAgreementStore(context, file)
            assertFalse(store.hasAccepted(bundle))
            store.accept(bundle, "fr", Instant.ofEpochSecond(100))
            store.accept(bundle, "en")
            val reload = LegalAgreementStore(context, file)
            assertTrue(reload.hasAccepted(bundle))
            assertEquals(1, reload.records.length())
            assertEquals("fr", reload.records.getJSONObject(0).getString("language"))
            assertEquals("1970-01-01T00:01:40Z", reload.records.getJSONObject(0).getString("acceptedUTC"))
            assertFalse(reload.hasAccepted(LegalBundle(bundle.json, "changed")))
            assertFalse(reload.hasAccepted(LegalBundle(JSONObject(bundle.json.toString()).put("version", "future"), bundle.fingerprint)))
            assertFalse(reload.hasAccepted(LegalBundle(JSONObject(bundle.json.toString()).put("screenVersion", bundle.json.getInt("screenVersion") + 1), bundle.fingerprint)))
        } finally { folder.deleteRecursively() }
    }
    @Test fun repeatedVisitsReuseTheImmutableBundledDocuments() {
        assertSame(LegalBundle.load(context), LegalBundle.load(context))
    }

    @Test fun structurallyDamagedReceiptsFailClosedAndCanBeReplaced() = runTest {
        val folder = kotlin.io.path.createTempDirectory().toFile()
        try {
            val file = File(folder, "receipt.json")
            val bundle = LegalBundle.load(context)
            for (damaged in listOf("[null]", "[{}]", "[42]", "[\"receipt\"]")) {
                file.writeText(damaged)
                val store = LegalAgreementStore(context, file)
                assertEquals(0, store.records.length(), damaged)
                assertFalse(store.hasAccepted(bundle), damaged)
                store.accept(bundle, "en")
                assertTrue(LegalAgreementStore(context, file).hasAccepted(bundle))
            }
            val record = JSONObject(file.readText().let { org.json.JSONArray(it).getJSONObject(0).toString() })
            record.put("appVersion", JSONObject.NULL)
            file.writeText(org.json.JSONArray().put(record).toString())
            assertEquals(0, LegalAgreementStore(context, file).records.length())
        } finally { folder.deleteRecursively() }
    }

    @Test fun corruptionAndWriteFailureNeverGrantAcceptance() = runTest {
        val folder = kotlin.io.path.createTempDirectory().toFile()
        try {
            val file = File(folder, "receipt.json").apply { writeText("{broken") }
            val bundle = LegalBundle.load(context)
            assertFalse(LegalAgreementStore(context, file).hasAccepted(bundle))
            val impossible = LegalAgreementStore(context, File(file, "child.json"))
            assertFails { impossible.accept(bundle, "en") }
            assertFalse(impossible.hasAccepted(bundle))
        } finally { folder.deleteRecursively() }
    }
}
