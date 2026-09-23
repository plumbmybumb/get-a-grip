// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/// The house store injection, `.environment()`'s twin. `staticCompositionLocalOf` because
/// the instance never changes; readers observe the snapshot state INSIDE it, and a dynamic
/// local would re-run readers on re-provision for nothing.
///
/// No provider is a programming error: every gauge screen lives under `MainActivity`'s
/// provider.
val LocalDeviceStore: ProvidableCompositionLocal<DeviceStore> = staticCompositionLocalOf {
    error("LocalDeviceStore was read outside a CompositionLocalProvider")
}
