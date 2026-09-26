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
// The Frez Dyno sends signed ADC values; the per-device slope is known only to Frez's
// coefficient API. This is the ONLY place the app talks to a non-platform server, so its
// rules:
//
// - Nothing runs unless the selected gauge needs it AND a unit is connected; other gauges
//   never construct any of it.
// - One request per serial, EVER. The slope belongs to the load cell; once cached on the
//   device the network is never asked again for that Dyno, which also respects the
//   account's device and rate limits.
// - One-shot connection: opened for the request, closed after. No networking stack lives
//   between requests.
// - The access key lives only in a private Gradle property (see app/build.gradle.kts and
//   BUILDING.md). A build without one still connects and says why there is no force.
// - Fail-closed, as Frez asks: no coefficient, no calibrated force, and the reason shown
//   rather than a guessed number.

/// The per-device slope, and where it came from.
data class GaugeCalibration(
    val coefficient: Double,
    /// Answered from the on-device cache, with no request made.
    val cached: Boolean,
)

/// Why calibrated force is, or is not, available — so the gauge screen and runner can say
/// so instead of a silent 0.0 kg.
///
/// TRANSLATION NOTE: Swift's enum with associated values becomes a sealed interface.
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

    /// One line for the gauge screen: what to DO where there is something to do, otherwise
    /// whose problem it is.
    val label: String
        get() = when (this) {
            MissingSerial ->
                L10n.tr("This Dyno sent no serial number, so its calibration can't be looked up.")
            NoAccessKey ->
                L10n.tr("This build has no Frez access key, so it can't fetch the Dyno's calibration.")
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
                L10n.tr("Connect to the internet for the Dyno's first calibration.")
        }
}

/// What the resolver answers.
///
/// TRANSLATION NOTE: Swift returns `Result<GaugeCalibration, GaugeCalibrationFailure>`.
/// `kotlin.Result` types failure as `Throwable`, and an unfetched calibration is an ANSWER,
/// not an exception, so two cases keep the failure typed.
sealed interface GaugeCalibrationAnswer {
    data class Resolved(val calibration: GaugeCalibration) : GaugeCalibrationAnswer
    data class Unavailable(val failure: GaugeCalibrationFailure) : GaugeCalibrationAnswer
}

/// The seam between the client and whatever answers the coefficient question: Frez's API in
/// the app, a script in tests.
interface GaugeCalibrationResolver {
    suspend fun calibration(serial: String): GaugeCalibrationAnswer
}

/// `GET https://api.frez.app/functions/v1/dyno-coefficient?serial=…` with the access key in
/// a header, exactly one query parameter (Frez rejects both or neither), answered by
/// `{"a": α}`.
class FrezCoefficientResolver(
    private val accessKey: String? = bundledAccessKey,
    private val preferences: SharedPreferences,
    private val transport: Transport = oneShotTransport,
) : GaugeCalibrationResolver {

    /// The request as this app makes it and the answer as it reads it.
    ///
    /// TRANSLATION NOTE: Swift's transport takes a `URLRequest` and returns
    /// `(Data, HTTPURLResponse)`. `HttpURLConnection` is a live socket, not a value a test
    /// can return, so the seam is these two records.
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
        const val endpoint = "https://api.frez.app/functions/v1/dyno-coefficient"
        const val accessKeyHeader = "X-Frez-Access-Key"

        /// **A storage format**: renaming it makes every calibrated Dyno spend a request it
        /// already spent. One slope per serial.
        const val preferencesName = "getagrip.calibration"

        /// Frez's own example uses five seconds: long enough for a slow first lookup, short
        /// enough that a stuck one does not hold the gauge screen hostage.
        const val timeoutSeconds: Double = 5.0

        fun cacheKey(serial: String): String = "frez.coefficient.$serial"

        /// The build's key, or null. An empty string is "none": `app/build.gradle.kts`
        /// defaults the property to "" so forks and CI build without one.
        val bundledAccessKey: String?
            get() = BuildConfig.FREZ_ACCESS_KEY.trim().ifEmpty { null }

        /// `{"a": 0.000012345678}` — a number or numeric string, and only finite and
        /// POSITIVE: a zero or negative slope would turn every pull into nothing or its
        /// opposite.
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

        /// One request, one connection, closed on the way out: `HttpURLConnection` on
        /// `Dispatchers.IO`, the platform's own client, nothing left running.
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
                    // A 4xx/5xx body is on the ERROR stream; reading the wrong one throws
                    // and would report a named status as a network fault.
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
        // The cache first, before the key check: a Dyno calibrated on a keyed build keeps
        // working on one without.
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
        // TRANSLATION NOTE: SharedPreferences has no double, and `putFloat` would round a
        // slope whose magnitude is all exponent. `Double.toString` round-trips exactly.
        preferences.edit().putString(key, coefficient.toString()).apply()
        return GaugeCalibrationAnswer.Resolved(
            GaugeCalibration(coefficient = coefficient, cached = false),
        )
    }
}
