// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/// The gauge codecs, asserted from `Fixtures/codec/*.json` — the same files the
/// Swift suite reads. Every byte vector in here was lifted from the three XCTest
/// codec files, so a rule that changes on one platform and not the other fails on
/// BOTH. See `Fixtures/README.md` for the schemas.
///
/// Doubles are compared with a 1e-6 tolerance throughout: the Swift assertions that
/// carry an `accuracy:` want exactly that, and the ones that do not are values (26.00,
/// 20.0, 60.0) that land exactly anyway — so one rule costs nothing and cannot drift
/// out of step with which assertion used which.
class CodecFixtureTests {

    private val tolerance = 1e-6

    @Test
    fun everyGaugeFixtureReproducesItsDecoder() {
        val files = Fixtures.files("codec")
        assertTrue(files.isNotEmpty(), "no fixtures found under Fixtures/codec")

        for (file in files) {
            val doc = Fixtures.load("codec/${file.name}").jsonObject
            val gauge = doc.getValue("gauge").jsonPrimitive.content
            assertEquals(
                file.name.removeSuffix(".json"), gauge,
                "a fixture file must be named for the gauge it describes",
            )

            for (case in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
                val where = "$gauge / ${case.getValue("name").jsonPrimitive.content}"
                val frames = case.getValue("frames").jsonArray.map {
                    hexBytes(it.jsonPrimitive.content)
                }
                val expect = case.getValue("expect").jsonObject

                when (gauge) {
                    "progressor" -> checkProgressor(case, frames, expect, where)
                    "whc06" -> checkBroadcast(frames, expect, where)
                    else -> checkFramed(gauge, frames, expect, where)
                }
            }
        }
    }

    /// The Progressor decoder is stateless, so every frame is decoded on its own with
    /// the same pending command and the events concatenate — which is exactly what the
    /// BLE layer does with a run of notifications.
    private fun checkProgressor(
        case: JsonObject,
        frames: List<ByteArray>,
        expect: JsonObject,
        where: String,
    ) {
        val answering = case["answering"]?.jsonPrimitive?.content?.let { raw ->
            ProgressorCommand.entries.firstOrNull { it.name == raw }
                ?: fail("$where: unknown pending command '$raw'")
        }

        val actual = frames.flatMap { ProgressorCodec.decode(it, answering = answering) }
        val expected = expect.getValue("events").jsonArray.map { it.jsonObject }

        assertEquals(expected.size, actual.size, "$where: event count")
        for ((index, want) in expected.withIndex()) {
            val got = actual[index]
            val at = "$where: event $index"
            when (val type = want.getValue("type").jsonPrimitive.content) {
                "sample" -> {
                    val sample = (got as? ProgressorEvent.Sample)?.sample
                        ?: fail("$at: expected a sample, got $got")
                    assertEquals(want.getValue("kg").jsonPrimitive.double, sample.kg, tolerance, at)
                    assertEquals(
                        want.getValue("micros").jsonPrimitive.long.toUInt(),
                        sample.deviceMicros, at,
                    )
                    assertEquals(
                        want.getValue("batchStart").jsonPrimitive.boolean,
                        sample.isBatchStart, at,
                    )
                }
                "battery" -> assertEquals(
                    ProgressorEvent.Battery(want.getValue("millivolts").jsonPrimitive.long.toUInt()),
                    got, at,
                )
                "batteryFraction" -> assertEquals(
                    ProgressorEvent.BatteryFraction(want.getValue("fraction").jsonPrimitive.double),
                    got, at,
                )
                "appVersion" -> assertEquals(
                    ProgressorEvent.AppVersion(want.getValue("text").jsonPrimitive.content), got, at,
                )
                "errorInformation" -> assertEquals(
                    ProgressorEvent.ErrorInformation(want.getValue("text").jsonPrimitive.content),
                    got, at,
                )
                "rfdPeak" -> assertEquals(
                    ProgressorEvent.RfdPeak(
                        kg = want.getValue("kg").jsonPrimitive.double,
                        micros = want.getValue("micros").jsonPrimitive.long.toUInt(),
                    ),
                    got, at,
                )
                "lowPowerWarning" -> assertEquals(ProgressorEvent.LowPowerWarning, got, at)
                // `raw` is `.unknown(tag:payload:)`: a tag this build has never heard of,
                // or a block whose shape its own tag forbids.
                "raw" -> assertEquals(
                    ProgressorEvent.Unknown(
                        tag = want.getValue("tag").jsonPrimitive.int,
                        payload = hexBytes(want.getValue("payload").jsonPrimitive.content),
                    ),
                    got, at,
                )
                // A tag-0 reply with nothing pending to attribute it to.
                "commandResponse" -> assertEquals(
                    ProgressorEvent.CommandResponse(
                        hexBytes(want.getValue("payload").jsonPrimitive.content),
                    ),
                    got, at,
                )
                else -> fail("$at: unknown event type '$type'")
            }
        }
    }

    /// One decoder instance for the whole case, frames in order — the reassembly
    /// buffers and the Motherboard's calibration table are the point of the fixture.
    private fun checkFramed(
        gauge: String,
        frames: List<ByteArray>,
        expect: JsonObject,
        where: String,
    ) {
        val kind = GaugeKind.fromRaw(gauge) ?: fail("$where: unknown gauge")
        val decoder = kind.makeFrameDecoder() ?: fail("$where: gauge has no frame decoder")

        val actual = frames.flatMap { decoder.ingest(it) }
        val expected = expect.getValue("readings").jsonArray.map { it.jsonObject }

        assertEquals(expected.size, actual.size, "$where: reading count")
        for ((index, want) in expected.withIndex()) {
            val at = "$where: reading $index"
            assertEquals(want.getValue("kg").jsonPrimitive.double, actual[index].kg, tolerance, at)
            val micros = want["micros"]
            if (micros == null || micros is JsonNull) {
                assertNull(actual[index].deviceMicros, "$at: a codec must never invent a stamp")
            } else {
                assertEquals(micros.jsonPrimitive.long.toUInt(), actual[index].deviceMicros, at)
            }
        }

        val battery = expect["battery"]
        if (battery == null || battery is JsonNull) {
            assertNull(decoder.batteryFraction, "$where: battery")
        } else {
            assertEquals(
                battery.jsonPrimitive.double,
                assertNotNull(decoder.batteryFraction, "$where: battery"),
                tolerance, "$where: battery",
            )
        }
    }

    /// The WH-C06 has no decoder at all: each frame is a manufacturer-data blob and
    /// the answer is one kilogram figure, or nil for "not our advertisement".
    private fun checkBroadcast(frames: List<ByteArray>, expect: JsonObject, where: String) {
        val kg = expect["kg"]
        for (frame in frames) {
            val actual = WHC06Codec.kilogramsFromManufacturerData(frame)
            if (kg == null || kg is JsonNull) {
                assertNull(actual, where)
            } else {
                assertEquals(kg.jsonPrimitive.double, assertNotNull(actual, where), tolerance, where)
            }
        }
    }

    /// Every gauge in the registry has a fixture file, so adding a device cannot
    /// quietly skip the cross-platform contract.
    @Test
    fun everyGaugeKindHasAFixtureFile() {
        val present: List<String> = Fixtures.files("codec").map { it.name.removeSuffix(".json") }
        for (kind in GaugeKind.entries) {
            assertTrue(present.contains(kind.rawValue), "no Fixtures/codec/${kind.rawValue}.json")
        }
    }
}
