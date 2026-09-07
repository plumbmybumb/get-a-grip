// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.ui.maxes.MaxEntryDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// The max composer's state holder: what it will save, and what it calls the number.
///
/// It is a class rather than a value precisely so it can outlive the sheet while the
/// full-screen measure host owns the screen — and being a plain holder is what lets the rules
/// be asserted here without a gauge, a store or a UI tree.
class MaxEntryDraftTests {

    private val grip = GripSpec(edgeMM = 20, fingers = FingerSet.four, position = GripPosition.halfCrimp)

    // MARK: - Provenance is DERIVED, never flagged

    /// **The rule this class exists for.** `measured` only while the value still IS the one
    /// the gauge produced — so the answer is self-correcting, with no ordering rules about
    /// which change runs first.
    @Test
    fun aMeasuredNumberIsMeasured() {
        val draft = MaxEntryDraft(grip)
        draft.receiveMeasured(31.5)
        assertEquals(MaxSource.measured, draft.source)
        assertEquals(31.5, draft.kg)
    }

    /// Drag or type it away from the gauge's number and the record honestly becomes manual
    /// again. On iOS this is `measuredKg == kg` with an Optional on the left; the same
    /// comparison, and the same reason: a flag would have to be cleared by whichever control
    /// happened to move next.
    @Test
    fun editingAMeasuredNumberRevertsItToManual() {
        val draft = MaxEntryDraft(grip)
        draft.receiveMeasured(31.5)
        draft.kg = 32.0
        assertEquals(MaxSource.manual, draft.source)
    }

    /// And typing the measured value back in re-earns it — which is right, because the number
    /// being recorded genuinely is the one the gauge saw.
    @Test
    fun typingTheMeasuredNumberBackEarnsItAgain() {
        val draft = MaxEntryDraft(grip)
        draft.receiveMeasured(31.5)
        draft.kg = 20.0
        draft.kg = 31.5
        assertEquals(MaxSource.measured, draft.source)
    }

    /// A sheet nobody measured on is manual, and the untouched zero must not read as "the
    /// gauge said nothing, so nothing is what it measured".
    @Test
    fun anUntouchedDraftIsManual() {
        assertEquals(MaxSource.manual, MaxEntryDraft(grip).source)
    }

    // MARK: - What Save needs

    /// `recordMax` rejects zero outright — a 0 kg max would make every percentage caption in
    /// the app divide by nothing — so Save is disabled on exactly that range, and the band
    /// caption under the row is what says why.
    @Test
    fun saveNeedsAMaxAboveZero() {
        val draft = MaxEntryDraft(grip)
        assertFalse(draft.canSave)
        draft.kg = 0.5
        assertTrue(draft.canSave)
    }

    // MARK: - The slider spans one range, a typed value is clamped to another

    /// **`sliderRange` is what the SLIDER spans, `limit` is what a TYPED value is clamped
    /// to.** 60 was never a storage limit — typing already reached 200 — but a slider that
    /// stops is read as a ceiling, and being told your max is off-scale is a poor welcome.
    @Test
    fun theSliderStopsWellBeforeTheTypedLimit() {
        assertEquals(100.0, MaxEntryDraft.sliderRange.endInclusive)
        assertEquals(250.0, MaxEntryDraft.limit.endInclusive)
        assertTrue(MaxEntryDraft.sliderRange.endInclusive < MaxEntryDraft.limit.endInclusive)
    }

    /// A number past the storage clamp is held there rather than refused: the field is an
    /// escape hatch, and one that silently discards what you typed is not an escape hatch.
    @Test
    fun aTypedValueIsClampedToTheStorageLimit() {
        assertEquals(250.0, MaxEntryDraft.clamped(400.0))
        assertEquals(0.0, MaxEntryDraft.clamped(-12.0))
        // Anything inside the limit is untouched — including the numbers the slider cannot
        // reach, which is the whole point of the second range.
        assertEquals(180.0, MaxEntryDraft.clamped(180.0))
        assertEquals(22.5, MaxEntryDraft.clamped(22.5))
    }

    /// The gauge goes through the same clamp — a nonsense reading from a miscalibrated cell
    /// must not be able to store a number no screen can display.
    @Test
    fun aMeasuredValueIsClampedToo() {
        val draft = MaxEntryDraft(grip)
        draft.receiveMeasured(999.0)
        assertEquals(250.0, draft.kg)
        // Still measured: clamping is the app's arithmetic, not the user editing the number.
        assertEquals(MaxSource.measured, draft.source)
    }

    // MARK: - The hand

    /// Defaulted to `both`, which is what an untouched picker has always meant and what every
    /// record written before hands existed means. Nothing here is required.
    @Test
    fun theHandDefaultsToBoth() {
        assertEquals(Side.both, MaxEntryDraft(grip).side)
    }

    @Test
    fun aMeasuredResultReturnsItsSelectedHandToTheComposer() {
        val draft = MaxEntryDraft(grip)
        draft.side = Side.right
        draft.receiveMeasured(31.5, Side.left)

        assertEquals(Side.left, draft.side)
        assertEquals(31.5, draft.kg)
        assertEquals(MaxSource.measured, draft.source)
        assertEquals(grip, draft.grip)
    }

    @Test
    fun aBothHandsCorrectionReplacesTheComposersPreviousHand() {
        val draft = MaxEntryDraft(grip)
        draft.side = Side.left
        draft.receiveMeasured(42.0, Side.both)

        assertEquals(Side.both, draft.side)
        assertEquals(42.0, draft.kg)
    }

    /// The seed is a starting point, not a lock: every field stays editable, so a grip you
    /// have never used costs three taps rather than a setup step.
    @Test
    fun theSeedGripIsEditable() {
        val draft = MaxEntryDraft(grip)
        assertEquals(grip.key, draft.grip.key)
        draft.grip = draft.grip.withEdgeMM(10)
        assertEquals(10, draft.grip.edgeMM)
        // And the model's own invariant still holds through the composer: a pinch carries the
        // thumb whatever this screen asks for.
        draft.grip = draft.grip.withPosition(GripPosition.pinch)
        assertTrue(draft.grip.fingers.hasThumb)
    }
}
