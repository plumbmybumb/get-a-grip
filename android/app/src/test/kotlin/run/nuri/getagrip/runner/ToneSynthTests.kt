// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The nine cue tones, as arithmetic.
///
/// `CuePlayer` is pure OUTPUT and cannot fail inward, so almost none of it is assertable —
/// but the SYNTHESIS is, and it is the half where a mistake is audible rather than silent: a
/// missing release ramp clicks through a phone speaker, an over-unity envelope clips, and a
/// note whose length is wrong changes the rhythm the whole session is paced by. None of that
/// needs an `AudioTrack`, a vibrator or Android.
class ToneSynthTests {

    private val rate = ToneSynth.SAMPLE_RATE

    private fun frames(seconds: Double) = (seconds * rate).roundToInt()

    /// A single-note cue is exactly its own duration, rounded to a frame.
    @Test
    fun aSingleNoteRendersItsOwnDuration() {
        val tick = ToneSynth.render(ToneSynth.notes(ToneSynth.Tone.tick))
        assertEquals(frames(0.06), tick.size)
    }

    /// A figure is rendered END TO END into one buffer, so a whole cue is a single write and
    /// cannot be pulled apart by scheduling jitter.
    @Test
    fun aMultiNoteCueIsOneBufferOfTheSummedDurations() {
        val go = ToneSynth.render(ToneSynth.notes(ToneSynth.Tone.go))
        assertEquals(frames(0.09) + frames(0.12), go.size)

        val session = ToneSynth.render(ToneSynth.notes(ToneSynth.Tone.sessionComplete))
        assertEquals(frames(0.15) + frames(0.15) + frames(0.22), session.size)
    }

    /// Every tone renders something. A cue that silently produced an empty buffer would be a
    /// cue that never sounds, which is exactly the failure the exhaustive `when` in
    /// `CuePlayer.play` exists to prevent one level up.
    @Test
    fun everyToneRendersANonEmptyBuffer() {
        for (tone in ToneSynth.Tone.entries) {
            assertTrue(ToneSynth.render(ToneSynth.notes(tone)).isNotEmpty(), "$tone rendered nothing")
        }
    }

    /// **The envelope only ever attenuates.** Attack, decay and release are all in 0…1, so no
    /// sample may exceed the note's own amplitude — a buffer that clipped would distort
    /// through a speaker and there is no headroom above 1.0 to absorb it.
    @Test
    fun noSampleExceedsItsNotesAmplitude() {
        for (tone in ToneSynth.Tone.entries) {
            val ceiling = ToneSynth.notes(tone).maxOf { it.amplitude }
            val peak = ToneSynth.render(ToneSynth.notes(tone)).maxOf { abs(it) }
            assertTrue(peak <= ceiling + 1e-4, "$tone peaked at $peak against a ceiling of $ceiling")
            assertTrue(peak <= 1f, "$tone would clip")
        }
    }

    /// **~4 ms of release on every note, and this is what it is for.** The exponential tail
    /// never quite reaches zero, and a truncated sine clicks.
    @Test
    fun everyNoteEndsOnTheReleaseRampSoItCannotClick() {
        for (tone in ToneSynth.Tone.entries) {
            val buffer = ToneSynth.render(ToneSynth.notes(tone))
            assertTrue(
                abs(buffer.last()) < 0.02f,
                "$tone ends at ${buffer.last()} — loud enough to click",
            )
        }
    }

    /// The attack is fast but never zero, for the same reason: a hard edge on a sine is an
    /// audible click.
    @Test
    fun everyNoteOpensOnTheAttackRamp() {
        for (tone in ToneSynth.Tone.entries) {
            val buffer = ToneSynth.render(ToneSynth.notes(tone))
            assertEquals(0f, buffer.first(), 1e-6f, "$tone starts at full amplitude")
        }
    }

    /// The alarm sounds a minor SECOND — two partials together, deliberately beating. The
    /// average of the two is what keeps it inside its amplitude.
    @Test
    fun theAlarmSoundsTwoPartialsTogether() {
        val alarm = ToneSynth.notes(ToneSynth.Tone.alarm).single()
        assertEquals(2, alarm.hz.size)
        assertTrue(alarm.hz[1] > alarm.hz[0])
        // A minor second at this octave is about 8 Hz apart — close enough that the beating
        // IS the message.
        assertTrue(alarm.hz[1] - alarm.hz[0] < 10.0)
    }

    /// **Only the last three seconds of a countdown speak.** A beep per second through a
    /// two-minute rest is unbearable; this range is the whole rule and the reason the most
    /// heard cue in the app is bearable.
    @Test
    fun onlyTheLastThreeTicksSpeak() {
        assertEquals(1..3, ToneSynth.SPEAKING_TICKS)
        assertTrue(0 !in ToneSynth.SPEAKING_TICKS)
        assertTrue(4 !in ToneSynth.SPEAKING_TICKS)
    }

    /// The cues share one A-major frame so seven of them are tellable apart by INTERVAL
    /// rather than by volume, which is what survives a gym. The alarm deliberately does not,
    /// which is what makes it read as wrong.
    @Test
    fun theCuesShareOneChordAndTheAlarmDoesNot() {
        val family = setOf(440.0, 554.37, 659.25, 880.0)
        val musical = ToneSynth.Tone.entries.filter {
            it != ToneSynth.Tone.alarm && it != ToneSynth.Tone.alarmSoft
        }
        for (tone in musical) {
            for (note in ToneSynth.notes(tone)) {
                assertTrue(note.hz.all { it in family }, "$tone left the chord: ${note.hz}")
            }
        }
        for (tone in listOf(ToneSynth.Tone.alarm, ToneSynth.Tone.alarmSoft)) {
            assertTrue(ToneSynth.notes(tone).single().hz.none { it in family })
        }
    }

    /// One cue is allowed past 250 ms — the session's own ending, heard once, and the only
    /// thing telling someone with their eyes shut that they are finished. Every other cue
    /// stays short enough to sit under a countdown without smearing into the next beat.
    @Test
    fun onlyTheSessionEndingRunsPastAQuarterOfASecond() {
        for (tone in ToneSynth.Tone.entries) {
            val seconds = ToneSynth.notes(tone).sumOf { it.seconds }
            if (tone == ToneSynth.Tone.sessionComplete) {
                assertTrue(seconds > 0.25, "the session cue should be the long one")
            } else {
                assertTrue(seconds <= 0.25, "$tone runs $seconds s")
            }
        }
    }

    /// 44.1 kHz mono float, and the render honours whatever rate it is handed — the mixer
    /// converts to whatever the route actually wants, so the buffers never have to be rebuilt
    /// when headphones appear.
    @Test
    fun theRenderHonoursTheRateItIsGiven() {
        val notes = ToneSynth.notes(ToneSynth.Tone.tick)
        assertEquals(44_100, ToneSynth.SAMPLE_RATE)
        assertEquals((0.06 * 22_050).roundToInt(), ToneSynth.render(notes, rate = 22_050).size)
    }

    /// A note shorter than one frame still renders one frame rather than nothing — the floor
    /// that keeps a rounding error from producing a silent cue.
    @Test
    fun aVanishinglyShortNoteStillRendersAFrame() {
        val note = ToneSynth.Note(hz = listOf(440.0), seconds = 0.0, amplitude = 0.5)
        assertEquals(1, ToneSynth.render(listOf(note)).size)
    }
}
