// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import run.nuri.getagrip.ui.share.handleRoutineScanResult
import run.nuri.getagrip.ui.share.routineScanOptions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoutineScannerTests {
    @Test fun validAndForeignCodesReachTheImportValidator() {
        for (text in listOf("getagrip://routine#payload", "https://example.com")) {
            var received: String? = null
            val result = ScanContract().parseResult(Activity.RESULT_OK,
                Intent().putExtra(Intents.Scan.RESULT, text))
            handleRoutineScanResult(result, { received = it }, { error("Unexpected denial") })
            assertEquals(text, received)
        }
    }

    @Test fun cancellationAndEmptyResultsAreQuiet() {
        for (intent in listOf(null, Intent().putExtra(Intents.Scan.RESULT, " "))) {
            var received: String? = null
            handleRoutineScanResult(ScanContract().parseResult(Activity.RESULT_CANCELED, intent),
                { received = it }, { error("Unexpected denial") })
            assertNull(received)
        }
    }

    @Test fun permissionDenialExplainsRecoveryWithoutImporting() {
        var denied = false
        val result = ScanContract().parseResult(Activity.RESULT_CANCELED,
            Intent().putExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true))
        handleRoutineScanResult(result, { error("Should not import") }, { denied = true })
        assertTrue(denied)
    }

    @Test fun scannerIsLocalQrOnlySilentAndDoesNotSaveImages() {
        val intent = routineScanOptions("Scan a routine")
            .createScanIntent(ApplicationProvider.getApplicationContext())
        assertEquals("com.journeyapps.barcodescanner.CaptureActivity", intent.component?.className)
        assertEquals("QR_CODE", intent.getStringExtra(Intents.Scan.FORMATS))
        assertEquals("Scan a routine", intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE))
        assertFalse(intent.getBooleanExtra(Intents.Scan.BEEP_ENABLED, true))
        assertFalse(intent.getBooleanExtra(Intents.Scan.BARCODE_IMAGE_ENABLED, true))
        assertFalse(intent.getBooleanExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, true))
    }
}
