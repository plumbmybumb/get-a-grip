// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

enum BatteryDisplay {
    static func percentage(_ fraction: Double) -> Int {
        guard fraction.isFinite else { return 0 }
        return Int((min(max(fraction, 0), 1) * 100).rounded())
    }
}
