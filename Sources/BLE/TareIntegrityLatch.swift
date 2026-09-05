// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

enum TareStartDecision: Sendable, Equatable {
    case write
    case deferred
}

/// The tare/start ordering rule belongs to one physical link. Keeping this decision pure
/// makes the link reset explicit: preserving the latch after the link died turned the
/// safety stop into a process-wide stream stop because the ACK that could release it was
/// on the retired connection.
struct TareIntegrityLatch: Sendable {
    private(set) var isConfirmed = true
    private(set) var latestTareID: UInt64?

    var startDecision: TareStartDecision {
        isConfirmed ? .write : .deferred
    }

    mutating func tareEnqueued(id: UInt64) {
        isConfirmed = false
        latestTareID = id
    }

    /// Returns true only for the current tare's ACK, so an older ACK cannot release a
    /// newer tare's deferred start.
    mutating func tareAcknowledged(id: UInt64) -> Bool {
        guard latestTareID == id else { return false }
        isConfirmed = true
        latestTareID = nil
        return true
    }

    mutating func clearForNewLink() {
        // The queue, ACKs and deferred start all die with the physical link; this latch
        // must die with them or every later start remains stranded for the process life.
        //
        // Mutation-checked 2026-08-16: making this a no-op (main's behaviour, where the
        // latch survived the link) fails `testLostLinkResetsTareLatchSoTheNextStartIsWritten`
        // with no `startWeightMeasurement` ever written — which is the reported bug.
        isConfirmed = true
        latestTareID = nil
    }
}
