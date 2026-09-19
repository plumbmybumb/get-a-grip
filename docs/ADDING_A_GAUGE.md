# Adding a gauge

One codec file, one row in each switch, one fixture. Adding a device is never a refactor,
and if it starts to feel like one, something is being keyed to the device where it should
be keyed to a capability.

Two rules hold that shape, and both come from `AGENTS.md`:

- **UI and runner behaviour gate on `GaugeCapabilities`, never on the kind.** A rule keyed
  to a capability survives the next device; a rule keyed to a device name is a bug waiting
  in the one after.
- **An unverified protocol stays labelled unverified.** Whether the bytes came from the
  maker or from a port is a different fact from whether anyone here has watched the device
  work, and the app says both.

[FREZ_DYNO.md](FREZ_DYNO.md) is the worked example of the hardest case — raw counts, a
per-unit coefficient fetched by serial, and a maker's note shown once.

## Where the pieces live

| Concern | iOS | Android |
| --- | --- | --- |
| Wire protocol, framing, conversion | `Shared/Engine/<Name>Codec.swift` | `android/engine/…/<Name>Codec.kt` |
| Registry row, capabilities, GATT profile | `Shared/Engine/GaugeKind.swift` | `android/engine/…/GaugeKind.kt` |
| Scan matching and connection | `Sources/BLE/GattGaugeClient.swift` | `android/app/…/ble/GattGaugeClient.kt` |
| Broadcast-only devices | `Sources/BLE/BroadcastGaugeClient.swift` | `android/app/…/ble/BroadcastGaugeClient.kt` |
| Cross-platform contract | `Fixtures/codec/<rawValue>.json` | the same file, read by `CodecFixtureTests.kt` |
| Names shown to a person | `Sources/Localizable.xcstrings` | generated from it |

## The steps

1. **Choose the raw value first, and treat it as a storage format.** `GaugeKind` is
   `String`-backed and `DeviceStore` persists the raw string, so renaming a case later
   resets somebody's chosen gauge to the Progressor on their next launch.
   `Tests/MultiGaugeStoreTests.swift` pins every raw value for exactly that reason —
   `testGaugeKindRawValuesArePinnedBecauseTheyArePersisted` — and the new case goes in
   there with the others. The same string names the fixture file and appears in it.

2. **Write the Swift codec** as `Shared/Engine/<Name>Codec.swift`. Nothing in `Shared/`
   may import CoreBluetooth, SwiftData or any UI framework: the codec is pure values, so
   it compiles into the widget and the watch and decodes on any machine. Three rules the
   existing codecs all follow, each of which has a test:
   - The decoder is **stateful and per-connection** — some protocols split a frame across
     notifications — and it dies with the link, so no half-frame can pair with the next
     connection's bytes.
   - **Truncated or malformed input stops the walk cleanly** and returns whatever decoded
     whole. Real BLE delivers short reads.
   - **Never invent a timestamp.** Only the Progressor and the Dyno carry a device clock;
     everything else returns `deviceMicros: nil` and the BLE client stamps readings with
     `SyntheticSampleClock` at ingestion. A codec that fabricates a stamp is inventing
     hang time.
   Expose a `GaugeGattProfile` as `<Name>Codec.profile` (service, notify and write UUIDs,
   start/stop payloads, any one-time setup writes) unless the device is broadcast-only.

3. **Write the Kotlin twin** as `android/engine/…/<Name>Codec.kt`: same file name, same
   type names, and the comments carried over, because the comments are the spec.

4. **Add the registry row** in `Shared/Engine/GaugeKind.swift`. The enum case, then every
   switch in the file — the compiler finds them all, which is the point of the design:
   - `displayName` and `maker` (both `String(localized:)`);
   - `capabilities` — see step 6;
   - `gatt` — the profile from step 2, or nil for a broadcast device or one with its own
     client;
   - `makeFrameDecoder()` — the decoder, or nil if it needs a coefficient;
   - `makeCalibratedFrameDecoder(coefficient:)` — only for a gauge whose stream is raw
     counts; nil everywhere else, because handing a coefficient to a device that already
     reports kilograms is a mistake with a name;
   - `selectable` — the picker's order. Leaving a kind out must be **a decision with a
     name**: `testEverySelectableKindIsReachableAndNoneIsListedTwice` compares `selectable`
     against `allCases` minus an explicit exclusion list, so an oversight fails the suite.
     The only current exclusion is the PB-700BT, whose stream is RPM rather than force.

