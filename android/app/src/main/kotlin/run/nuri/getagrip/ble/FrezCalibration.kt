// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import run.nuri.getagrip.BuildConfig
import run.nuri.getagrip.engine.L10n
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Remote calibration for a gauge whose stream is raw counts (`requiresRemoteCalibration`).
//
// The Frez Dyno sends signed ADC values and leaves the conversion to the client, with
// one per-device slope that only Frez's coefficient API knows. This file is the ONLY
// place the app talks to a server that is not the platform's own, so its rules are
// spelled out:
//
// - Nothing here runs unless the selected gauge needs it AND a unit is connected. Every
//   other gauge never constructs any of it.
// - One request per serial, EVER. The slope is a property of the load cell; once it is
//   in hand it is cached on the device and the network is never asked again for that
//   Dyno. That is also what keeps the account's device and rate limits honest.
// - The connection is one-shot: opened for the request, disconnected after it. No
//   networking stack lives in the process between requests.
// - The access key is a build setting that lives only in a private Gradle property (see
//   app/build.gradle.kts and BUILDING.md). A build without one still connects and says
//   why there is no force.
// - Failure is fail-closed, as Frez asks: no coefficient, no calibrated force, and the
//   reason is shown rather than a guess at a number.

/// The per-device slope, and where it came from.
data class GaugeCalibration(
    val coefficient: Double,
    /// Answered from the on-device cache, with no request made.
    val cached: Boolean,
)

/// Why calibrated force is, or is not, available. Published by the store so the gauge
/// screen and the runner can say so instead of showing a silent 0.0 kg.
///
/// TRANSLATION NOTE: Swift's enum with associated values becomes a sealed interface, as
/// every other one in this package does.
sealed interface GaugeCalibrationStatus {
    /// Every gauge that reports kilograms itself.
    data object NotRequired : GaugeCalibrationStatus

    /// Connected; the serial read is in flight.
    data object WaitingForSerial : GaugeCalibrationStatus

    data class Resolving(val serial: String) : GaugeCalibrationStatus

    data class Ready(val serial: String, val calibration: GaugeCalibration) : GaugeCalibrationStatus

    data class Failed(
        val serial: String?,
        val failure: GaugeCalibrationFailure,
    ) : GaugeCalibrationStatus

    val isReady: Boolean get() = this is Ready

    /// True while a calibrated gauge cannot produce force — the state the UI must explain.
    val blocksForce: Boolean
        get() = when (this) {
            NotRequired, is Ready -> false
            WaitingForSerial, is Resolving, is Failed -> true
        }
}

/// Frez's documented error table, plus the two failures that happen before a request.
sealed interface GaugeCalibrationFailure {
    /// The Serial Number characteristic was absent, unreadable or empty.
    data object MissingSerial : GaugeCalibrationFailure

    /// This build carries no access key (forks, CI, a local build without the property).
    data object NoAccessKey : GaugeCalibrationFailure

    /// 400 invalid_request
    data object InvalidRequest : GaugeCalibrationFailure

    /// 401 invalid_access_key
    data object InvalidAccessKey : GaugeCalibrationFailure

    /// 403 device_limit_reached / developer_device_not_allowlisted
    data object DeviceLimitReached : GaugeCalibrationFailure

    /// 404 device_not_found
    data object DeviceNotFound : GaugeCalibrationFailure

    /// 409 device_ownership_review_required
    data object OwnershipReview : GaugeCalibrationFailure

    /// 422 coefficient_request_failed
    data object CalibrationUnavailable : GaugeCalibrationFailure

    /// 429 service_unavailable
    data object RateLimited : GaugeCalibrationFailure

    /// A 200 whose body carried no usable `a`, or an unexpected status.
    data object BadResponse : GaugeCalibrationFailure

    /// The request never completed: offline, timed out, TLS.
    data class Network(val message: String) : GaugeCalibrationFailure

