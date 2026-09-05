// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.math.BigDecimal
import java.math.RoundingMode

/// Locale-free number text for anything that crosses the wire or lands in a document
/// (`AnalysisExport`, keys, exported ledgers). Never `String.format` with the default
/// locale for these — a French phone would write `12,3`.
///
/// TRANSLATION NOTE: Swift's `String(format: "%.1f", x)` with no locale argument is C
/// `printf`, which rounds the EXACT binary value with ties to even (`12.25` → `12.2`).
/// Java's `Formatter` rounds HALF_UP on the same exact value (`12.25` → `12.3`), so it is
/// not used; `BigDecimal(double)` is that exact value and HALF_EVEN is printf's rule.
object Fmt {
    fun fixed(value: Double, decimals: Int): String {
        require(value.isFinite()) { "non-finite value" }
        return BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
    }
}