5. **Mirror all of step 4 in `android/engine/…/GaugeKind.kt`** — the same case, the same
   raw value, the same switches, `L10n.tr` where Swift has `String(localized:)`.

6. **Set the capability flags honestly.** `hasDeviceClock`, `hasHardwareTare`,
   `isBroadcast`, `hasStandardBattery`, `sustainsBackgroundStreaming`,
   `nominalSampleRate`, `hardwareVerified`, `requiresRemoteCalibration` and
   `protocolSource`. The last two facts are what the app tells people: Settings builds its
   gauge notes **from these flags**, on both platforms
   (`Sources/UI/SettingsView.swift`, `android/app/…/ui/settings/SettingsScreen.kt`), so a
   device is marked verified by flipping `hardwareVerified` after somebody has pulled on
   one — never by writing a sentence.

7. **Teach the scan to find it.** `nameHints(for:)` in `Sources/BLE/GattGaugeClient.swift`
   and `nameHints` in `android/app/…/ble/GattGaugeClient.kt` must get the new case; both
   switches are exhaustive. CoreBluetooth cannot filter a scan by name, and whether a
   given device advertises its primary service is usually unverified, so the scan is
   unfiltered and each hit is matched two ways: advertised service UUID, or advertised
   name against these hints. A broadcast-only device is matched on manufacturer data in
   `BroadcastGaugeClient` instead and takes no hints.

8. **Add `Fixtures/codec/<rawValue>.json`.** The file name, its `gauge` field and the raw
   value are one string, and `everyGaugeKindHasAFixtureFile` fails the Android engine
   suite when a kind has no file — adding a device cannot skip the cross-platform
   contract. Record real frames if you have the hardware; otherwise transcribe the vectors
   the protocol source documents and say so. Schemas are in
   [Fixtures/README.md](../Fixtures/README.md).

9. **Put the names through the catalogs.** `displayName` and `maker` are user-facing, so
   they go into `Sources/Localizable.xcstrings` with English and French, and Android's
   catalog is regenerated — never hand-edited — with
   `python3 android/scripts/xcstrings_to_android.py`.

10. **If the protocol is ported, carry its licence with it.** The ported codecs open with
    `// SPDX-License-Identifier: MPL-2.0 AND BSD-2-Clause`, name the upstream project and
    its copyright, and state plainly that the code is a transcription rather than
    something anyone here has watched the device send — `Shared/Engine/ClimbroCodec.swift`
    is the pattern to copy. A new upstream also needs its licence text in `LICENSES/` and
    an entry in `THIRD_PARTY_NOTICES.txt`.

11. **Update the device list in [README.md](../README.md)** — it names every supported
    gauge and separates verified support from unverified ports. A gauge that needs more
    than a line (a network lookup, a maker's note, a different shape of device) gets its
    own page under `docs/`, listed in [docs/README.md](README.md).

12. **What you should NOT have to touch.** `DeviceStore.makeClient(for:)` picks the client
    from `gatt` and `capabilities.isBroadcast` alone, and the runner reads capabilities
    rather than kinds. If a new gauge seems to need a branch in either, that branch is a
    missing capability flag wearing a device's name.

## Before the pull request

```sh
./build.sh test
./android/build.sh test
./Fixtures/tools/oracle/build.sh
./Fixtures/tools/oracle/build/oracle runner verify Fixtures
```

Say in the pull request which device you have in hand and which you do not. A port with no
hardware behind it is welcome and is how most of this registry was built — it is only
unwelcome when it is described as verified. Keep `hardwareVerified: false` until somebody
has pulled on one and compared the reading with a number they already trust, and do not
change another gauge's flags in the same change.
