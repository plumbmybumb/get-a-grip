# Android Play beta preparation — 5 September 2026

Package: `run.nuri.getagrip`; version name `1.0`, version code `1`.
The signed AAB has been uploaded to Google Play's closed Alpha track and parsed
successfully. This is a draft release, not an approved or available beta.

## Completed

- Signed release bundle with shrinking and ReTrace mapping included.
- 422 Android app tests and 386 engine tests pass (808 total).
- Six actual app screenshots with synthetic history, framed to match iOS.
  RGB PNGs, 1080 × 1920; feature graphic 1024 × 500; store icon 512 × 512.
- Google Group created: `getagrip-android-beta@googlegroups.com`.
  Anyone may join; only the owner may see members, their email addresses,
  conversations, or post. No test invitations were sent.
- Closed-track tester eligibility uses that Google Group; feedback is
  `support@nuri.run`.
- Privacy URL, unrestricted access, no ads, no advertising ID, no government or
  financial features, activity/fitness health declaration, Health & Fitness
  category, and public support contacts saved in Play Console.
- Website beta instructions and data-deletion pages are deployed and verified
  on `nuri.run`; the beta page clearly says installation is pending approval.
  The dedicated deletion URL is saved in the Data safety draft.
- Android beta source and artwork are published in commit `8327298`.
- Store listing finalized, with all eight visual assets labeled AI-assisted after
  explicit owner approval. Six screenshots, icon and feature graphic are saved.
- Worldwide closed-beta targeting saved after explicit owner approval; access
  remains restricted to the tester Google Group.
- Data safety declaration saved for review: optional email address, support
  emails and diagnostics; no non-exempt third-party sharing or app accounts.
  Email address is for support functionality; email/diagnostics also cover bug
  diagnosis (Google's Analytics category). The all-data encryption question is
  answered No: external mail clients control support transmission and the app
  does not enforce encryption on that route. Google's broad preview reads
  "Data isn't encrypted"; this is not a claim that local training is uploaded.
  The app has no Internet permission or training backend.
- Intended audience: 13–15, 16–17 and 18+, explicitly confirmed by the owner.
- IARC questionnaire includes native routine QR exchange as user content sharing.
  No chat, moderation, public imagery, online content catalogue, purchases,
  location sharing, or age-restricted goods. Generated ratings include Everyone
  (ESRB), PEGI 3 and USK 16 (communication-risk descriptor).

## Pending before submission

- Foreground service video: actual connected-device session, start, background,
  ongoing notification, return and end. Demo mode intentionally pauses in the
  background and cannot demonstrate this permission. Hardware readiness was
  confirmed by the owner, but the phone re-locked before recording. It was
  displaying the WH-C06 (IF_B7); select Progressor for this demonstration.
  No physical session has been started for the recording.
- Final release review and submission. Console now reports one blocking error
  (foreground service declaration). Native library symbols generate a
  non-blocking warning; the bundle includes AndroidX native dependencies and
  the Java/Kotlin ReTrace mapping, but no native symbols were available.

## Links

- Group: https://groups.google.com/g/getagrip-android-beta
- Play opt-in URL shown by Console (not active until publication):
  https://play.google.com/apps/testing/run.nuri.getagrip
- Public source: https://github.com/plumbmybumb/get-a-grip
- Privacy: https://nuri.run/getagrip/privacy
- Beta instructions (live, release still pending): https://nuri.run/getagrip/android-beta
- Deletion requests: https://nuri.run/getagrip/delete-data

This new personal developer account requires at least 12 opted-in closed testers
for 14 days before applying for production access. Group membership alone does
not count as Play opt-in. Google approval and production access are not automatic.

## Signing and reproduction

The upload key and passwords are stored outside this repository in the owner's
private Android signing folder. Back them up securely. Never commit or upload
that folder; only the signed AAB goes to Play. Play App Signing holds the
distribution key. A Play-signed install cannot update a differently signed local
development install without a migration/reinstallation plan; preserve users'
training records before changing installation channels.

The emulator was closed after captures. The physical phone's training data was
not seeded, cleared, or replaced for store artwork.
