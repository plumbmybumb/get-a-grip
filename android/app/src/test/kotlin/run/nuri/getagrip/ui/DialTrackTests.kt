// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.ui.components.DialLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/// `renderingMark` is the dial's drawing DECISION, lifted out of the composable so the
/// contract can be asserted without a UI tree. The rule it pins: an UNANSWERED scale draws
/// nothing at all — no highlighted detent, no hollow off-ladder mark.
///
/// That matters because the obvious implementation of "unset" is a sentinel below the
/// ladder, and `offLadderX` parks such a value on the FIRST stop — so an untouched dial
/// would draw a mark sitting on stop one, which is an answer.
class DialTrackTests {

    private val ladder = listOf(1.0, 2.0, 3.0, 4.0, 5.0)

    @Test
    fun anAnsweredDialHighlightsTheDetentItSitsOn() {
        assertEquals(
            DialLadder.RenderingMark.Detent(2),
            DialLadder.renderingMark(3.0, ladder, isUnset = false),
        )
    }

    /// The pre-existing behaviour, unchanged: a value between two stops keeps the hollow
    /// mark rather than lighting a detent it is not on.
    @Test
    fun aValueBetweenStopsStillDrawsTheOffLadderMark() {
        assertEquals(
            DialLadder.RenderingMark.OffLadder,
            DialLadder.renderingMark(2.5, ladder, isUnset = false),
        )
    }

    @Test
    fun anUnsetDialDrawsNoCurrentDetent() {
        assertNull(
            DialLadder.renderingMark(1.0, ladder, isUnset = true),
            "an untouched dial parked on its placeholder must not light stop one",
        )
    }

    /// The sentinel trap stated above, asserted directly.
    @Test
    fun anUnsetDialDrawsNoOffLadderMarkEither() {
        assertNull(DialLadder.renderingMark(0.0, ladder, isUnset = true))
        assertNull(DialLadder.renderingMark(2.5, ladder, isUnset = true))
    }
}
