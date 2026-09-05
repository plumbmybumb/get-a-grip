// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.asserter

/// Byte-literal helpers for the codec suites.
///
/// TRANSLATION NOTE: Swift writes `Data([0x05, 0xAA, …])` because `UInt8` literals
/// go up to 255. Kotlin's `byteArrayOf` takes SIGNED bytes, so every literal over
/// 0x7F would need a `.toByte()` and every fixture line would double in width —
/// `bytes(0x05, 0xAA, …)` is the same reading, written once.
fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/// The uppercase, unspaced hex the shared `Fixtures/codec/*.json` files carry.
fun hexBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have an even length: '$hex'" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/// `ByteArray` has reference equality under `assertEquals`, which passes for the
/// wrong reason exactly as often as it fails for the right one.
fun assertBytesEqual(expected: ByteArray, actual: ByteArray?, message: String? = null) {
    asserter.assertTrue(
        {
            "${message?.plus(". ") ?: ""}expected <${expected.toHexString()}>, " +
                "actual <${actual?.toHexString()}>"
        },
        actual != null && expected.contentEquals(actual),
    )
}
