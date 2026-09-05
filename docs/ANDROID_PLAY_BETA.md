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
- Intended audience: 13–15, 16–17 and 18+, explicitly confirmed by the owner.
- IARC questionnaire includes native routine QR exchange as user content sharing.
  No chat, moderation, public imagery, online content catalogue, purchases,
  location sharing, or age-restricted goods. Generated ratings include Everyone
  (ESRB), PEGI 3 and USK 16 (communication-risk descriptor).

## Pending before submission

- Final store listing artwork declaration. Actual app captures are framed using
  AI-assisted native HTML/SVG artwork and captions. Explicit owner approval of
  the asset labels was requested; the listing is saved as a draft meanwhile.
- Country selection: worldwide closed-beta availability requested for Reddit
  testers; no countries saved pending explicit owner approval.
- Data safety is saved as a draft. Optional email address, support emails and
  diagnostics are described, with no non-exempt third-party sharing or accounts.
  Support email/diagnostics are functionality and bug-diagnosis purposes;
  addresses are for support functionality. Review the encryption answer before
  submission: the app has no Internet permission or training backend; reports
  are handed to an external email client. An unverified all-transit encryption
  claim must not be made. Google's negative preview wording is also broader than
  this architecture and needs careful interpretation of its external-service
  guidance. Do not treat a saved draft as an approved declaration.
- Foreground service video: actual connected-device session, start, background,
  ongoing notification, return and end. Demo mode intentionally pauses in the
  background and cannot demonstrate this permission. Hardware readiness was
  requested from the owner; no physical session was started for the recording.
- Final release review and submission. Native library symbols generate a
  non-blocking warning; the bundle includes AndroidX native dependencies and
  the Java/Kotlin ReTrace mapping, but no native symbols were available.

## Links

- Group: https://groups.google.com/g/getagrip-android-beta
- Play opt-in URL shown by Console (not active until publication):
  https://play.google.com/apps/testing/run.nuri.getagrip
- Public source: https://github.com/plumbmybumb/get-a-grip
- Privacy: https://nuri.run/getagrip/privacy

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
