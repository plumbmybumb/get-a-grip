# Android 1.1.0 (13) — 23 September 2026

Built from `13d6ee9` (code identical to `80b4008`; the commit between adds only the iOS
release record). Carries the Frez Dyno endpoint move (Frez retires
`/v1/dyno/coefficient` on 30 September 2026 00:00 UTC) and the 23 September reliability,
sound, speed and code-tidy work. Release notes:
[ANDROID_1.1.0_WHATS_NEW.txt](ANDROID_1.1.0_WHATS_NEW.txt).

## Verification

- 746 app + 450 engine unit tests green; debug and release assemble.
- Signed with the private upload script (run by Nuri). Bundle
  `build/releases/android-1.1.0-13/get-a-grip-1.1.0-13.aab`, SHA-256
  `b26a4f6662ff947293cd9a32b2be58f4fb9b42e6cdb35062f84b9f05a5bda43e`; upload certificate
  SHA-256 `DA:6D:B7:CC:…:CF:24` (unchanged); manifest versionCode 13, versionName 1.1.0.
- Play pre-review: one warning only, native code without uploaded debug symbols (as on
  earlier builds). Supported devices unchanged (5,493 phones, 3,871 tablets).
- No hardware verification in this release pass. Owed: cues on speaker and Bluetooth;
  screen-locked reconnect (Progressor and a generic GATT gauge); the frozen-app grace
  alarm; the recovery prompt after killing the app on a summary; a Dyno first-time
  calibration against the new endpoint.

## Play Console

- Closed testing - Alpha: new release "1.1.0 (13) — Frez endpoint, sound and reliability",
  full rollout, en-US notes from the What's New file; version 12 not carried over.
- Sent for review 2026-09-23 (quick checks running). The track has 87 active testers.
- Closed testing: approved and live on 2026-09-24.
- Production access granted 2026-09-24. **First production release** created the same day:
  1.1.0 (13) from the library (the same bundle as Alpha), full rollout, 176 countries /
  regions plus "rest of world", en-US notes as above. Sent for review 2026-09-24.
