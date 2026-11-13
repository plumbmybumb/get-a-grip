// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import run.nuri.getagrip.ble.FrezCoefficientResolver
import run.nuri.getagrip.ble.GaugeCalibration
import run.nuri.getagrip.ble.GaugeCalibrationAnswer
import run.nuri.getagrip.ble.GaugeCalibrationFailure
import run.nuri.getagrip.ble.GaugeCalibrationStatus
import run.nuri.getagrip.ble.ProgressorClientDiagnostic
import run.nuri.getagrip.engine.FrezDynoCodec
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.GaugeProtocolSource
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.GaugeClientRouting
import run.nuri.getagrip.store.GaugeClientShape
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// Everything around the Dyno codec: the registry row, the coefficient lookup, and how its
/// progress reaches the store. No Bluetooth and no network anywhere — the resolver takes a
/// transport, and the store takes a scripted client.
///
/// TRANSLATION NOTE: Robolectric, for one reason only — the coefficient cache is a real
/// `SharedPreferences`, the Android twin of the `UserDefaults` the Swift resolver takes,
/// and every test below gets its own file so nothing leaks between them. The iOS suite
/// does the same with a suite-named `UserDefaults`.
///
/// Translated from Tests/FrezDynoIntegrationTests.swift.
@RunWith(RobolectricTestRunner::class)
class FrezDynoIntegrationTests {

    // MARK: - Registry

    @Test
    fun theDynoIsAConnectedGaugeWithAClockThatNeedsACoefficient() {
        val caps = GaugeKind.frezdyno.capabilities
        assertTrue(caps.hasDeviceClock, "every record carries device elapsed time")
        assertFalse(caps.hasHardwareTare, "the zero is arithmetic in the codec")
        assertFalse(caps.isBroadcast)
        assertTrue(caps.hasStandardBattery)
        assertTrue(caps.sustainsBackgroundStreaming)
        assertEquals(250.0, caps.nominalSampleRate)
        assertTrue(caps.hardwareVerified, "verified on Nuri's Dyno, 2026-10-04")
        assertTrue(caps.requiresRemoteCalibration)
        assertEquals(GaugeProtocolSource.vendorDocumented, caps.protocolSource)

        assertEquals(FrezDynoCodec.profile, GaugeKind.frezdyno.gatt)
        assertNull(GaugeKind.frezdyno.makeFrameDecoder(), "no coefficient, no decoder")
        assertNotNull(GaugeKind.frezdyno.makeCalibratedFrameDecoder(1e-5))
        assertTrue(GaugeKind.selectable.contains(GaugeKind.frezdyno))
        // The Swift asserts the CLIENT's type; on Android the routing decision is the
        // seam a JVM test can reach — see `MultiGaugeStoreTests` for why.
        assertEquals(GaugeClientShape.gatt, GaugeClientRouting.shape(GaugeKind.frezdyno))
    }

    /// The coefficient path is the Dyno's alone: handing a slope to a gauge that reports
    /// kilograms itself must be impossible, not merely unused.
    @Test
    fun noOtherGaugeNeedsOrAcceptsACoefficient() {
        for (kind in GaugeKind.entries) {
            if (kind == GaugeKind.frezdyno) continue
            assertFalse(kind.capabilities.requiresRemoteCalibration, "$kind")
            assertNull(kind.makeCalibratedFrameDecoder(1e-5), "$kind")
        }
    }

    @Test
    fun protocolProvenanceIsStatedPerGauge() {
        assertEquals(
            GaugeProtocolSource.vendorDocumented,
            GaugeKind.progressor.capabilities.protocolSource,
        )
        assertEquals(
            GaugeProtocolSource.vendorDocumented,
            GaugeKind.frezdyno.capabilities.protocolSource,
        )
        val ported = listOf(
            GaugeKind.whc06, GaugeKind.entralpi, GaugeKind.forceboard, GaugeKind.climbro,
            GaugeKind.motherboard, GaugeKind.cts500, GaugeKind.pb700bt,
        )
        for (kind in ported) {
            assertEquals(GaugeProtocolSource.ported, kind.capabilities.protocolSource, "$kind")
        }
    }

    // MARK: - The coefficient lookup

    /// Records what was asked and answers what it was told to.
    private class TransportSpy {
        val requests = mutableListOf<FrezCoefficientResolver.Request>()
        var status = 200
        var body = """{"a": 0.000012345678}"""
        var error: Exception? = null