    /// One line for the gauge screen. Every case names what to DO where there is
    /// something to do; the rest name whose problem it is.
    val label: String
        get() = when (this) {
            MissingSerial ->
                L10n.tr("This Dyno did not report a serial number, so its calibration cannot be looked up.")
            NoAccessKey ->
                L10n.tr("This build has no Frez access key, so the Dyno's calibration cannot be fetched.")
            InvalidRequest -> L10n.tr("Frez rejected the calibration request.")
            InvalidAccessKey -> L10n.tr("Frez did not accept this app's access key.")
            DeviceLimitReached ->
                L10n.tr("Frez has reached the number of Dynos this app may register.")
            DeviceNotFound -> L10n.tr("Frez has no calibration on file for this Dyno.")
            OwnershipReview ->
                L10n.tr("Frez is reviewing this Dyno's ownership before it can be registered.")
            CalibrationUnavailable -> L10n.tr("Frez could not produce a calibration for this Dyno.")
            RateLimited -> L10n.tr("Frez is busy. Try again in a minute.")
            BadResponse -> L10n.tr("Frez sent an answer this app could not read.")
            is Network ->
                L10n.tr("The calibration lookup needs an internet connection the first time a Dyno is used.")
        }
}

/// What the resolver answers.
///
/// TRANSLATION NOTE: Swift returns `Result<GaugeCalibration, GaugeCalibrationFailure>`,
/// which has no Kotlin twin — `kotlin.Result` types its failure as a `Throwable`, and a
/// calibration that could not be fetched is an ANSWER, not an exception thrown past the
/// client. Two cases say the same thing and keep the failure typed at the call site.
sealed interface GaugeCalibrationAnswer {
    data class Resolved(val calibration: GaugeCalibration) : GaugeCalibrationAnswer
    data class Unavailable(val failure: GaugeCalibrationFailure) : GaugeCalibrationAnswer
}

/// The seam between the client and whatever answers the coefficient question — Frez's
/// API in the app, a scripted answer in tests.
interface GaugeCalibrationResolver {
    suspend fun calibration(serial: String): GaugeCalibrationAnswer
}

