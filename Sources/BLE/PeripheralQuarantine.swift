// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The bookkeeping behind `LiveProgressorClient`'s deliberate-cancellation quarantine,
/// generic over the peripheral so it can be driven without CoreBluetooth — a
/// `CBPeripheral` cannot be constructed in a test.
///
/// A peripheral the app cancelled on purpose is HELD until CoreBluetooth delivers its
/// terminal callback, because reusing it earlier lets a late callback from the old
/// generation satisfy the new attempt. While one is held no new attempt starts. That
/// makes the quarantine a gate, and a gate needs every way out spelled out, or the gauge
/// is unreachable until relaunch:
/// - **the terminal callback** for the peripheral held (`releaseOnTerminal`);
/// - **the radio going away** (`drop`): CoreBluetooth delivers no per-peripheral
///   callback after power-off, a reset or lost authorization — the state change IS the
///   disconnect — so a peripheral retired then would be held forever;
/// - **a safety timeout** (`releaseOnTimeout`), for a terminal callback that never
///   comes for any other reason. The ticket makes a timer armed for an earlier hold
///   unable to release a later one.
struct PeripheralQuarantine<Peripheral: AnyObject> {
    private(set) var held: Peripheral?
    /// Advances with every hold; the safety timer carries the value it was armed with.
    private(set) var ticket: UInt64 = 0

    var isHolding: Bool { held != nil }

    /// Holds `peripheral`, returning the ticket to arm the safety release with — or nil
    /// when a hold is already in place. There can only be one gauge, and replacing an
    /// earlier quarantine would free the peripheral whose callback is still owed.
    mutating func hold(_ peripheral: Peripheral) -> UInt64? {
        guard held == nil else { return nil }
        held = peripheral
        ticket &+= 1
        return ticket
    }

    /// The terminal callback arrived. Releases only the peripheral actually held.
    mutating func releaseOnTerminal(_ peripheral: Peripheral) -> Peripheral? {
        guard let held, held === peripheral else { return nil }
        self.held = nil
        return held
    }

    /// The safety timer fired. Releases only the hold that armed it.
    mutating func releaseOnTimeout(ticket: UInt64) -> Peripheral? {
        guard let held, self.ticket == ticket else { return nil }
        self.held = nil
        return held
    }

    /// The radio went away: no terminal callback will ever arrive for what is held.
    mutating func drop() -> Peripheral? {
        let dropped = held
        held = nil
        return dropped
    }
}
