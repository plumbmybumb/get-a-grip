// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Locale
import run.nuri.getagrip.ui.l10n.tr

internal const val SUPPORT_ADDRESS = "support@nuri.run"

/** A draft only. The mail app lets the person edit, cancel, or send it. */
internal data class SupportDraft(val subject: String, val body: String) {
    fun intent(): Intent = Intent(Intent.ACTION_SENDTO,
        Uri.parse("mailto:$SUPPORT_ADDRESS?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_ADDRESS))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)

    fun open(context: Context): Boolean = try {
        context.startActivity(intent())
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun copyText(): String = "$SUPPORT_ADDRESS\n$subject\n$body"
}

internal fun supportDraft(context: Context, bug: Boolean, gauge: String, diagnostics: String? = null): SupportDraft {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val body = buildString {
        append("\n\n------------------------\n")
        append("Get a Grip ${info?.versionName ?: "unknown"} (${info?.longVersionCode ?: "unknown"})\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Gauge: $gauge\nLocale: ${Locale.getDefault().toLanguageTag()}")
        // Diagnostics are opt-in, and a feature request never includes them.
        if (bug && !diagnostics.isNullOrBlank()) append("\n\n${context.tr("Diagnostics")}\n$diagnostics")
    }
    return SupportDraft(context.tr(if (bug) "Get a Grip — Bug report" else "Get a Grip — Feature request"), body)
}