        val transport = FrezCoefficientResolver.Transport { request ->
            requests.add(request)
            error?.let { throw it }
            FrezCoefficientResolver.Response(statusCode = status, body = body)
        }
    }

    private fun freshPreferences(): SharedPreferences {
        val name = "frez-dyno-tests-${UUID.randomUUID()}"
        return RuntimeEnvironment.getApplication()
            .getSharedPreferences(name, Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
    }

    private fun cached(preferences: SharedPreferences, serial: String): Double? =
        preferences.getString(FrezCoefficientResolver.cacheKey(serial), null)?.toDoubleOrNull()

    @Test
    fun theKeyGoesInTheHeaderAndTheSerialInTheQuery() = runTest {
        val spy = TransportSpy()
        val resolver = FrezCoefficientResolver(
            accessKey = "k3y",
            preferences = freshPreferences(),
            transport = spy.transport,
        )
        val result = resolver.calibration("FrezDyno-000123")

        assertEquals(
            GaugeCalibrationAnswer.Resolved(
                GaugeCalibration(coefficient = 0.000012345678, cached = false),
            ),
            result,
        )
        val request = assertNotNull(spy.requests.firstOrNull())
        assertEquals(
            "https://api.frez.app/functions/v1/dyno-coefficient?serial=FrezDyno-000123",
            request.url,
        )
        assertEquals("k3y", request.headers["X-Frez-Access-Key"])
        assertEquals("application/json", request.headers["Accept"])
        assertEquals(5.0, request.timeoutSeconds)
    }

    /// Once per Dyno, ever: the second ask is answered from the device, and only a
    /// different serial spends another request.
    @Test
    fun aCoefficientIsFetchedOnceAndThenServedFromTheCache() = runTest {
        val spy = TransportSpy()
        val preferences = freshPreferences()
        val resolver = FrezCoefficientResolver("k3y", preferences, spy.transport)

        val first = resolver.calibration("FrezDyno-000123")
        val second = resolver.calibration("FrezDyno-000123")
        assertEquals(
            GaugeCalibrationAnswer.Resolved(GaugeCalibration(0.000012345678, cached = false)),
            first,
        )
        assertEquals(
            GaugeCalibrationAnswer.Resolved(GaugeCalibration(0.000012345678, cached = true)),
            second,
        )
        assertEquals(1, spy.requests.size)

        // A fresh resolver over the same preferences is the next launch.
        val relaunched = FrezCoefficientResolver("k3y", preferences, spy.transport)
        val third = relaunched.calibration("FrezDyno-000123")
        assertEquals(
            GaugeCalibrationAnswer.Resolved(GaugeCalibration(0.000012345678, cached = true)),
            third,
        )
        assertEquals(1, spy.requests.size)

        relaunched.calibration("FrezDyno-000124")
        assertEquals(2, spy.requests.size, "a different Dyno is a different question")
    }

    @Test
    fun aBuildWithoutAKeyNeverTouchesTheNetwork() = runTest {
        val spy = TransportSpy()
        val resolver = FrezCoefficientResolver(null, freshPreferences(), spy.transport)
        val result = resolver.calibration("FrezDyno-000123")
        assertEquals(
            GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.NoAccessKey),
            result,
        )
        assertTrue(spy.requests.isEmpty())
    }

    /// The cache is consulted BEFORE the key: a Dyno calibrated on a build that had one
    /// keeps working on a build that does not.
    @Test
    fun aCachedCoefficientOutlivesTheKey() = runTest {
        val spy = TransportSpy()
        val preferences = freshPreferences()
        preferences.edit()
            .putString(FrezCoefficientResolver.cacheKey("FrezDyno-000123"), "2.0E-5")
            .commit()
        val resolver = FrezCoefficientResolver(null, preferences, spy.transport)
        val result = resolver.calibration("FrezDyno-000123")
        assertEquals(
            GaugeCalibrationAnswer.Resolved(GaugeCalibration(0.00002, cached = true)),
            result,
        )
        assertTrue(spy.requests.isEmpty())
    }

    @Test
    fun frezStatusesMapToNamedFailuresAndCacheNothing() = runTest {
        val table = listOf(
            400 to GaugeCalibrationFailure.InvalidRequest,
            401 to GaugeCalibrationFailure.InvalidAccessKey,
            403 to GaugeCalibrationFailure.DeviceLimitReached,
            404 to GaugeCalibrationFailure.DeviceNotFound,
            409 to GaugeCalibrationFailure.OwnershipReview,
            422 to GaugeCalibrationFailure.CalibrationUnavailable,
            429 to GaugeCalibrationFailure.RateLimited,
            500 to GaugeCalibrationFailure.BadResponse,
        )
        for ((status, failure) in table) {
            val spy = TransportSpy()
            spy.status = status
            val preferences = freshPreferences()
            val resolver = FrezCoefficientResolver("k3y", preferences, spy.transport)
            val result = resolver.calibration("FrezDyno-000123")
            assertEquals(GaugeCalibrationAnswer.Unavailable(failure), result, "HTTP $status")
            assertNull(
                cached(preferences, "FrezDyno-000123"),
                "HTTP $status must not be remembered as a coefficient",
            )
        }
    }

