// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
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
///
/// **The track is kept FED between cues** (`CueKeepAlive`), because the iPhone's engine
/// renders continuously from `begin()` to `end()` and a starved Android track does not:
/// the output fell to standby during every rest and the next tick paid the wake-up —
/// clipped on the speaker, lost on Bluetooth — and a fresh track held its first cue until
/// its start threshold filled. Same tones, same envelopes, same pitches at the device's
/// own sample rate; this is what makes them ARRIVE the way they do on the iPhone.
///
/// `CHHapticEngine` patterns become `VibrationEffect`s — composition primitives where the
/// vibrator has them, which is the nearest thing to a Core Haptics transient. See `CueHaptic`.
class CuePlayer(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CueSink {

    private val appContext = context.applicationContext

    private var isRunning = false
    /// The output's own rate, so the mixer never resamples a tone. The pitches are the
    /// same at any rate — `ToneSynth.render` works in seconds — so a Pixel at 48 kHz and an
    /// iPhone at 44.1 kHz play the identical figure.
    private val outputRate: Int by lazy {
        runCatching { AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) }
            .getOrNull()?.takeIf { it in 8_000..192_000 } ?: ToneSynth.SAMPLE_RATE
    }

    /// Rendered once per tone and kept, as iOS builds its nine buffers once at `begin()`.
    /// Concurrent because a new session's worker can start while the last one finishes.
    private val rendered = ConcurrentHashMap<ToneSynth.Tone, FloatArray>()

    private val audio = CueAudioQueue(
        dispatcher = dispatcher,
        enabled = { true },
        render = { tone -> rendered.getOrPut(tone) { ToneSynth.render(ToneSynth.notes(tone), outputRate) } },
        open = ::openTrack,
        keepAlive = CueKeepAlive(
            silence = FloatArray(outputRate / 50), // 20 ms
            lowWaterFrames = outputRate * 3 / 100, // 30 ms
            pollMillis = 8,
        ),
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
                .setSampleRate(outputRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(
                outputRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            ).coerceAtLeast(FALLBACK_BUFFER_BYTES)
            // Room for the longest figure (the 0.52 s session chord) plus the keep-alive
            // lead, so every cue lands in ONE write and a burst never has to wait on the
            // poll loop mid-figure. Capacity is not latency: only what is queued plays
            // before a cue, and the keep-alive holds that to tens of milliseconds.
            val bytes = maxOf(minBytes, outputRate * 3 / 4 * Float.SIZE_BYTES)
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                // A request, not a guarantee: the system falls back to the normal mixer
                // when it cannot grant a fast track, which is no worse than before.
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrNull() ?: return null
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { built.release() }
            return null
        }
        // A new streaming track waits for its WHOLE buffer before it starts — which, with a
        // buffer sized for the longest chord, would hold the first tick back indefinitely.
        // Start on the first ~5 ms instead; the keep-alive fills behind it.
        runCatching { built.setStartThresholdInFrames(maxOf(1, outputRate / 200)) }
        if (runCatching { built.play() }.isFailure) {
            runCatching { built.release() }
            return null
        }
        // Frames handed to the track, for `queuedFrames`. Worker-only, like every call here.
        var framesWritten = 0L
        return object : CueAudioOutput {
            override fun write(buffer: FloatArray, offset: Int, count: Int): Int {
                val written = built.write(buffer, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                if (written > 0) framesWritten += written
                return written
            }

            // The head position is an unsigned 32-bit frame count; masked, it cannot go
            // negative across a wrap no session will ever reach.
            override fun queuedFrames(): Int? {
                val played = built.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                return (framesWritten - played).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            }

            override fun close() {
                runCatching { built.pause() }
                runCatching { built.flush() }
                runCatching { built.stop() }
                runCatching { built.release() }
            }
        }
    }

    // MARK: - Haptics

    /// What this vibrator can actually express, asked once. Primitives are the closest
    /// Android comes to Core Haptics' transients; amplitude control is the next best.
    private val hapticRange: HapticRange by lazy {
        val device = vibrator ?: return@lazy HapticRange.predefined
        val composed = runCatching {
            device.areAllPrimitivesSupported(*CueHaptic.PRIMITIVES)
        }.getOrDefault(false)
        when {
            composed -> HapticRange.composed
            runCatching { device.hasAmplitudeControl() }.getOrDefault(false) -> HapticRange.amplitude
            else -> HapticRange.predefined
        }
    }

    private fun haptic(kind: CueHaptic) {
        if (!isRunning) return
        val device = vibrator ?: return
        runCatching { device.vibrate(kind.effect(hapticRange)) }
    }
}

/// How much of a haptic figure this device's vibrator can express.
enum class HapticRange { composed, amplitude, predefined }

/// The seven haptic figures, mapped onto what Android's vibrator can actually express.
///
/// iOS plays these through Core Haptics: TRANSIENTS (a tap with an intensity and a
/// sharpness) and CONTINUOUS events (a buzz, a swell). Android gets as close as the
/// hardware allows, in three tiers:
///
/// - **composed** — `VibrationEffect.Composition` primitives. A primitive CLICK or TICK is
///   the device maker's own tuned transient, which is what a Core Haptics transient is on an
///   iPhone; a fixed-length one-shot on the same motor is a short buzz, and at 10 ms on
///   many motors it is felt as nothing at all. Intensity maps to the primitive's scale,
///   sharpness chooses the primitive (sharp → CLICK/TICK, dull → LOW_TICK).
/// - **amplitude** — one-shots and waveforms, intensity as amplitude and sharpness as
///   DURATION: a sharp cue is a short tick, a dull one a longer buzz.
/// - **predefined** — no amplitude control: `EFFECT_CLICK` and friends, which every
///   device maps to something it can play, instead of one-shots whose amplitude would be
///   silently ignored and leave every transient feeling the same.
///
/// The continuous figures (the "go" swell, the alarm buzz) stay waveforms in every tier
/// that can shape them: no primitive is a sustained buzz.
enum class CueHaptic {
    crisp,
    crispStrong,
    soft,
    ramp,
    buzz,
    buzzSoft,
    heavyDouble;

    fun effect(range: HapticRange): VibrationEffect = when (range) {
        HapticRange.composed -> composed() ?: amplitude()
        HapticRange.amplitude -> amplitude()
        HapticRange.predefined -> predefined()
    }

    /// Null for the continuous figures, which fall through to the amplitude waveforms.
    private fun composed(): VibrationEffect? {
        val composition = VibrationEffect.startComposition()
        when (this) {
            crisp -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
            crispStrong -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
            // Soft and dull on iOS (intensity 0.45, sharpness 0.35). LOW_TICK is the dull
            // primitive; it is also the faintest, so it gets more scale than 0.45 to land at
            // the same felt weight.
            soft -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f)
            // Two knocks whose STARTS are 160 ms apart, as on iOS; the delay is measured from
            // the end of the first primitive, which is ~10-20 ms long.
            heavyDouble -> {
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 145)
            }
            ramp, buzz, buzzSoft -> return null
        }
        return composition.compose()
    }

    private fun amplitude(): VibrationEffect = when (this) {
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

    private fun predefined(): VibrationEffect = when (this) {
        crisp -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        crispStrong -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        soft -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        heavyDouble -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        // No amplitude to shape, so the swell becomes its length alone.
        ramp -> VibrationEffect.createOneShot(320, VibrationEffect.DEFAULT_AMPLITUDE)
        buzz -> VibrationEffect.createOneShot(280, VibrationEffect.DEFAULT_AMPLITUDE)
        buzzSoft -> VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    companion object {
        /// Every primitive `composed()` uses: a device that lacks any of them gets the
        /// amplitude tier for ALL figures, so one cue never feels unlike its family.
        val PRIMITIVES = intArrayOf(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
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
