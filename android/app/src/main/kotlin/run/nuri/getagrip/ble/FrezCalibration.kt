// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import run.nuri.getagrip.BuildConfig
import run.nuri.getagrip.engine.L10n
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

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

    /// `bodyTooLarge`: the body ran past `maxResponseBytes` and was abandoned there, so
    /// `body` is empty rather than the first sixteen kilobytes of something else.
    data class Response(val statusCode: Int, val body: String, val bodyTooLarge: Boolean = false)

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
        ///
        /// **For the WHOLE request, connect and read together** — iOS's
        /// `timeoutIntervalForResource`. `HttpURLConnection`'s own timeouts are per phase,
        /// and the read one starts over with every byte, so a server that dripped one byte
        /// every four seconds held the screen for as long as it liked.
        const val timeoutSeconds: Double = 5.0

        /// The answer is `{"a": 0.0000123}`. Sixteen kilobytes is hundreds of times that and
        /// still nothing to buffer; a body that keeps coming is not a calibration.
        const val maxResponseBytes = 16 * 1024

        fun cacheKey(serial: String): String = "frez.coefficient.$serial"

        /// `^[A-Za-z0-9._:-]{1,64}$`. A serial is whatever the PERIPHERAL says it is, and
        /// any device can claim to be a Dyno. This one string becomes a preferences key and
        /// a request carrying the app's access key, so it has to look like a serial number:
        /// ASCII letters, digits and the four separators serials use, at most 64 of them.
        /// Deliberately looser than Frez's documented `FrezDyno-######` — a rule that
        /// refused a real unit would cost its owner the gauge.
        fun isWellFormed(serial: String): Boolean =
            serial.length in 1..64 && serial.all { character ->
                character in '0'..'9' || character in 'A'..'Z' || character in 'a'..'z' ||
                    character == '.' || character == '_' || character == ':' || character == '-'
            }

        /// The body, or null the moment it proves longer than `limit` — never more than
        /// `limit + 1` bytes read, however much the server has to send. `expired` is asked
        /// before every read, so a body that drips cannot outlast the deadline either.
        internal fun readBody(
            stream: InputStream,
            limit: Int = maxResponseBytes,
            expired: () -> Boolean = { false },
        ): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                if (expired()) throw SocketTimeoutException("calibration request deadline passed")
                val read = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (read < 0) return out.toByteArray()
                out.write(buffer, 0, read)
                if (out.size() > limit) return null
            }
        }

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
        ///
        /// Three things the platform's defaults do that this must not: FOLLOW A REDIRECT
        /// (the request carries the access key, and a 3xx would send it wherever it was
        /// pointed — refused, so the 3xx arrives as the answer and reads as a bad one),
        /// BUFFER WHATEVER ARRIVES (the body is read up to `maxResponseBytes` and abandoned
        /// past it), and WAIT PHASE BY PHASE (see `timeoutSeconds`).
        ///
        /// The deadline is a watchdog that closes the socket, because a blocked read hears
        /// nothing else — not a coroutine cancellation, not an interrupt. It closes the
        /// socket on cancellation too, so a lookup for a link that has gone stops with it.
        val oneShotTransport = Transport { request ->
            withContext(Dispatchers.IO) {
                val connection = URL(request.url).openConnection() as HttpURLConnection
                val deadlineMillis = (request.timeoutSeconds * 1000).toLong()
                val expired = AtomicBoolean(false)
                val watchdog = launch {
                    try {
                        delay(deadlineMillis)
                        expired.set(true)
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = deadlineMillis.toInt()
                    connection.readTimeout = deadlineMillis.toInt()
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
                    // A length the server states up front is refused without reading a byte.
                    val body = when {
                        stream == null -> ByteArray(0)
                        connection.contentLengthLong > maxResponseBytes -> null
                        else -> stream.use { readBody(it, maxResponseBytes, expired::get) }
                    }
                    Response(
                        statusCode = status,
                        body = body?.toString(Charsets.UTF_8) ?: "",
                        bodyTooLarge = body == null,
                    )
                } catch (error: Exception) {
                    // The watchdog closed the socket under a blocked call: say what
                    // happened rather than "Socket closed".
                    if (expired.get() && error !is CancellationException) {
                        throw SocketTimeoutException("no answer within ${request.timeoutSeconds} s")
                    }
                    throw error
                } finally {
                    watchdog.cancel()
                    connection.disconnect()
                }
            }
        }
    }

    override suspend fun calibration(serial: String): GaugeCalibrationAnswer {
        // Before the cache as well as the network: the key below is built from it.
        if (!isWellFormed(serial)) {
            return GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.InvalidRequest)
        }
        // The cache first, before the key check: a Dyno calibrated on a keyed build keeps
        // working on one without. (Guarded: a value of another type under this key throws,
        // and an unreadable cache is an empty one.)
        val key = cacheKey(serial)
        val cached = runCatching { preferences.getString(key, null) }.getOrNull()?.toDoubleOrNull()
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

        // The deadline again, around WHATEVER the transport is: the one above bounds a
        // socket, this one bounds the answer, so no transport can hold the screen longer.
        val response = try {
            withTimeoutOrNull((timeoutSeconds * 1000).toLong()) { transport.send(request) }
                ?: return GaugeCalibrationAnswer.Unavailable(
                    GaugeCalibrationFailure.Network("no answer within $timeoutSeconds s"),
                )
        } catch (cancelled: CancellationException) {
            // THIS lookup being cancelled (its link went away) is not an answer, and is
            // passed on. A cancellation that merely surfaced here — a transport's own
            // timeout — is one, and swallowing it would leave the screen on "resolving".
            currentCoroutineContext().ensureActive()
            return GaugeCalibrationAnswer.Unavailable(
                GaugeCalibrationFailure.Network(cancelled.message ?: cancelled.toString()),
            )
        } catch (error: Exception) {
            return GaugeCalibrationAnswer.Unavailable(
                GaugeCalibrationFailure.Network(error.message ?: error.toString()),
            )
        }
        if (response.statusCode != 200) {
            return GaugeCalibrationAnswer.Unavailable(failure(response.statusCode))
        }
        // A 200 that kept coming is not a calibration. Measured here as well as in the
        // transport, so the rule holds for any transport that hands a body over whole.
        if (response.bodyTooLarge || response.body.length > maxResponseBytes ||
            response.body.toByteArray(Charsets.UTF_8).size > maxResponseBytes
        ) {
            return GaugeCalibrationAnswer.Unavailable(GaugeCalibrationFailure.BadResponse)
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
