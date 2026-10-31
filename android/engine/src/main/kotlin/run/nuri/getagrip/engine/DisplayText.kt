// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

/// Where a DISPLAY string may break across lines.
///
/// A number never breaks from its unit and a phrase never breaks inside itself: at large
/// text "≈21 / min", "36 / pulls" and "half / crimp" were each one line-wrap away from
/// reading as two facts. The rule is spelled in the string itself — every space inside a
/// token is U+00A0 — so it holds in every `Text` that draws it, with no layout code.
/// A " · "-joined line therefore wraps ONLY after a middle dot, which is the one place a
/// reader expects a new fact to begin.
///
/// Display only. Keys, blobs, the share payload and the analysis export never pass
/// through here: none of them is ever laid out, and all of them are compared byte for byte.
object DisplayText {
    /// U+00A0 NO-BREAK SPACE, spelled as a code point so the source shows what it is.
    val NBSP: Char = Char(0x00A0)

    /// "20 mm", "≈21 min", "half crimp" with every space non-breaking. For one token: a
    /// number and its unit, or a short phrase.
    fun token(text: String): String = text.replace(' ', NBSP)

    /// "20 mm · 4 fingers · half crimp" with every space non-breaking EXCEPT the one after
    /// each middle dot. The space BEFORE the dot binds too, so a line never starts with "·".
    /// Applied after translation, so the French catalog gets the same rule with no edits.
    fun dotted(text: String): String =
        token(text).replace("$NBSP·$NBSP", "$NBSP· ")

    /// Joins tokens with " · " under the `dotted` rule.
    fun dotted(parts: List<String>): String = dotted(parts.joinToString(" · "))

    /// The same text with every no-break space folded back to a plain one — for comparing
    /// display copy against a cross-platform fixture written with ordinary spaces.
    fun plain(text: String): String = text.replace(NBSP, ' ')
}
