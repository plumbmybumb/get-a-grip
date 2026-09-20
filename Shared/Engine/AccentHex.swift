// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The two accent hues as HEX STRINGS, in the engine.
///
/// `WatchFaceMood` names the face's colours by hex so the wrist and the phone cannot
/// drift a shade apart, and it used to read them off `Accent.Hex` in the design tokens.
/// The tokens import SwiftUI; the engine imports Foundation and is compiled on its own
/// by the fixture oracle (`Fixtures/tools/oracle/build.sh`), which is where that
/// reference failed to compile (CI, 2026-09-20). The value lives HERE now and the tokens
/// read it, so there is still exactly one copy — it just sits on the side that everything
/// can see.
enum AccentHex {
    /// Bleu de France, light scheme — `Accent.bleu`'s own value.
    static let bleu = "1E6FC4"
    /// The alarm red, light scheme — `Accent.alarm` and `Accent.alarmFlat`.
    static let alarm = "C62828"
}
