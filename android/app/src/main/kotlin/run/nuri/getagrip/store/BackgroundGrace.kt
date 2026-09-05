// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

/// What leaving the foreground should do to the gauge's link — the rule on its own, with
/// no store, no coroutine and no radio behind it.
///
/// TRANSLATION NOTE (from `DeviceStore.beginBackgroundGrace` on iOS): the Swift version
/// interleaves the decision with a `beginBackgroundTask` assertion and a sleeping `Task`.
/// Splitting the decision out is what makes it testable here, and the three outcomes are
/// exactly the three branches that file has.
enum class BackgroundGraceAction {
    /// A session is streaming (or nothing is connected): leave the link alone. On Android
    /// what actually keeps such a session alive is `SessionForegroundService`; the grace is
    /// for the OTHER case, an idle gauge left connected by a screen you walked away from.
    none,

    /// **A gauge that cannot stream in the background gets no grace at all.** For a
    /// broadcast scale the "link" is an unfiltered all-matches scan — the most power-hungry
    /// BLE mode there is — and the OS silences it the moment we background whatever we ask
    /// for. Reacquiring costs about a second of rescan, so the grace was buying nothing on
    /// either side of the trade. The caller arms the scan to stand itself back up on the
    /// way in.
    disconnectNow,

    /// The 45 s window. Firing at once could not tell a two-second "hey Siri" from a phone
    /// put in a bag, and charged both a 5–6 s reconnect; that churn is what Nuri reported
    /// as "weird Bluetooth drops".
    scheduleDisconnect,
}

object BackgroundGracePolicy {

    /// 45 seconds, the same window iOS opens. Long enough that an app switch is free, short
    /// enough that a phone in a bag does not leave the gauge awake for the ten minutes it
    /// takes to self-sleep after a disconnect.
    const val graceSeconds = 45L

    fun onLeavingForeground(
        isConnected: Boolean,
        isBusy: Boolean,
        isStreaming: Boolean,
        sustainsBackgroundStreaming: Boolean,
    ): BackgroundGraceAction {
        // `isBusy` is in the guard on purpose — scanning and connecting are exactly the
        // states this has to catch, because by the time anything looks at a broadcast
        // gauge's link the scan has usually already re-armed itself.
        if (!sustainsBackgroundStreaming && (isConnected || isBusy)) {
            return BackgroundGraceAction.disconnectNow
        }
        if (isConnected && !isStreaming) return BackgroundGraceAction.scheduleDisconnect
        return BackgroundGraceAction.none
    }
}
