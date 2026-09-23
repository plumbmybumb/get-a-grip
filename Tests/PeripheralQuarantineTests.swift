// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The quarantine is a GATE on every reconnect (`LiveProgressorClient` starts no attempt
/// while one is held), so these pin its ways out. The client itself cannot be driven
/// here — a `CBPeripheral` has no public initializer — which is why the bookkeeping is
/// its own type. The radio power cycle on a real Progressor stays a hardware check.
final class PeripheralQuarantineTests: XCTestCase {
    private final class FakePeripheral {}

    func testTheTerminalCallbackReleasesOnlyThePeripheralHeld() throws {
        var quarantine = PeripheralQuarantine<FakePeripheral>()
        let retired = FakePeripheral(), stranger = FakePeripheral()

        XCTAssertNotNil(quarantine.hold(retired))
        XCTAssertNil(quarantine.hold(stranger), "one gauge, one quarantine: never replaced")
        XCTAssertNil(quarantine.releaseOnTerminal(stranger), "another peripheral's callback frees nothing")
        XCTAssertTrue(quarantine.isHolding)
        XCTAssertTrue(try XCTUnwrap(quarantine.releaseOnTerminal(retired)) === retired)
        XCTAssertFalse(quarantine.isHolding)
    }

    /// Bluetooth switched off (or reset, or airplane mode) with a peripheral retired:
    /// CoreBluetooth never delivers its disconnect, so without `drop` the hold outlived
    /// the outage and every reconnect afterwards was refused until relaunch.
    func testLosingTheRadioDropsTheHoldSoTheNextAttemptCanStart() throws {
        var quarantine = PeripheralQuarantine<FakePeripheral>()
        let retired = FakePeripheral()
        _ = quarantine.hold(retired)

        XCTAssertTrue(try XCTUnwrap(quarantine.drop()) === retired)
        XCTAssertFalse(quarantine.isHolding, "power-on may attempt at once")
        XCTAssertNil(quarantine.drop(), "dropping nothing is a no-op")
        XCTAssertNil(quarantine.releaseOnTerminal(retired),
                     "a callback arriving after the drop has nothing left to release")
        XCTAssertNotNil(quarantine.hold(FakePeripheral()), "and a fresh retire can hold again")
    }

    /// The safety release frees the hold that armed it, and a timer armed for an earlier
    /// hold cannot free a later one early.
    func testTheSafetyReleaseIsBoundToItsOwnHold() throws {
        var quarantine = PeripheralQuarantine<FakePeripheral>()
        let first = FakePeripheral(), second = FakePeripheral()

        let firstTicket = try XCTUnwrap(quarantine.hold(first))
        _ = quarantine.releaseOnTerminal(first)
        let secondTicket = try XCTUnwrap(quarantine.hold(second))

        XCTAssertNotEqual(firstTicket, secondTicket)
        XCTAssertNil(quarantine.releaseOnTimeout(ticket: firstTicket), "a stale timer frees nothing")
        XCTAssertTrue(quarantine.isHolding)
        XCTAssertTrue(try XCTUnwrap(quarantine.releaseOnTimeout(ticket: secondTicket)) === second)
        XCTAssertFalse(quarantine.isHolding)
        XCTAssertNil(quarantine.releaseOnTimeout(ticket: secondTicket), "and it fires only once")
    }
}
