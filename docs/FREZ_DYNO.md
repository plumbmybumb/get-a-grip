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
| The maker's note, shown once | `Sources/UI/FrezIntroSheet.swift` | the gauge picker in `SettingsScreen.kt` |
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
  picker presents Donghyun Kim's note, verbatim and in English in every locale, with
  Next as the only way through; `frezIntroSeen` is then persisted. No other gauge
  shows it.

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

## Privacy and legal follow-ups (owner decisions)

- The in-app legal agreement states that the Android app does not request Internet
  access. The Dyno's calibration lookup needs the `INTERNET` permission, so the
  agreement text needs a new revision and a re-accept on both platforms.
- The lookup sends the Dyno's serial number to Frez's server. The privacy text should
  say so, and the store privacy declarations should be reviewed.
