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

    /// Hand the remembered device to the OS to connect WHENEVER it is next in range
    /// (`autoConnect`), with no timeout. The only way a link that dropped behind a locked
    /// screen comes back: Android pauses unfiltered scans while the screen is off, so the
    /// old scan-based retry ladder found nothing, spent its five attempts, and gave up —
    /// leaving a session the foreground service was keeping alive waiting on a gauge
    /// nothing was looking for any more.
    awaitInRange,
}

/// Which route the next attempt takes — the rule on its own, so it can be asserted whole.
object AttemptRouting {
    /// - `recovering`: an ESTABLISHED link dropped while the user still wants it. Wait for
    ///   that same device, however long it takes; a session waits for its gauge (a rep never
    ///   ends itself, and neither does the wait for the link).
    /// - `directTriesLeft`: an explicit Connect with a device remembered from earlier gets
    ///   ONE quick direct try, then scans — the user may have picked up a different unit,
    ///   and waiting forever on the old one would never find it.
    fun route(hasRemembered: Boolean, recovering: Boolean, directTriesLeft: Int): AttemptRoute = when {
        !hasRemembered -> AttemptRoute.scan
        recovering -> AttemptRoute.awaitInRange
        directTriesLeft > 0 -> AttemptRoute.direct
        else -> AttemptRoute.scan
    }
}

/// **The remembered-device half of a connected client's reconnect**, shared by
/// `LiveProgressorClient` and `GattGaugeClient` so the two cannot drift apart.
///
/// A scan is the wrong tool for getting BACK a gauge a client has already held: the
/// platform pauses unfiltered scans with the screen off and rations the rest (see
/// `ScanStartBudget`), and the old ladder — five scans, then give up — ended a locked-screen
/// session's wait for its gauge after about a minute. So the device the last ESTABLISHED
/// link ran on is kept for the life of the client, and `AttemptRouting` decides how the next
/// attempt uses it.
///
/// Generic over the device so the rule can be driven without a `BluetoothDevice`.
///
/// **Closing a lost autoConnect link — the one place the two clients differ, on purpose.**
/// Nordic keeps an autoConnect link's GATT open after a loss and reconnects it on its own,
/// alongside the attempt the client is about to make, so `linkLost()` reports such a link
/// and both clients close it. HOW the next attempt is held behind that close follows each
/// client's cancellation discipline, not anything about link loss:
///
/// - `LiveProgressorClient` retires it into its QUARANTINE, as it does every cancelled
///   link: the next attempt waits for the stack's terminal callback (or a 3 s fallback), so
///   no callback from the old link can satisfy the new one. That is the Tindeq machinery
///   earned on real hardware, alongside its serialized queries and tare latch.
/// - `GattGaugeClient` disconnects and lets its ordinary one-second backoff hold the next
///   attempt, which is the guard it uses for every cancellation: a superseded generation's
///   terminal callback is ignored, and the backoff keeps a retry from racing the cancel it
///   just issued. Its gauges are ports of a documented protocol nobody on this project has
///   held, and it deliberately carries none of the Tindeq machinery (see its class comment)
///   — giving the loss path alone a quarantine would make it the one path in that client
///   with different cancellation rules.
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

    /// The link in flight went down. True when it was an autoConnect link, whose GATT the
    /// caller has to close — see the class comment.
    fun linkLost(): Boolean {
        val wasAutoConnect = linkUsesAutoConnect
        linkUsesAutoConnect = false
        return wasAutoConnect
    }

    /// An ESTABLISHED link dropped while the user still wants it: the next attempts wait for
    /// the same device rather than scanning for any.
    fun awaitReturn() {
        recovering = device != null
    }
}
