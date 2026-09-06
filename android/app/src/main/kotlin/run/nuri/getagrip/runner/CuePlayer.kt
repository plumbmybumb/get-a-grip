// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import run.nuri.getagrip.engine.RunnerCue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/// Plays what `SessionRunner` asks for — and nothing else.
///
/// The runner returns `RunnerCue`s and never plays one itself, so this is the only place in
/// the app where a workout makes a noise. Everything here is pure OUTPUT: no method throws,
/// no failure propagates, and a phone with no audio route and no vibrator still runs the
/// whole session in silence. A cue that cannot be played is not a reason for a set to stop.
///
/// The tones are synthesized on the audio worker, with no file loading or main-thread
/// audio setup. They share one A-major frame so the cues read as a family; the alarm
/// deliberately does not, which is what makes it read as wrong.
///
/// ## NEVER DISTURB WHAT THE USER IS LISTENING TO
///
/// **Audio focus is never requested. Not once, not transiently, not `MAY_DUCK`.** That is
/// the whole rule, and it is a named reason Nuri left Frez. `requestAudioFocus` with any
/// transient gain ducks or pauses whatever is playing, and this app fires roughly one cue
/// every five seconds across a twenty-minute session — a video that pulses for the entire
/// workout is a smaller version of the same complaint, not a solution to it.
///
/// **The stream is `USAGE_MEDIA` (content `SONIFICATION`), NOT `USAGE_ASSISTANCE_SONIFICATION`.**
/// The iOS rule is that cues survive the mute switch (`.playback`, not `.ambient`). On Android
/// the sonification usage rides the SYSTEM stream, which silent mode mutes outright — the
/// first hardware session on the Realme (2026-09-04) produced no sound at all with the phone
/// on silent. Media is the stream the user actually controls with the volume keys and the
/// one the ringer mode leaves alone. Mixing is unchanged: with no focus request, other media
/// keeps playing untouched.
///
/// TRANSLATION NOTE (from Sources/Runner/CuePlayer.swift): `AVAudioEngine` + a scheduled
/// `AVAudioPCMBuffer` per cue becomes ONE `AudioTrack` in `MODE_STREAM` that buffers are
/// written into from a single background queue owner — which reproduces the property iOS got
/// from `scheduleBuffer` without `.interrupts`: cues QUEUE rather than cutting each other
/// off, because the runner returns them in batches (a final rep yields rep-end, set-end and
/// session-end together) and interrupting would leave only the last one audible.
/// `CHHapticEngine` patterns become `VibrationEffect` waveforms; Android has no sharpness
/// axis, so intensity is mapped to amplitude and sharpness to DURATION — a sharp cue is a
/// short tick, a dull one a longer buzz, which is the closest the hardware gets.
class CuePlayer(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CueSink {

    private val appContext = context.applicationContext

    private var isRunning = false
    private val audio = CueAudioQueue(
        dispatcher = dispatcher,
        enabled = { true },
        render = { ToneSynth.render(ToneSynth.notes(it)) },
        open = ::openTrack,
    )

    /// Null on a device with no vibrator — everything haptic short-circuits on it rather
    /// than logging or throwing. That is the expected state of an emulator, not an error.
    private val vibrator: Vibrator? = runCatching {
        val manager = appContext.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator?.takeIf { it.hasVibrator() }
    }.getOrNull()

    // MARK: - Lifecycle

    override fun begin() {
        if (isRunning) return
        isRunning = true
        audio.begin()
    }

    override fun end() {
        if (!isRunning) return
        isRunning = false
        audio.end()
    }

    // MARK: - The one entry point

    /// Every case is spelled out and there is NO `else` — a cue added to `RunnerCue` must be
    /// a compile error here rather than a cue that silently never sounds.
    override fun gripChanged() { sound(ToneSynth.Tone.gripChange) }

    override fun play(cue: RunnerCue) {
        when (cue) {
            is RunnerCue.LeadInTick -> tick(cue.secondsRemaining)
            is RunnerCue.RestTick -> tick(cue.secondsRemaining)

            is RunnerCue.Armed -> {
                sound(ToneSynth.Tone.go)
                haptic(CueHaptic.ramp)
            }

            is RunnerCue.RepStarted -> {
                // Deliberately small: "go" already sounded, and this only confirms the clock
                // caught the pull. Something louder here would compete with it.
                sound(ToneSynth.Tone.tick)
                haptic(CueHaptic.crisp)
            }

            is RunnerCue.RepHalfway -> {
                sound(ToneSynth.Tone.halfway)
                haptic(CueHaptic.soft)
            }

            is RunnerCue.RepEnded -> {
                sound(if (cue.completed) ToneSynth.Tone.repComplete else ToneSynth.Tone.alarm)
                haptic(if (cue.completed) CueHaptic.crispStrong else CueHaptic.buzz)
            }

            is RunnerCue.SetCompleted -> {
                sound(ToneSynth.Tone.setComplete)
                haptic(CueHaptic.crispStrong)
            }

            is RunnerCue.SessionCompleted -> {
                sound(ToneSynth.Tone.sessionComplete)
                haptic(CueHaptic.heavyDouble)
            }

            is RunnerCue.DropoutWarning -> {
                // A dip is a warning, not a verdict — the rep is still alive, so this is the
                // quiet, short form of the same alarm that ends one.
                sound(ToneSynth.Tone.alarmSoft)
                haptic(CueHaptic.buzzSoft)
            }

            is RunnerCue.ConnectionLost -> {
                sound(ToneSynth.Tone.alarm)
                haptic(CueHaptic.buzz)
            }
        }
    }

    /// The most-heard cue in the app, and the one most able to ruin it: a beep per second
    /// through a two-minute rest is unbearable, so only the last three seconds speak at all.
    /// The final second is a higher pitch so "go" is anticipated rather than merely announced.
    private fun tick(secondsRemaining: Int) {
        if (secondsRemaining !in ToneSynth.SPEAKING_TICKS) return
        val last = secondsRemaining == 1
        sound(if (last) ToneSynth.Tone.tickFinal else ToneSynth.Tone.tick)
        haptic(if (last) CueHaptic.crispStrong else CueHaptic.crisp)
    }

    // MARK: - Audio

    private fun sound(tone: ToneSynth.Tone) {
        if (isRunning) audio.play(tone)
    }

    // Called exclusively by the audio queue's worker, as are write and close.
    private fun openTrack(): CueAudioOutput? {
        val built = runCatching {
            val attributes = AudioAttributes.Builder()
                // **MEDIA usage, and no focus request anywhere in this file.** See the type's
                // note: the system stream is muted by silent mode, the media stream is not,
                // and these tones must carry over the user's music without touching it.
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(ToneSynth.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(
                ToneSynth.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            ).coerceAtLeast(FALLBACK_BUFFER_BYTES)
            // A short buffer keeps queued feedback close to the action that caused it.
            val bytes = maxOf(minBytes, ToneSynth.SAMPLE_RATE / 10 * Float.SIZE_BYTES)
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return null
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { built.release() }
            return null
        }
        if (runCatching { built.play() }.isFailure) {
            runCatching { built.release() }
            return null
        }
        return object : CueAudioOutput {
            override fun write(buffer: FloatArray, offset: Int, count: Int): Int =
                built.write(buffer, offset, count, AudioTrack.WRITE_NON_BLOCKING)

            override fun close() {
                runCatching { built.pause() }
                runCatching { built.flush() }
                runCatching { built.stop() }
                runCatching { built.release() }
            }
        }
    }

    // MARK: - Haptics

    private fun haptic(kind: CueHaptic) {
        if (!isRunning) return
        val device = vibrator ?: return
        runCatching { device.vibrate(kind.effect()) }
    }
}

/// The seven haptic figures, mapped onto what Android's vibrator can actually express.
///
/// iOS has an intensity axis AND a sharpness axis; Android has amplitude and time. So
/// intensity becomes AMPLITUDE and sharpness becomes DURATION — a sharp cue is a short
/// tick, a dull one a longer buzz. The ramp keeps its swell as a rising waveform, because
/// "go" is a state you enter and a swell is still felt through a hand already closing on
/// the edge.
enum class CueHaptic {
    crisp,
    crispStrong,
    soft,
    ramp,
    buzz,
    buzzSoft,
    heavyDouble;

    fun effect(): VibrationEffect = when (this) {
        crisp -> VibrationEffect.createOneShot(10, 153)
        crispStrong -> VibrationEffect.createOneShot(16, 255)
        soft -> VibrationEffect.createOneShot(22, 115)
        // 320 ms, the same swell length iOS gives it, in four rising steps.
        ramp -> VibrationEffect.createWaveform(
            longArrayOf(80, 80, 80, 80),
            intArrayOf(51, 120, 180, 255),
            -1,
        )
        buzz -> VibrationEffect.createOneShot(280, 230)
        buzzSoft -> VibrationEffect.createOneShot(140, 140)
        // Two knocks 160 ms apart — the session's one "you are finished".
        heavyDouble -> VibrationEffect.createWaveform(
            longArrayOf(0, 16, 144, 16),
            intArrayOf(0, 255, 0, 255),
            -1,
        )
    }
}

/// The nine cue tones, as pure arithmetic.
///
/// Split out from `CuePlayer` on purpose: rendering is the half worth testing (durations,
/// sample counts, peak amplitude, the release ramp that stops a truncated sine clicking)
/// and it needs no `AudioTrack`, no vibrator and no Android at all.
object ToneSynth {

    const val SAMPLE_RATE = 44_100

    /// Only the last three seconds of a countdown speak. See `CuePlayer.tick`.
    val SPEAKING_TICKS = 1..3

    /// ~4 ms of release on every note. The exponential tail never quite reaches zero, and a
    /// truncated sine clicks.
    const val RELEASE_SECONDS = 0.004

    /// One note of a cue: the partials sounded together, how long, and how loud.
    data class Note(
        val hz: List<Double>,
        val seconds: Double,
        val amplitude: Double,
        /// Attack as a fraction of the note. Fast, but never zero — a hard edge on a sine is
        /// an audible click through a phone speaker.
        val attack: Double = 0.02,
        /// Exponential decay constant as a fraction of the note. Smaller is drier.
        val decay: Double = 0.35,
    )

    enum class Tone {
        tick,
        tickFinal,
        go,
        halfway,
        repComplete,
        setComplete,
        sessionComplete,
        alarm,
        alarmSoft,
        gripChange,
    }

    // A4 / C#5 / E5 / A5 — one chord's worth of pitches, so seven cues are tellable apart by
    // interval rather than by volume, which is what survives a gym.
    private const val A4 = 440.0
    private const val CS5 = 554.37
    private const val E5 = 659.25
    private const val A5 = 880.0

    // The alarm's minor second, low. Sounded together the beating is unpleasant BY
    // CONSTRUCTION, and unpleasant is the whole message.
    private const val ALARM_LO = 138.59
    private const val ALARM_HI = 146.83

    fun notes(tone: Tone): List<Note> = when (tone) {
        Tone.tick -> listOf(Note(listOf(A4), 0.06, 0.30, decay = 0.30))
        Tone.tickFinal -> listOf(Note(listOf(E5), 0.07, 0.45, decay = 0.30))
        // Rising, because it means START. A falling figure for the same event read as "done"
        // to everyone who heard it.
        Tone.go -> listOf(
            Note(listOf(A4), 0.09, 0.55, decay = 0.50),
            Note(listOf(A5), 0.12, 0.60, decay = 0.50),
        )
        Tone.gripChange -> listOf(Note(listOf(E5), 0.10, 0.55, decay = 0.30),
            Note(listOf(CS5), 0.14, 0.55, decay = 0.30))
        Tone.halfway -> listOf(Note(listOf(E5), 0.11, 0.40))
        Tone.repComplete -> listOf(Note(listOf(A5), 0.16, 0.55))
        // Falling and soft-edged: a set ending is a rest earned, not an alert.
        Tone.setComplete -> listOf(
            Note(listOf(A5), 0.10, 0.45, attack = 0.10, decay = 0.50),
            Note(listOf(E5), 0.14, 0.45, attack = 0.10, decay = 0.50),
        )
        // The one cue allowed past 250 ms — it is heard once, and it is the only thing
        // telling someone with their eyes shut that they are finished.
        Tone.sessionComplete -> listOf(
            Note(listOf(A4), 0.15, 0.50, decay = 0.50),
            Note(listOf(CS5), 0.15, 0.50, decay = 0.50),
            Note(listOf(E5), 0.22, 0.55, decay = 0.60),
        )
        Tone.alarm -> listOf(
            Note(listOf(ALARM_LO, ALARM_HI), 0.22, 0.55, attack = 0.03, decay = 0.90),
        )
        Tone.alarmSoft -> listOf(
            Note(listOf(ALARM_LO, ALARM_HI), 0.13, 0.35, attack = 0.03, decay = 0.90),
        )
    }

    /// Renders the notes end to end into ONE buffer, so a whole figure is a single write and
    /// cannot be pulled apart by scheduling jitter.
    fun render(notes: List<Note>, rate: Int = SAMPLE_RATE): FloatArray {
        val counts = notes.map { maxOf(1, (it.seconds * rate).roundToInt()) }
        val total = counts.sum()
        if (total <= 0) return FloatArray(0)
        val out = FloatArray(total)

        val releaseFrames = maxOf(1, (RELEASE_SECONDS * rate).toInt())
        var cursor = 0
        for ((note, count) in notes.zip(counts)) {
            val attackFrames = maxOf(1, (note.attack * note.seconds * rate).toInt())
            for (i in 0 until count) {
                val t = i.toDouble() / rate
                var value = 0.0
                for (hz in note.hz) value += sin(2 * PI * hz * t)
                value /= note.hz.size
                val rise = min(1.0, i.toDouble() / attackFrames)
                val fall = exp(-t / (note.seconds * note.decay))
                val release = min(1.0, (count - i).toDouble() / releaseFrames)
                out[cursor + i] = (value * note.amplitude * rise * fall * release).toFloat()
            }
            cursor += count
        }
        return out
    }
}

/// A last resort when `getMinBufferSize` reports an error code: 8192 float frames is well
/// over the longest cue and small enough not to matter.
private const val FALLBACK_BUFFER_BYTES = 8192 * Float.SIZE_BYTES
