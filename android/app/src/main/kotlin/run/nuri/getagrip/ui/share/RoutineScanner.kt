// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.share

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import run.nuri.getagrip.ui.l10n.tr

/** A bundled, offline QR scanner. Camera permission is requested only on Scan.
 * Foreign QR text goes to the existing import validator for a useful explanation;
 * cancelling is quiet, and denying permission leaves the rest of the app usable.
 */
@Composable
fun rememberRoutineScanner(
    onScanned: (String) -> Unit,
    onUnavailable: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scanned by rememberUpdatedState(onScanned)
    val unavailable by rememberUpdatedState(onUnavailable)
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        handleRoutineScanResult(result, scanned) {
            unavailable(context.tr("Allow camera access in Settings to scan a routine. You can still open a shared routine link."))
        }
    }
    return {
        try {
            launcher.launch(routineScanOptions(context.tr("Point your camera at a Get a Grip routine QR code.")))
        } catch (_: android.content.ActivityNotFoundException) {
            unavailable(context.tr("Couldn't open the camera. Try opening the shared routine link instead."))
        } catch (_: SecurityException) {
            unavailable(context.tr("Allow camera access in Settings to scan a routine. You can still open a shared routine link."))
        }
    }
}

internal fun routineScanOptions(prompt: String): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt(prompt)
    .setOrientationLocked(false)
    .setBeepEnabled(false)
    .setBarcodeImageEnabled(false)
    .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false)

internal fun handleRoutineScanResult(
    result: ScanIntentResult,
    onScanned: (String) -> Unit,
    onPermissionDenied: () -> Unit,
) {
    if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
        onPermissionDenied()
    } else {
        result.contents?.takeIf { it.isNotBlank() }?.let(onScanned)
    }
}
