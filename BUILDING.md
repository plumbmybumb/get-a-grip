# Building and releasing

## iOS configuration

`project.yml` is the source of truth; XcodeGen regenerates `Doigt.xcodeproj`.
The default bundle, App Group, and CloudKit identifiers are the official app's
identities. They are public identifiers, not credentials. Never rename them in an
update to the official app: doing so can separate users from their saved data.

Forks use `project.local.yml` (ignored by Git), based on the supplied example.
`GETAGRIP_APP_GROUP` and `GETAGRIP_CLOUD_CONTAINER` flow into both entitlements and
runtime configuration. Use your own group/container in Apple's developer portal,
and give the app and Live Activity extension the same App Group. Simulator builds
can run without these capabilities. Real CloudKit sync needs a signed device build;
deploy its schema to your own Production environment before distributing it.

Use `DEVELOPER_DIR` to select an Xcode installation and `SIM_UDID` to choose an
installed iOS 26+ iPhone simulator. Tests launch a simulator; close it when finished.
Do not pass `-mockDevice` for real gauge verification. The in-app demo remains
available to people without hardware.

The app icon's procedural source is `scripts/make_app_icon.swift`. Run it with an
output PNG path and `any`, `dark` or `tinted`. The tinted palette is grayscale for
the system's tint treatment; the script rejects unknown styles instead of silently
writing the light variant.

## Android configuration

`android/gradle/libs.versions.toml` pins library versions. The wrapper pins Gradle
and its distribution checksum. `android/gradle.properties` owns the app version.
`ANDROID_HOME` or an ignored `local.properties` supplies the SDK location.

Local release builds are optimized and signed with the local Android debug key.
They are suitable for device testing, not Play uploads. For a store release, set all
four Gradle properties below in your private `~/.gradle/gradle.properties` or supply
them through your release environment. Never commit key material or passwords.

```properties
getagrip.keystore=/absolute/path/to/your-upload-key.jks
getagrip.keyAlias=your-alias
getagrip.storePassword=your-private-password
getagrip.keyPassword=your-private-password
```

Use `./android/build.sh :app:bundleRelease` for an app bundle after configuring your
own upload key. A fork must also choose its own `applicationId` before distribution.
Do not change the official application's ID or signing key for existing users.

## Cross-platform checks

```sh
./Fixtures/tools/oracle/build.sh
./Fixtures/tools/oracle/build/oracle runner verify Fixtures
python3 android/scripts/xcstrings_to_android.py
```

The oracle defaults to this repository's Swift source; `GETAGRIP_IOS_TREE` can
explicitly select another checkout. Fixtures are synthetic and checked into source.
Regenerate only when intentionally changing the contract, then review the results.
Do not add actual training exports, personal diagnostics, or device identifiers.

## Before a public release

Run both test suites, compare the cross-platform fixtures, inspect the merged
Android manifest, and test camera permission/scan flow and Bluetooth on hardware.
Keep MPL and dependency notices bundled with binaries. Update dependency notices
when changing libraries. Tag the exact source commit used for each shipped version,
with separate platform tags if their store versions differ.

For iOS, inspect the entitlements in the **final distribution-signed export**, not
only the source file or a development-signed archive. Run
`codesign -d --entitlements :- /path/to/export/Payload/Doigt.app` and confirm
`aps-environment` is `production`, the CloudKit container environment is `Production`,
and the bundle, App Group and CloudKit identifiers match the intended application.
Do this for Organizer exports and automated `xcodebuild -exportArchive` releases.
Do not distribute an export with development push entitlements.
[Apple derives the APNs environment from the provisioning profile](https://developer.apple.com/documentation/bundleresources/entitlements/aps-environment).
The source's development default supports local signing; it is not evidence of the
environment in the exported app. SwiftData's CloudKit mirroring requires the
[Remote notifications background capability](https://developer.apple.com/documentation/SwiftData/Syncing-model-data-across-a-persons-devices).