/// `GET https://api.frez.app/v1/dyno/coefficient?serial=…` with the access key in a
/// header, exactly one query parameter (Frez rejects both or neither), answered by
/// `{"a": α}`.
class FrezCoefficientResolver(
    private val accessKey: String? = bundledAccessKey,
    private val preferences: SharedPreferences,
    private val transport: Transport = oneShotTransport,
) : GaugeCalibrationResolver {

    /// The request as this app makes it and the answer as it reads it.
    ///
    /// TRANSLATION NOTE: Swift hands the transport a `URLRequest` and takes back
    /// `(Data, HTTPURLResponse)`. `HttpURLConnection` is a live socket, not a value a
    /// test can hand back, so the seam is these two records instead — the same two
    /// halves, minus the parts of the platform types nothing here reads.
    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val timeoutSeconds: Double,
    )

    data class Response(val statusCode: Int, val body: String)

    /// Throws for a request that never completed; every HTTP status is a `Response`.
    fun interface Transport {
        suspend fun send(request: Request): Response
    }

    companion object {
        const val endpoint = "https://api.frez.app/v1/dyno/coefficient"
        const val accessKeyHeader = "X-Frez-Access-Key"

        /// **A storage format**, like every other preference file name: renaming it makes
        /// every already-calibrated Dyno spend a request it has already spent. One slope
        /// per serial, kept beside the app's other small local answers.
        const val preferencesName = "getagrip.calibration"

        /// Frez's own example uses five seconds; a first-time lookup on a slow link is
        /// worth waiting that long for, and a stuck one must not hold the gauge screen
        /// hostage.
        const val timeoutSeconds: Double = 5.0

        fun cacheKey(serial: String): String = "frez.coefficient.$serial"

        /// The build's key, or nil when it carries none. An empty string is "none":
        /// `app/build.gradle.kts` defaults the property to "" so forks and CI build
        /// without a key.
        val bundledAccessKey: String?
            get() = BuildConfig.FREZ_ACCESS_KEY.trim().ifEmpty { null }

        /// `{"a": 0.000012345678}`. A number, or a numeric string — and only a finite,
        /// POSITIVE one: a zero or negative slope would turn every pull into nothing or
        /// into its opposite, which is a corrupt answer, not a calibration.
        fun coefficient(body: String): Double? {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: return null
            val primitive = root["a"] as? JsonPrimitive ?: return null
            val value = primitive.doubleOrNull ?: return null
            return if (isUsable(value)) value else null
        }

        fun isUsable(coefficient: Double): Boolean = coefficient.isFinite() && coefficient > 0

        /// Frez's documented status table.
        fun failure(status: Int): GaugeCalibrationFailure = when (status) {
            400 -> GaugeCalibrationFailure.InvalidRequest
            401 -> GaugeCalibrationFailure.InvalidAccessKey
            403 -> GaugeCalibrationFailure.DeviceLimitReached
            404 -> GaugeCalibrationFailure.DeviceNotFound
            409 -> GaugeCalibrationFailure.OwnershipReview
            422 -> GaugeCalibrationFailure.CalibrationUnavailable
            429 -> GaugeCalibrationFailure.RateLimited
            else -> GaugeCalibrationFailure.BadResponse
        }

        /// One request, one connection, disconnected on the way out. `HttpURLConnection`
        /// on `Dispatchers.IO`: the platform's own client, nothing added to the app, and
        /// nothing left running between requests.
        val oneShotTransport = Transport { request ->
            withContext(Dispatchers.IO) {
                val connection = URL(request.url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = (request.timeoutSeconds * 1000).toInt()
                    connection.readTimeout = (request.timeoutSeconds * 1000).toInt()
                    connection.useCaches = false
                    connection.instanceFollowRedirects = false
                    for ((name, value) in request.headers) {
                        connection.setRequestProperty(name, value)
                    }
                    val status = connection.responseCode
                    // A 4xx/5xx body arrives on the ERROR stream, and reading the wrong
                    // one throws — which would report a named status as a network fault.
                    val stream: InputStream? =
                        if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    Response(statusCode = status, body = body)
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    override suspend fun calibration(serial: String): GaugeCalibrationAnswer {
        // The cache first, and before the key check on purpose: a Dyno calibrated on a
        // build that had a key keeps working on one that does not.
        val key = cacheKey(serial)
        val cached = preferences.getString(key, null)?.toDoubleOrNull()
        if (cached != null && isUsable(cached)) {
            return GaugeCalibrationAnswer.Resolved(
                GaugeCalibration(coefficient = cached, cached = true),
            )
        }
        val key0 = accessKey
            ?: return GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.NoAccessKey)

        val url = endpoint + "?serial=" + URLEncoder.encode(serial, "UTF-8")
        val request = Request(
            url = url,
            headers = mapOf(accessKeyHeader to key0, "Accept" to "application/json"),
            timeoutSeconds = timeoutSeconds,
        )

        val response = try {
            transport.send(request)
        } catch (error: Exception) {
            return GaugeCalibrationAnswer.Unavailable(
                GaugeCalibrationFailure.Network(error.message ?: error.toString()),
            )
        }
        if (response.statusCode != 200) {
            return GaugeCalibrationAnswer.Unavailable(failure(response.statusCode))
        }
        val coefficient = coefficient(response.body)
            ?: return GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.BadResponse)
        // TRANSLATION NOTE: `UserDefaults` stores a Double; SharedPreferences has no
        // double, and `putFloat` would round a slope whose whole magnitude is in its
        // exponent. `Double.toString` round-trips exactly and stays readable in a dump.
        preferences.edit().putString(key, coefficient.toString()).apply()
        return GaugeCalibrationAnswer.Resolved(
            GaugeCalibration(coefficient = coefficient, cached = false),
        )
    }
}
