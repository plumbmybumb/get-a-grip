# Android Play closed beta

## Current status — 7 September 2026

Version **1.0.1 (4)**, release **1.0.1 (4) — Choose your hand for max tests**,
has been submitted to the existing closed Alpha track at 100% rollout. Play
Console shows **Changes in review**, with automated quick checks running.
Managed publishing is off; eligible testers receive the update after approval.
Build 3 was verified available to testers before this submission and remains live.

The update adds hand selection directly to max measurement and preserves results
after failed saves. All 899 Android tests pass. No change to permissions or
supported devices. See [release verification](RELEASE_2026-09-07.md).

## Previous submission — 6 September 2026

The status below is historical. Build 3 was subsequently approved and published.

Version **1.0.1 (3)**, release **1.0.1 (3) — Effort ladder and reliability**, has
been uploaded and submitted to the existing closed Alpha track at 100% rollout.
Play Console shows **Changes in review**, with automated quick checks running.
Managed publishing is off: approval will publish the update to eligible testers.
Build 2 remains the live version while this update is reviewed.

The source is commit `08ac463da23a9e344439d7b937c68f7638bee3d6`, tagged
`android-v1.0.1-3`. The upload certificate matches the preceding official bundle.
Permissions and supported devices are unchanged. The only Console warning is
missing native debug symbols from dependencies; ReTrace mapping is attached.
See [release verification](RELEASE_2026-09-06.md) for artifact hashes and checks.

## Initial submission record — 5 September 2026

The notes below describe the initial submission; their pending-approval wording
is historical. That initial beta and build 2 were subsequently approved.

Package: `run.nuri.getagrip`; version name `1.0`, version code `1`.
The signed AAB has been uploaded to Google Play's closed Alpha track and parsed
successfully. All 15 publishing changes were submitted on 5 September 2026.
Console shows Changes in review, with automated quick checks running before
review. The beta is not yet approved or available to install. Managed publishing
is off, so the closed track is set to publish after approval.

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

## Submission and review

- Owner-supplied `IMG_3777.MOV` demonstrates the Tindeq hardware, connection and
  live force readings during a session. The complete 74-second recording and
  narration were converted to a 1080 × 1920 H.264/AAC MP4 (about 43 MB).
  It is hosted at an unlisted, noindex URL in the private website repository.
- The connected-device foreground-service declaration includes that video.
  Google saved it successfully. The release then had zero blocking errors.
- Native library debug symbols remain an optional warning; Java/Kotlin ReTrace
  mapping is included. No native symbols were available from these dependencies.
- All 15 changes were sent for review: closed Alpha rollout, worldwide targeting,
  group-based testers, listing, audience and content/privacy declarations.
- Wait for Google review. Approval is not automatic. If review asks for a video
  specifically showing background operation or the notification, record an
  additional clip; the supplied video demonstrates foreground Bluetooth use.

## Device follow-up

The automated recording attempt did not produce a usable file. The user supplied
its replacement. Before the phone was unplugged, its temporary plugged-in
keep-awake setting had been enabled. Original value was 0; restore it when the
USB phone is connected again. Do not modify any current workout to do so.
The app was switched from WH-C06 to Progressor during the recording attempt.
Notification permission was inspected but not changed.

## Links

- Group: https://groups.google.com/g/getagrip-android-beta/about
  Use the About page for recruitment: the conversations page shows a misleading
  access error because conversations are owner-only. Testers must sign in with
  their Play Store Google account before the join option is available.
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
