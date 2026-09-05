# Android audio ownership and measured progress

Device crash reports showed native SIGSEGVs inside AudioTrack.write/releaseBuffer.
The previous player launched one IO coroutine per cue, so writes could overlap.
Its end method cancelled only the latest writer and released the shared native
track from the caller thread. Coroutine cancellation does not interrupt a JNI call;
catching Kotlin exceptions cannot recover a native segmentation fault.

CueAudioQueue now gives one worker exclusive ownership of each AudioTrack, including
construction, nonblocking writes, route recovery, and release in finally. End drops
queued cues and requests cancellation; only the owner can release its handle after
the active native call returns. A subsequent session owns a different handle.
Partial writes advance the offset, retries are bounded, and an unwritable route
cannot spin forever. Audio setup and synthesis stay off the UI thread. No audio
focus is requested, and live sound settings are honored.

The measured pull bar now receives the full measured fraction, separately from the
coarse runner snapshot. Its view travels toward the latest reading using the linear
200 ms Motion.measuredProgress curve and resets at each new pull. Reduce Motion snaps to the measured value. There is no
extrapolation, and neither credited work time nor persisted force data is changed.

Validation: 411 Android app tests passed, including six audio-queue regressions,
a sub-percent progress/no-sample timing regression, and three Compose animation
checks for constant travel, no extrapolation, new-pull reset, and reduced motion. The regression matrix covers
teardown during an in-flight write, queued cue order, partial writes, a failed route,
a stalled route, restart, and muting. The audio fix was tested in a release APK on a USB Android
phone with a live Progressor: six Skip set actions, including rapid skips and final
session completion, retained the same process with no new crash record. This tests
the identified failure; it does not establish that every possible crash is resolved.
Personal crash logs, device identifiers, screenshots, and training data are kept out
of this repository.
