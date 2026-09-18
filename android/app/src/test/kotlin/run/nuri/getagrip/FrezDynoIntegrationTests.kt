// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import android.content.Context
import android.content.SharedPreferences
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
        assertFalse(caps.hardwareVerified, "unverified until a Dyno has pulled on this app")
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
            "https://api.frez.app/v1/dyno/coefficient?serial=FrezDyno-000123",
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
