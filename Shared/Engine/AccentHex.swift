// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The two accent hues as HEX STRINGS, in the engine.
///
/// The engine compiles alone, without SwiftUI (`Fixtures/tools/oracle/build.sh`), so the
/// one copy of each hue lives here and `Accent.Hex` reads it — the watch face and the
/// phone cannot drift a shade apart.
enum AccentHex {
    /// Bleu de France, light scheme — `Accent.bleu`'s own value.
    static let bleu = "1E6FC4"
    /// The alarm red, light scheme — `Accent.alarm` and `Accent.alarmFlat`.
    static let alarm = "C62828"
}
