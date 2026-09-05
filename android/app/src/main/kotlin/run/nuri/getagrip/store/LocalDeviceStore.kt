// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/// The house store injection, `.environment()`'s twin.
///
/// `staticCompositionLocalOf` rather than `compositionLocalOf`: the store instance never
/// changes for the life of the composition — what changes is the snapshot state INSIDE it,
/// which readers observe on their own. A dynamic local would re-run every reader of this
/// local whenever the value was re-provided, for a value that is provided once.
///
/// Reading it without a provider is a programming error, not a case to be handled: every
/// screen that draws a gauge lives under `MainActivity`'s provider.
val LocalDeviceStore: ProvidableCompositionLocal<DeviceStore> = staticCompositionLocalOf {
    error("LocalDeviceStore was read outside a CompositionLocalProvider")
}
