// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/// Settings' Sound card (GitHub issue 7): off renders nothing, the level multiplies every
/// tone sample, and the cached full-level render is never rewritten by a quieter one.
class CueSoundLevelTests {

    @Test fun offIsSilenceWhateverTheLevel() {
        assertEquals(0f, CueSoundLevel.gain(enabled = false, volume = 1f))
        assertEquals(0f, CueSoundLevel.gain(enabled = false, volume = 0.4f))
    }

    @Test fun theLevelIsClampedAndANonsenseOneReadsAsFull() {
        assertEquals(0.35f, CueSoundLevel.gain(enabled = true, volume = 0.35f))
        assertEquals(1f, CueSoundLevel.gain(enabled = true, volume = 4f))
        assertEquals(0f, CueSoundLevel.gain(enabled = true, volume = -2f))
        assertEquals(1f, CueSoundLevel.gain(enabled = true, volume = Float.NaN))
    }

    @Test fun theLevelMultipliesTheToneAndLeavesTheCachedRenderAlone() {
        val tick = ToneSynth.render(ToneSynth.notes(ToneSynth.Tone.tick))
        val before = tick.copyOf()
        val half = CueSoundLevel.scaled(tick, 0.5f)
        assertEquals(tick.size, half.size)
        for (i in tick.indices) assertEquals(tick[i] * 0.5f, half[i])
        assertTrue(before.contentEquals(tick), "the full-level render is a cache, not a scratch buffer")
        assertSame(tick, CueSoundLevel.scaled(tick, 1f), "full level costs no copy")
    }
}
