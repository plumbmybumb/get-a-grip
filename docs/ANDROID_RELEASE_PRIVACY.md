# Android privacy and release declarations

Reviewed September 5, 2026 for `run.nuri.getagrip`. This is a release handoff,
not a claim that Google has reviewed or approved the app. Re-audit when changing
SDKs, permissions, backups, support handling or sharing.

## Published documents

- Privacy: https://nuri.run/getagrip/privacy
- French privacy: https://nuri.run/getagrip/privacy/fr
- Terms: https://nuri.run/getagrip/terms
- French terms: https://nuri.run/getagrip/terms/fr
- Support: https://nuri.run/getagrip — support@nuri.run

These pages cover official Android and iOS builds, including betas. Android
Settings → Privacy and terms opens the matching language in an external browser.
The existing iOS privacy URL remains valid. No acceptance checkbox or permission
is needed simply to open the documents. Source lives in the existing
`plumbmybumb/Nuri_Portfolio_react` website repository, `app/getagrip/`.

## Data-flow audit of this build

| Feature | Implementation and destination |
| --- | --- |
| Training and preferences | Room database and DataStore; local routines, grips, results, timing, maximums and settings. No developer training backend or account. |
| Backup | `res/xml/data_extraction_rules.xml` includes the database and DataStore for system cloud backup/device transfer. Provider and device settings control availability. Not live sync, not Android–iOS sync. Export cache is excluded. |
| Network | The merged release manifest has no `INTERNET` permission. No advertising, analytics, automatic crash-report SDK, account SDK or billing SDK was found. |
| Gauges | Nearby-device permission; Bluetooth names/connection identifiers, readings, battery and link status. No location permission. |
| Background session | `SessionForegroundService` uses `connectedDevice`, with an ongoing session notification. |
| QR scan | Optional camera, on-device ZXing decoding. No camera-frame saving/uploading. |
| Reminders | Local scheduled notifications; no remote marketing push. |
| Exports and routine sharing | User chooses share recipient, file destination or clipboard. CSV can contain detailed fitness history and stable record IDs; routine QR/link contains the routine. Cache copies can remain until cleared. No automatic AI upload. |
| Support | `SupportMail.kt` opens an editable external email draft. Includes app/OS version, phone model, gauge and locale. Diagnostics are offered separately before inclusion. Nothing is sent until the user sends their email. |
| Developer-held data | Sent support messages, sender address, delivery metadata and chosen attachments. Provider processes email. Temporary in-app diagnostics are not automatically uploaded. |
| Website | External browser visits reach Vercel. Support/legal page source adds no analytics or advertising script. Ordinary host requests are separate from the training database. |
| Deletion | In-app record deletion; Android Clear storage/uninstall for local data. Users manage system backups and exported copies separately. Support data deletion by email. No app account exists to delete. |

## Play Console entries still to submit

Use the public English privacy URL in the app's privacy-policy field and retain
French links in localized listing copy. The policy is readable HTML, without
login, a PDF download or a consent wall. Google requires a public policy and
in-app access; the document covers storage, permissions, disclosures, retention
and deletion. [Google User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).

### Data safety

Do not infer “No data collected” just from the missing Internet permission.
In particular, users can send diagnostics to the developer through email.
Google distinguishes collection from sharing, excludes on-device-only processing,
and has an exception for expected, user-initiated sharing. The declaration is
package-wide, including other active versions. [Google's definitions and form](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).

Conservative draft for review in the actual console:

- Optional collection: **Email address**, **Emails**, and **Diagnostics** when a
  user sends a support report. Purpose: app functionality/support; also analytics
  in Google's broad sense when diagnosing performance and improving the app.
  This does not mean the app embeds an analytics SDK.
- These support messages are **not ephemeral**: they remain in the support inbox.
  Users can request deletion at support@nuri.run.
- Do not mark phone model or app version as a unique device ID. Do not mark
  local workout IDs as advertising or account identifiers.
- Review any actually requested screenshots, files or fitness records in support
  before finalizing additional data categories. The standard draft does not
  attach a workout, photo, exported file or force history automatically.
- No advertising sale/sharing. User-requested external sharing and email-service
  processing should be assessed using Google's stated exceptions; do not mark
  every share-sheet recipient as a developer advertising partner.
- System backup is controlled by the OS/provider. Do not describe it as a
  developer-run cloud database or assume every OEM backup is end-to-end encrypted.
- Do **not** certify “all collected data encrypted in transit” solely because
  the website uses HTTPS: arbitrary external email clients/routes and provider
  configurations have not been verified. Resolve that answer against the actual
  support delivery setup before submission.

These are preparation notes, not submitted form answers. The exact treatment of
external email and system backup should be confirmed against the console guidance
and actual support operations; retain the rationale with the release.

### Health declaration and listing

Declare **Health and fitness → Activity and Fitness**. This app records workouts;
“no health features” would be inaccurate. It does not provide diagnosis,
rehabilitation or regulated medical-device functionality. [Health declaration](https://support.google.com/googleplay/android-developer/answer/14738291?hl=en).

Include this paragraph in the English full description:

> Get a Grip is not a medical device and does not diagnose, treat, cure or prevent any medical condition. Consult a healthcare professional for medical advice. Live force measurement requires a compatible Bluetooth force gauge; timer-only sessions work without one. Experimental gauge support is identified in the app.

French equivalent:

> Get a Grip n’est pas un dispositif médical et ne diagnostique, ne traite, ne guérit ni ne prévient aucune affection. Consultez un professionnel de santé pour un avis médical. La mesure de force en direct nécessite un capteur Bluetooth compatible ; le mode chronomètre fonctionne sans capteur. Les compatibilités expérimentales sont signalées dans l’application.

The medical disclaimer and consultation reminder are also in Android Settings
and the published terms. Avoid injury-prevention or rehabilitation claims.
[Google Health Content and Services](https://support.google.com/googleplay/android-developer/answer/16679511?hl=en).

### Foreground service declaration

Declare **connectedDevice** for ongoing Bluetooth force measurements during a
user-started workout. Delaying/interruption loses live measurements and prevents
reliable workout timing. The user sees the session notification and can return
to end the session. Do not declare a location, microphone or camera foreground
service: the app does not run one.

Google requests a demonstration video. Record a consenting test session showing:
connect gauge, start workout, background app, show the ongoing notification,
return and end the session. Avoid exposing personal training history in the
video. [Foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).

## Operator obligations

The published policy is an operational commitment: use support information only
for the stated purposes, delete/de-identify correspondence after its purpose and
follow-up end unless limited legal retention applies, honor deletion/rights
requests, and obtain appropriate consent before necessary handling of sensitive
health details. Avoid soliciting medical records for ordinary debugging.

Keep publisher identity/contact details consistent with the Play developer
account. Review any applicable business/trader disclosures against the actual
publishing entity; no unverified postal address, registration number or consumer
mediator is invented in these documents. No lawyer review is claimed. The terms
preserve mandatory consumer rights and MPL-2.0 rights rather than imposing a
blanket injury waiver or overriding the source licence.

Update the policy and declarations before adding a new data destination or
SDK. Store forms, target-audience/content-rating choices, publisher verification
and the foreground-service video are separate release steps; publishing these
pages does not submit them to Google Play.
