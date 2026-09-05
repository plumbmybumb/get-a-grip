# Get a Grip contributor notes

Native SwiftUI and Jetpack Compose implementations share data formats, grip keys,
and synthetic fixtures. Keep Shared/Engine and android/engine free of platform UI,
Bluetooth, and persistence framework dependencies. Grips are inline values, not a
separate library. project.yml is the iOS project source of truth.

Preserve official app, App Group, CloudKit, and storage identities. Forks configure
their own identities using project.local.yml. Do not commit credentials or real
training exports. Preserve upstream attribution and MPL-covered source notices.

Work timing uses device timestamp deltas where available, including UInt32 wrap.
Never substitute arrival time for measured time. Preserve tare integrity, release
gates, serialized untagged query replies, and connection-epoch cleanup. Gate gauge
behavior on capabilities. Unverified protocols must remain labeled unverified.

Use shared Motion tokens, honor reduced motion and accessibility text sizes, keep
backgrounds static, and keep feedback out of the live metrics' space. Sound must
respect the existing setting and must not interrupt other audio.

Run platform tests for changed code, and shared replay fixtures when changing
engine behavior or formats. See README.md and BUILDING.md for commands. Never
replace fixture expectations merely to hide a failing test.
