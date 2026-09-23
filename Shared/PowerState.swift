// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation

/// Low Power Mode, observable: `ProcessInfo`'s property plus its notification, turned
/// into something a view can depend on. The clock numerals stop rolling under it
/// (`NumeralRoll`). A singleton, because it is a fact about the device, read by every
/// target.
@Observable @MainActor
final class PowerState {
    static let shared = PowerState()

    private(set) var isLowPowerModeEnabled: Bool

    @ObservationIgnored private var observer: (any NSObjectProtocol)?

    private init() {
        #if DEBUG
        // Headless verification: the simulator has no battery, so no Low Power Mode to
        // switch on. `-previewLowPower` stands in for it. Never in a release build.
        if ProcessInfo.processInfo.arguments.contains("-previewLowPower") {
            isLowPowerModeEnabled = true
            return
        }
        #endif
        isLowPowerModeEnabled = ProcessInfo.processInfo.isLowPowerModeEnabled
        observer = NotificationCenter.default.addObserver(
            forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main
        ) { _ in
            // The notification lands on the main queue, but not statically on the main
            // actor; hop rather than assume.
            Task { @MainActor in
                PowerState.shared.isLowPowerModeEnabled = ProcessInfo.processInfo.isLowPowerModeEnabled
            }
        }
    }
}
