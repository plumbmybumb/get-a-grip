// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/// When the app asks for a Google Play rating: ONCE per device, after the fifth saved
/// session — someone who has come back five times knows what the app is. The twin of iOS's
/// `ReviewRequestPolicy`, with the same numbers and the same once-only flag.
///
/// - **After demonstrated engagement, at a natural stopping point.** Never on first launch
///   or during onboarding, never mid-task: Today, once the fifth session has been saved and
///   the runner has closed, a beat after it is gone (`rememberReviewRequest`), reading the
///   store's save counter so a discarded session never counts.
/// - **Never repeated by this app, never traded for anything.** Asked once
///   (`SettingsStore.reviewRequested`), whatever the answer, and nothing is gated on it.
///   Play's policy forbids incentives exactly as Apple's does.
///
/// TRANSLATION NOTE: iOS shows the system prompt (`requestReview`). Android's equivalent,
/// Play's In-App Review API (`com.google.android.play:review`), is NOT used: it is licensed
/// under the Play Core SDK Terms of Service, not an open-source licence, and it pulls in
/// `com.google.android.gms:play-services-*`, which `scripts/generate-notices.py` refuses as
/// a non-FOSS runtime — the same line that keeps QR scanning on ZXing rather than ML Kit,
/// and that lets the app run without Google Play services at all. So the ask is the app's
/// own one-time dialog, and it only ever OPENS the Play listing — the store's own rating UI
/// does the rating.
object ReviewRequestPolicy {
    /// The fifth session, not the first: enough visits to have an opinion, few enough that a
    /// daily ritual reaches it in the first week.
    const val SESSIONS_BEFORE_ASKING = 5

    fun shouldAsk(hangSessionsLogged: Int, alreadyAsked: Boolean): Boolean =
        !alreadyAsked && hangSessionsLogged >= SESSIONS_BEFORE_ASKING
}

/// The app's own Google Play listing — the permanent "Rate on Google Play" row in Settings
/// and the one-time prompt both open it.
object PlayStoreListing {
    /// The Play Store app's own scheme: opens the listing in the store, where the rating is.
    fun marketUri(packageName: String): String = "market://details?id=$packageName"

    /// The web listing, for a phone with no Play Store app (a de-Googled build, an emulator):
    /// the browser still reaches the page.
    fun webUri(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"

    /// Tries the store, then the web. False when nothing on the phone can open either — the
    /// caller then has nothing to show, which beats a crash.
    ///
    /// The PACKAGE name, not a hard-coded id: a fork's own application id opens its own listing.
    fun open(context: Context): Boolean {
        val id = context.packageName
        for (uri in listOf(marketUri(id), webUri(id))) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
        return false
    }
}