    @Test
    fun aTransportErrorIsReportedAsNetworkAndCachesNothing() = runTest {
        val spy = TransportSpy()
        spy.error = java.net.UnknownHostException("api.frez.app")
        val preferences = freshPreferences()
        val resolver = FrezCoefficientResolver("k3y", preferences, spy.transport)
        val result = resolver.calibration("FrezDyno-000123")
        val failure = (result as? GaugeCalibrationAnswer.Unavailable)?.failure
        assertTrue(
            failure is GaugeCalibrationFailure.Network,
            "expected a network failure, got $result",
        )
        assertNull(cached(preferences, "FrezDyno-000123"))
    }

    /// A zero or negative slope would turn every pull into nothing or its opposite; that is
    /// a corrupt answer, not a calibration.
    @Test
    fun anUnusableCoefficientIsABadResponseNotACalibration() = runTest {
        assertEquals(
            0.000012345678,
            FrezCoefficientResolver.coefficient("""{"a": 0.000012345678}"""),
        )
        assertEquals(0.5, FrezCoefficientResolver.coefficient("""{"a": "0.5"}"""))
        assertNull(FrezCoefficientResolver.coefficient("""{"a": 0}"""))
        assertNull(FrezCoefficientResolver.coefficient("""{"a": -1e-5}"""))
        assertNull(FrezCoefficientResolver.coefficient("""{"a": "nan"}"""))
        assertNull(FrezCoefficientResolver.coefficient("""{"b": 1}"""))
        assertNull(FrezCoefficientResolver.coefficient("not json"))

        val spy = TransportSpy()
        spy.body = """{"a": 0}"""
        val resolver = FrezCoefficientResolver("k3y", freshPreferences(), spy.transport)
        val result = resolver.calibration("FrezDyno-000123")
        assertEquals(
            GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.BadResponse),
            result,
        )
    }

    // MARK: - A serial, a body and a wait that are not what they should be

    /// A serial is whatever the PERIPHERAL says it is, and any device can claim to be a
    /// Dyno. This one string becomes a preferences key and a request carrying the app's
    /// access key, so one that does not look like a serial number goes nowhere: not to the
    /// network, and not to the cache — even a cache that would have answered.
    @Test
    fun aSerialThatIsNotASerialTouchesNeitherTheNetworkNorTheCache() = runTest {
        val notSerials = listOf(
            "", " ", "Frez Dyno", "FrezDyno-000123\n", "FrezDyno/000123", "../frez",
            "x?serial=y", "a&b", "a#b", "a%20b", "sérial", "Frez\tDyno",
            "x".repeat(65), "x".repeat(5_000),
        )
        for (serial in notSerials) {
            assertFalse(FrezCoefficientResolver.isWellFormed(serial), "<$serial>")
            val spy = TransportSpy()
            val preferences = freshPreferences()
            // A coefficient sitting under exactly the key this string would make.
            preferences.edit().putString(FrezCoefficientResolver.cacheKey(serial), "2.0E-5").commit()
            val before = preferences.all.toMap()

            val result = FrezCoefficientResolver("k3y", preferences, spy.transport).calibration(serial)

            assertEquals(
                GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.InvalidRequest),
                result,
                "<$serial>",
            )
            assertTrue(spy.requests.isEmpty(), "<$serial> must not reach the network")
            assertEquals(before, preferences.all.toMap(), "<$serial> must not touch the cache")
        }

        // Letters, digits and the four separators serials use, up to 64 of them.
        for (serial in listOf("FrezDyno-000123", "A", "0", "AB:CD:EF_01.2-3", "x".repeat(64))) {
            assertTrue(FrezCoefficientResolver.isWellFormed(serial), serial)
            val spy = TransportSpy()
            val result = FrezCoefficientResolver("k3y", freshPreferences(), spy.transport).calibration(serial)
            assertTrue(result is GaugeCalibrationAnswer.Resolved, serial)
            assertEquals(1, spy.requests.size, serial)
        }
    }

    /// The answer is `{"a": 0.0000123}`. A body that keeps coming is not a calibration, and
    /// is not remembered as one.
    @Test
    fun aBodyLongerThanSixteenKilobytesIsABadResponse() = runTest {
        assertEquals(16 * 1024, FrezCoefficientResolver.maxResponseBytes)
        fun body(bytes: Int): String {
            val answer = """{"a": 0.5}"""
            return answer + " ".repeat(bytes - answer.length)
        }

        // On the line: sixteen kilobytes of valid JSON is still an answer.
        val onTheLine = TransportSpy().also { it.body = body(16 * 1024) }
        assertEquals(
            GaugeCalibrationAnswer.Resolved(GaugeCalibration(0.5, cached = false)),
            FrezCoefficientResolver("k3y", freshPreferences(), onTheLine.transport)
                .calibration("FrezDyno-000123"),
        )

        // One byte over, and a megabyte over, read the same — whatever the JSON says.
        for (bytes in listOf(16 * 1024 + 1, 1024 * 1024)) {
            val spy = TransportSpy().also { it.body = body(bytes) }
            val preferences = freshPreferences()
            val result = FrezCoefficientResolver("k3y", preferences, spy.transport)
                .calibration("FrezDyno-000123")
            assertEquals(
                GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.BadResponse),
                result,
                "$bytes bytes",
            )
            assertNull(cached(preferences, "FrezDyno-000123"), "$bytes bytes")
        }

        // Bytes, not characters: 6,000 three-byte characters are 18 KB.
        val wide = TransportSpy().also { it.body = """{"a": 0.5, "pad": "${"\u20AC".repeat(6_000)}"}""" }
        assertEquals(
            GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.BadResponse),
            FrezCoefficientResolver("k3y", freshPreferences(), wide.transport).calibration("FrezDyno-000123"),
        )

        // What the real transport reports when it stopped reading: no body, and the flag.
        val abandoned = FrezCoefficientResolver.Transport {
            FrezCoefficientResolver.Response(statusCode = 200, body = "", bodyTooLarge = true)
        }
        assertEquals(
            GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.BadResponse),
            FrezCoefficientResolver("k3y", freshPreferences(), abandoned).calibration("FrezDyno-000123"),
        )
        // A named status stays the named status: its body is never read as an answer.
        val limited = TransportSpy().also { it.status = 429; it.body = body(1024 * 1024) }
        assertEquals(
            GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.RateLimited),
            FrezCoefficientResolver("k3y", freshPreferences(), limited.transport).calibration("FrezDyno-000123"),
        )
    }

    /// The reader the transport uses: never more than the cap plus one byte, however much
    /// there is to read — the one extra is how "exactly the cap" is told from "more".
    @Test
    fun theBodyReaderStopsOneByteAfterTheCap() {
        val cap = FrezCoefficientResolver.maxResponseBytes

        assertEquals(0, FrezCoefficientResolver.readBody(ByteArray(0).inputStream())?.size)
        assertEquals(cap, FrezCoefficientResolver.readBody(ByteArray(cap) { 0x20 }.inputStream())?.size)
        assertNull(FrezCoefficientResolver.readBody(ByteArray(cap + 1) { 0x20 }.inputStream()))

        // A body with no end at all, counting what was actually taken from it.
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { served += 1; return 0x20 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                served += length
                buffer.fill(0x20, offset, offset + length)
                return length
            }
        }
        assertNull(FrezCoefficientResolver.readBody(endless))
        assertEquals(cap + 1L, served)

        // And a body that only drips cannot outlast the deadline: the reader asks before
        // every read, and stops the moment the answer is yes.
        var asked = 0
        val failure = kotlin.runCatching {
            FrezCoefficientResolver.readBody(endless, cap) { asked += 1; asked > 2 }
        }.exceptionOrNull()
        assertTrue(failure is java.net.SocketTimeoutException, "got $failure")
    }

    /// Five seconds for the WHOLE request. A transport that never answers is a network
    /// failure at the deadline, not a gauge screen held for as long as a server likes.
    @Test
    fun aRequestThatNeverAnswersFailsAtTheDeadline() = runTest {
        var sent = 0
        val silent = FrezCoefficientResolver.Transport {
            sent += 1
            kotlinx.coroutines.awaitCancellation()
        }
        val preferences = freshPreferences()
        val result = FrezCoefficientResolver("k3y", preferences, silent).calibration("FrezDyno-000123")

        val failure = (result as? GaugeCalibrationAnswer.Unavailable)?.failure
        assertTrue(failure is GaugeCalibrationFailure.Network, "expected a network failure, got $result")
        assertEquals(1, sent)
        assertEquals((FrezCoefficientResolver.timeoutSeconds * 1000).toLong(), testScheduler.currentTime)
        assertNull(cached(preferences, "FrezDyno-000123"))
    }

    /// A cancellation that merely SURFACES in the lookup — a transport's own timeout — is an
    /// answer (the request failed). Swallowed as a cancellation it would leave the screen
    /// on "looking up" for good. The lookup itself being cancelled is the opposite case:
    /// its link went away, and it must stop without answering or caching anything.
    @Test
    fun aTransportsOwnTimeoutIsAnAnswerAndACancelledLookupIsNot() = runTest {
        val impatient = FrezCoefficientResolver.Transport {
            kotlinx.coroutines.withTimeout(10) { kotlinx.coroutines.awaitCancellation() }
        }
        val result = FrezCoefficientResolver("k3y", freshPreferences(), impatient).calibration("FrezDyno-000123")
        assertTrue(
            (result as? GaugeCalibrationAnswer.Unavailable)?.failure is GaugeCalibrationFailure.Network,
            "expected a network failure, got $result",
        )

        val silent = FrezCoefficientResolver.Transport { kotlinx.coroutines.awaitCancellation() }
        val preferences = freshPreferences()
        var answer: GaugeCalibrationAnswer? = null
        val lookup = launch {
            answer = FrezCoefficientResolver("k3y", preferences, silent).calibration("FrezDyno-000123")
        }
        testScheduler.runCurrent()
        lookup.cancel()
        testScheduler.runCurrent()
        assertTrue(lookup.isCancelled)
        assertNull(answer, "a cancelled lookup reports nothing")
        assertNull(cached(preferences, "FrezDyno-000123"))
    }

    // MARK: - The store

    /// The client's calibration diagnostics become store state the screens read, and a
    /// breadcrumb that names the phase but never the serial.
    @Test
    fun calibrationDiagnosticsBecomeStoreStateAndASerialFreeBreadcrumb() {
        val client = FakeGaugeClient(GaugeKind.frezdyno)
        val store = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.connect()
        assertTrue(store.state.isConnected)

        client.onDiagnostic?.invoke(
            ProgressorClientDiagnostic.Calibration(GaugeCalibrationStatus.WaitingForSerial),
        )
        assertEquals(GaugeCalibrationStatus.WaitingForSerial, store.calibrationStatus)
        assertTrue(store.calibrationStatus.blocksForce)

        val calibration = GaugeCalibration(coefficient = 1e-5, cached = true)
        client.onDiagnostic?.invoke(
            ProgressorClientDiagnostic.Calibration(
                GaugeCalibrationStatus.Ready("FrezDyno-000123", calibration),
            ),
        )
        assertEquals(
            GaugeCalibrationStatus.Ready("FrezDyno-000123", calibration),
            store.calibrationStatus,
        )
        assertTrue(store.calibrationStatus.isReady)
        assertFalse(store.calibrationStatus.blocksForce)

        assertTrue(store.diagnosticEntries.any { it.text == "Calibration: ready (cached)" })
        assertFalse(
            store.diagnosticEntries.any { it.text.contains("000123") },
            "the ring travels in support mail and must not carry the serial",
        )

        client.onDiagnostic?.invoke(
            ProgressorClientDiagnostic.Calibration(
                GaugeCalibrationStatus.Failed(
                    "FrezDyno-000123",
                    GaugeCalibrationFailure.InvalidAccessKey,
                ),
            ),
        )
        assertEquals(
            GaugeCalibrationStatus.Failed(
                "FrezDyno-000123",
                GaugeCalibrationFailure.InvalidAccessKey,
            ),
            store.calibrationStatus,
        )
        assertTrue(store.diagnosticEntries.any { it.text == "Calibration: failed (401)" })

        client.disconnect()
        assertEquals(
            GaugeCalibrationStatus.NotRequired,
            store.calibrationStatus,
            "a coefficient belongs to a link",
        )
    }

    @Test
    fun everyOtherGaugeReportsCalibrationNotRequired() {
        val store = DeviceStore(
            client = FakeGaugeClient(GaugeKind.cts500),
            scope = inertScope(),
            clock = FakeClock(),
        )
        assertEquals(GaugeCalibrationStatus.NotRequired, store.calibrationStatus)
        assertFalse(store.calibrationStatus.blocksForce)
    }
}
