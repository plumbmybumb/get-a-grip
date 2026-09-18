# Frez Dyno

Status: implemented on both platforms from Frez's published Dyno API. **Unverified on
hardware** until a Dyno has pulled on this app; the gauge picker says so.

## What is different about this gauge

Every other supported gauge reports kilograms. The Dyno streams signed raw ADC counts
with its own clock, nine to a 74-byte notification at 250 Hz, and leaves the conversion
to the client:

```
tare_adc  = average(first 100 unloaded samples)
weight_kg = a × (raw_adc − tare_adc)
```

`a` is a per-device slope that only Frez's coefficient API knows, looked up by the
unit's serial number (Device Information › Serial Number String, `FrezDyno-######`).

## Where the pieces live

| Concern | iOS | Android |
| --- | --- | --- |
| Wire protocol, tare, conversion, clock rules | `Shared/Engine/FrezDynoCodec.swift` | `android/engine/…/FrezDynoCodec.kt` |
| Registry row and capabilities | `Shared/Engine/GaugeKind.swift` | `android/engine/…/GaugeKind.kt` |
| Serial read, coefficient gate, decoder minting | `Sources/BLE/GattGaugeClient.swift` | `android/app/…/ble/GattGaugeClient.kt` |
| Coefficient API client, cache, failure vocabulary | `Sources/BLE/FrezCalibration.swift` | `android/app/…/ble/FrezCalibration.kt` |
| Calibration status for the screens | `DeviceStore.calibrationStatus` | `DeviceStore.calibrationStatus` |
| The maker's note, shown once | `Sources/UI/FrezIntroSheet.swift` | `android/app/…/ui/settings/FrezIntroSheet.kt` |
| Cross-platform contract | `Fixtures/codec/frezdyno.json` | same file, asserted by `CodecFixtureTests.kt` |

## Rules that are not obvious from the code

- **The tare is taken once per connection, never per Start.** The app re-sends Start
  routinely (silence watchdog, foreground return, tare recovery) and Start resets the
  device clock; a tare keyed to "the first samples after Start" would re-zero the gauge
  under whatever load was on it, typically a hang. The count offset belongs to the load
  cell, so the zero taken at link-up stays good, and the Tare button's app-side capture
  sits on top of it. A reconnect is a new decoder and a fresh zero, as Frez asks.
- **Nothing is shown until the zero exists.** The first hundred accepted samples (0.4 s)
  produce no readings, so keep the Dyno unloaded while connecting.
- **Timestamps.** `elapsed_ms` becomes the engine's device clock (`× 1000`, wrapping at
  the same ~71.6 minutes as the Tindeq's), so the timeline survives Bluetooth jitter.
  Duplicate and backwards timestamps are dropped, except a jump back of at least 250 ms
  that lands under 250 ms, which is the clock restarting on a Start.
- **No coefficient, no force.** Until the lookup succeeds the client mints no decoder;
  the gauge screen and the runner say why (looking up, no key, Frez's own error table).
- **One request per unit, ever.** The coefficient is cached on the device by serial and
  the network is never asked again for that Dyno. A cached coefficient is used even by a
  build without a key. Nothing network-related exists for any other gauge, and the
  session used for the request is created for it and invalidated after it.
- **The access key is private.** `FREZ_ACCESS_KEY` is empty in `project.yml`; the
  official build supplies it from the ignored `project.local.yml`, Android from the
  private Gradle property `getagrip.frezAccessKey`. See BUILDING.md. A key compiled into
  a shipped binary can be extracted; it is a usage credential with device and rate
  limits, not a secret that protects anyone's data.
- **The maker's note is shown once.** The first selection of the Dyno in the gauge
  picker presents Donghyun Kim's note, verbatim and in English in every locale, one
  paragraph per line as he laid it out (his review, 2026-09-18), with Next as the only
  way through; `frezIntroSeen` is then persisted. No other gauge shows it.

## Hardware checks still owed

1. Connect a real Dyno: serial read, coefficient fetch (watch Settings › Diagnostics
   for the `Calibration:` breadcrumbs), first readings after the 0.4 s tare.
2. Compare a known load against the Frez app. Frez states the linear model deviates by
   roughly 0.3 % from their own higher-order calibration.
3. Confirm the 250 Hz stream stays smooth on the runner screen and the trace shows six
   seconds (the trace buffer scales with the sample rate).
4. Background a session and return; confirm the stream re-kick does not re-tare.
5. Android only: confirm the MTU 85 request is granted and no truncated frames are
   logged.

## Privacy and legal

Done in this branch: the in-app agreement is revised to `2026-09-18` (the old revision
is archived, both loaders point at the new file, and no re-accept is forced because
the agreement no longer gates training). The privacy policy now states the Internet
permission's single purpose, describes the lookup (what is sent, to whom, when, what
is kept), names Frez among the providers, and the terms say a manufacturer's
calibration service is outside the developer's control. The iOS privacy manifest
declares one collected data type (Other Data Types, app functionality, not linked, no
tracking). `docs/ANDROID_RELEASE_PRIVACY.md` carries the Android side.

Still outside this repository: the published copies at nuri.run/getagrip/privacy and
/terms (and their French pages) must be regenerated from
`Legal/current/agreement-2026-09-18.json`, because the website's version has to be
identical to the bundled one.

## Store submissions

The apps now make one network request that a third party answers, so both store
privacy declarations change. Everything below is what to enter; none of it is
submitted from this repository.

**App Store Connect › App Privacy.** The app can no longer be declared as "Data Not
Collected". Add one data type: **Other Data Types**, with the description "the serial
number of a connected Frez Dyno force gauge, sent to the gauge's manufacturer to fetch
its calibration". Purpose: **App Functionality**. Not linked to the user's identity.
Not used for tracking. Nothing else changes: no accounts, no analytics, no advertising.
Export compliance is unchanged (standard HTTPS is exempt). In the review notes, mention
that the Frez note shown on first selection is a disclosure the hardware maker requires
and contains no purchase link or button, and that reviewers can use demo mode since
the Dyno needs hardware.

**Google Play Console › Data safety.** Declare data **shared** (not collected by the
developer): **Device or other IDs** — the serial number of a Frez Dyno — purpose
**App functionality**, optional (only when the user selects that gauge), encrypted in
transit, not ephemeral on the third party's side, deletion requests go to Frez. The
`INTERNET` permission needs no permissions declaration. Everything else in the
existing draft stands; see `ANDROID_RELEASE_PRIVACY.md`.

**Both stores.** Point the privacy-policy field at the regenerated web pages after
they carry the 2026-09-18 text.
