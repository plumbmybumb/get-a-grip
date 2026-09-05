// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

enum class TareStartDecision {
    write,
    deferred,
}

/// The tare/start ordering rule belongs to one physical link. Keeping this decision pure
/// makes the link reset explicit: preserving the latch after the link died turned the
/// safety stop into a process-wide stream stop because the ACK that could release it was
/// on the retired connection.
///
/// TRANSLATION NOTE: Swift's `struct` with `mutating func`s becomes a class, per the
/// house type mapping — the caller drives it and nothing here wants value semantics.
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
        // The queue, ACKs and deferred start all die with the physical link; this latch
        // must die with them or every later start remains stranded for the process life.
        //
        // Mutation-checked 2026-08-16 on iOS: making this a no-op (the behaviour where the
        // latch survived the link) fails `lostLinkResetsTareLatchSoTheNextStartIsWritten`
        // with no `startWeightMeasurement` ever written — which is the reported bug.
        isConfirmed = true
        latestTareID = null
    }
}
