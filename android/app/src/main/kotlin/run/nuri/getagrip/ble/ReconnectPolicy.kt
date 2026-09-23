// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

/// How the next connection attempt reaches the gauge.
enum class AttemptRoute {
    /// Look for it: the first link ever, or a remembered one that did not answer.
    scan,

    /// Connect straight to the remembered device, with the usual short timeout. No scan
    /// is involved, so neither the quota nor a locked screen can stop it.
    direct,

    /// Hand the remembered device to the OS to connect WHENEVER next in range
    /// (`autoConnect`), with no timeout. The only way a link dropped behind a locked screen
    /// comes back: Android pauses unfiltered scans with the screen off, so the old scan
    /// ladder spent its five attempts and gave up on a session the foreground service was
    /// keeping alive.
    awaitInRange,
}

/// Which route the next attempt takes — the rule on its own, so it can be asserted whole.
object AttemptRouting {
    /// - `recovering`: an ESTABLISHED link dropped while still wanted. Wait for that device
    ///   however long it takes (a rep never ends itself, nor does the wait for the link).
    /// - `directTriesLeft`: an explicit Connect with a remembered device gets ONE quick
    ///   direct try, then scans — the user may have picked up a different unit.
    fun route(hasRemembered: Boolean, recovering: Boolean, directTriesLeft: Int): AttemptRoute = when {
        !hasRemembered -> AttemptRoute.scan
        recovering -> AttemptRoute.awaitInRange
        directTriesLeft > 0 -> AttemptRoute.direct
        else -> AttemptRoute.scan
    }
}

/// **The remembered-device half of a connected client's reconnect**, shared by
/// `LiveProgressorClient` and `GattGaugeClient` so they cannot drift.
///
/// A scan is the wrong tool for getting BACK a known gauge: unfiltered scans pause with the
/// screen off and the rest are rationed (`ScanStartBudget`), so five scans then give-up
/// ended a locked-screen session's wait after about a minute. The device of the last
/// ESTABLISHED link is kept for the client's life, and `AttemptRouting` decides how to use
/// it. Generic so the rule runs without a `BluetoothDevice`.
///
/// **Closing a lost autoConnect link — where the two clients differ, on purpose.** Nordic
/// keeps a lost autoConnect GATT open and reconnects it alongside the client's next
/// attempt, so `linkLost()` reports it and both clients close it. How the next attempt
/// waits follows each client's cancellation discipline:
///
/// - `LiveProgressorClient` QUARANTINES it like every cancelled link: the next attempt
///   waits for the terminal callback (or a 3 s fallback), so no old callback satisfies the
///   new link — Tindeq machinery earned on hardware.
/// - `GattGaugeClient` disconnects and lets its one-second backoff hold the next attempt,
///   its guard for every cancellation (superseded generations are ignored). It carries none
///   of the Tindeq machinery by design, and a quarantine on the loss path alone would give
///   it two cancellation rules.
class RememberedGauge<D : Any> {
    /// The device the last ESTABLISHED link ran on.
    var device: D? = null
        private set

    /// An established link dropped and the user still wants it: wait for that device.
    private var recovering = false

    /// A quick direct try at `device` before an explicit Connect scans.
    private var directTriesLeft = 0

    /// Whether the link in flight was handed to the OS with `autoConnect`.
    private var linkUsesAutoConnect = false

    /// An explicit Connect: one quick direct try at a remembered device, then scans.
    fun connectRequested() {
        recovering = false
        directTriesLeft = if (device != null) 1 else 0
    }

    /// The user let go of the link: nothing is owed to the remembered device any more.
    fun released() {
        recovering = false
        directTriesLeft = 0
    }

    /// Which route the next attempt takes. Choosing `direct` spends the direct try.
    fun route(): AttemptRoute {
        val next = AttemptRouting.route(device != null, recovering, directTriesLeft)
        if (next == AttemptRoute.direct) directTriesLeft -= 1
        return next
    }

    /// An attempt is going out; `autoConnect` is whether it waits for the device in range.
    fun attaching(autoConnect: Boolean) {
        linkUsesAutoConnect = autoConnect
    }

    /// The link is up and streaming-ready: this is the device to come back to.
    fun established(device: D) {
        this.device = device
        recovering = false
        directTriesLeft = 0
    }

    /// The link in flight went down. True for an autoConnect link, whose GATT the caller
    /// must close — see the class comment.
    fun linkLost(): Boolean {
        val wasAutoConnect = linkUsesAutoConnect
        linkUsesAutoConnect = false
        return wasAutoConnect
    }

    /// An ESTABLISHED link dropped while still wanted: next attempts wait for the same
    /// device instead of scanning.
    fun awaitReturn() {
        recovering = device != null
    }
}
