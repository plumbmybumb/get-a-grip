// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Settings' Sound card (GitHub issue 7): on at full level for every existing install,
/// persisted per device, and read by `CuePlayer` from the same defaults the store writes.
final class CueSoundSettingsTests: XCTestCase {

    private func freshDefaults() -> (UserDefaults, String) {
        let suite = "CueSoundSettingsTests." + UUID().uuidString
        return (UserDefaults(suiteName: suite)!, suite)
    }

    @MainActor
    func testAnUntouchedDeviceKeepsSoundOnAtFullLevel() {
        let (defaults, suite) = freshDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = SettingsStore(defaults: defaults)
        XCTAssertTrue(settings.cueSoundEnabled)
        XCTAssertEqual(settings.cueVolume, 1)
        XCTAssertEqual(CueSoundLevel.read(from: defaults).gain, 1)
    }

    @MainActor
    func testBothValuesSurviveARelaunchAndReachThePlayer() {
        let (defaults, suite) = freshDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = SettingsStore(defaults: defaults)
        first.cueVolume = 0.35
        XCTAssertEqual(CueSoundLevel.read(from: defaults).gain, 0.35, accuracy: 1e-6,
                       "the level multiplies the tones")
        first.cueSoundEnabled = false

        let relaunched = SettingsStore(defaults: defaults)
        XCTAssertFalse(relaunched.cueSoundEnabled)
        XCTAssertEqual(relaunched.cueVolume, 0.35, accuracy: 1e-9,
                       "turning sound off keeps the chosen level for when it comes back on")
        XCTAssertEqual(CueSoundLevel.read(from: defaults).gain, 0, "off renders nothing")
    }

    func testANonsenseStoredLevelIsClampedNotPlayed() {
        let (defaults, suite) = freshDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(4.0, forKey: CueSoundLevel.volumeKey)
        XCTAssertEqual(CueSoundLevel.read(from: defaults).volume, 1)
        defaults.set(-2.0, forKey: CueSoundLevel.volumeKey)
        XCTAssertEqual(CueSoundLevel.read(from: defaults).volume, 0)
        defaults.set(Double.nan, forKey: CueSoundLevel.volumeKey)
        XCTAssertEqual(CueSoundLevel.read(from: defaults).volume, 1)
    }
}
