// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

enum class TareStartDecision {
    write,
    deferred,
}

/// The tare/start ordering rule belongs to one physical link. Pure, so the link reset is
/// explicit: a latch that outlived its link became a process-wide stream stop, because the
/// releasing ACK was on the retired connection.
///
/// TRANSLATION NOTE: Swift's `mutating` struct becomes a class; nothing here wants value
/// semantics.
class TareIntegrityLatch {
    var isConfirmed: Boolean = true
        private set

    var latestTareID: ULong? = null
        private set

    val startDecision: TareStartDecision
        get() = if (isConfirmed) TareStartDecision.write else TareStartDecision.deferred

    fun tareEnqueued(id: ULong) {
        isConfirmed = false
        latestTareID = id
    }

    /// Returns true only for the current tare's ACK, so an older ACK cannot release a
    /// newer tare's deferred start.
    fun tareAcknowledged(id: ULong): Boolean {
        if (latestTareID != id) return false
        isConfirmed = true
        latestTareID = null
        return true
    }

    fun clearForNewLink() {
        // Queue, ACKs and deferred start die with the link; the latch must too, or every
        // later start is stranded. Mutation-checked on iOS: a no-op here fails
        // `lostLinkResetsTareLatchSoTheNextStartIsWritten`.
        isConfirmed = true
        latestTareID = null
    }
}
