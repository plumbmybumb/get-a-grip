// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/// The REAL transport — `HttpURLConnection` and a socket — against a server that
/// misbehaves in the three ways the request has to survive. `FrezDynoIntegrationTests`
/// covers the resolver over a scripted transport; these are the properties a script cannot
/// show, because they are about what the platform's client does by default.
///
/// The server is a `ServerSocket` on the loopback address inside this process: no network
/// is touched and nothing leaves the machine. The JVM's `HttpURLConnection` stands in for
/// Android's here, so what is pinned is this file's own logic — the watchdog, the bounded
/// read, the refused redirect — not the platform's implementation of them.
class FrezTransportTests {

    /// Accepts connections, reads each request's head, and hands the socket to `respond`.
    private class Loopback(private val respond: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val hits = AtomicInteger(0)
        val url: String get() = "http://127.0.0.1:${server.localPort}/dyno-coefficient?serial=FrezDyno-000123"

        init {
            thread(isDaemon = true) {
                try {
                    while (true) {
                        val socket = server.accept()
                        hits.incrementAndGet()
                        thread(isDaemon = true) {
                            try {
                                socket.use {
                                    readRequestHead(it)
                                    respond(it)
                                }
                            } catch (_: IOException) {
                                // The client hung up, which is what three of these tests are for.
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Closed.
                }
            }
        }

        /// Up to the blank line that ends the request head.
        private fun readRequestHead(socket: Socket) {
            val input = socket.getInputStream()
            var matched = 0
            while (matched < 4) {
                val byte = input.read()
                if (byte < 0) return
                matched = if (byte == "\r\n\r\n"[matched].code) matched + 1 else if (byte == '\r'.code) 1 else 0
            }
        }

        override fun close() = server.close()
    }

    private fun Socket.send(text: String) {
        getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
        getOutputStream().flush()
    }

    private fun request(url: String, timeoutSeconds: Double = 5.0) = FrezCoefficientResolver.Request(
        url = url,
        headers = mapOf(FrezCoefficientResolver.accessKeyHeader to "k3y", "Accept" to "application/json"),
        timeoutSeconds = timeoutSeconds,
    )

    /// Never longer than ten real seconds, whatever a test's server does.
    private fun send(request: FrezCoefficientResolver.Request): FrezCoefficientResolver.Response =
        runBlocking { withTimeout(10_000) { FrezCoefficientResolver.oneShotTransport.send(request) } }

    @Test
    fun anOrdinaryAnswerIsReadWhole() {
        val body = """{"a": 0.000012345678}"""
        Loopback { socket ->
            socket.send("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
        }.use { server ->
            val response = send(request(server.url))
            assertEquals(200, response.statusCode)
            assertEquals(body, response.body)
            assertFalse(response.bodyTooLarge)
            assertEquals(0.000012345678, FrezCoefficientResolver.coefficient(response.body))
        }
    }

    /// The error body is on a different stream; a named status must arrive as that status.
    @Test
    fun aNamedFailureArrivesAsItsStatus() {
        val body = """{"error": "device_not_found"}"""
        Loopback { socket ->
            socket.send("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
        }.use { server ->
            val response = send(request(server.url))
            assertEquals(404, response.statusCode)
            assertEquals(GaugeCalibrationFailure.DeviceNotFound, FrezCoefficientResolver.failure(response.statusCode))
        }
    }

    /// **The access key never follows a redirect.** `HttpURLConnection` re-sends its request
    /// headers wherever a 3xx points, and the header here is the app's key. The 3xx is the
    /// answer instead — not a 200, so a bad response — and the second host hears nothing.
    @Test
    fun aRedirectIsNotFollowedSoTheKeyGoesNowhereElse() {
        Loopback { socket ->
            socket.send("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\n{\"a\": 0.5}")
        }.use { elsewhere ->
            Loopback { socket ->
                socket.send(
                    "HTTP/1.1 302 Found\r\nLocation: ${elsewhere.url}\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n",
                )
            }.use { server ->
                val response = send(request(server.url))
                assertEquals(302, response.statusCode)
                assertEquals(GaugeCalibrationFailure.BadResponse, FrezCoefficientResolver.failure(response.statusCode))
                assertEquals(1, server.hits.get())
                assertEquals(0, elsewhere.hits.get(), "the redirect target must never be contacted")
            }
        }
    }

    /// A body that says up front it is too long is refused without being read; one that
    /// does not say, and simply keeps coming, is abandoned a byte past the cap.
    @Test
    fun aBodyPastTheCapIsAbandonedNotBuffered() {
        val declared = FrezCoefficientResolver.maxResponseBytes + 1
        Loopback { socket ->
            socket.send("HTTP/1.1 200 OK\r\nContent-Length: $declared\r\nConnection: close\r\n\r\n")
            socket.send(" ".repeat(declared))
        }.use { server ->
            val response = send(request(server.url))
            assertEquals(200, response.statusCode)
            assertTrue(response.bodyTooLarge)
            assertEquals("", response.body)
        }

        Loopback { socket ->
            socket.send("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")
            val chunk = " ".repeat(8 * 1024)
            // Eight megabytes, if anybody were still listening.
            repeat(1024) { socket.send(chunk) }
        }.use { server ->
            val response = send(request(server.url))
            assertTrue(response.bodyTooLarge)
            assertEquals("", response.body)
        }
    }

    /// Exactly the cap is still an answer: the limit is on what is too long, not on this.
    @Test
    fun aBodyOfExactlyTheCapIsRead() {
        val size = FrezCoefficientResolver.maxResponseBytes
        val body = """{"a": 0.5}""" + " ".repeat(size - 10)
        Loopback { socket ->
            socket.send("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n$body")
        }.use { server ->
            val response = send(request(server.url))
            assertFalse(response.bodyTooLarge)
            assertEquals(size, response.body.length)
        }
    }

    /// **The deadline is for the WHOLE request.** This server answers at once and then sends
    /// one byte every 50 ms for ever: no single read ever waits long enough to time out, so
    /// per-phase timeouts never fire and the old transport read until the body ended —
    /// which it does not. The watchdog closes the socket at the deadline instead.
    @Test
    fun aBodyThatDripsForeverIsCutOffAtTheDeadline() {
        Loopback { socket ->
            socket.send("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")
            while (true) {
                socket.send(" ")
                Thread.sleep(50)
            }
        }.use { server ->
            val started = System.nanoTime()
            val failure = runCatching { send(request(server.url, timeoutSeconds = 0.5)) }.exceptionOrNull()
            val elapsed = (System.nanoTime() - started) / 1e9

            assertTrue(failure is IOException, "expected the request to fail, got $failure")
            assertTrue(elapsed < 5.0, "cut off at the deadline, not whenever the body ends ($elapsed s)")
            assertTrue(elapsed >= 0.4, "and not before it ($elapsed s)")
        }
    }

    /// A server that accepts and then says nothing at all.
    @Test
    fun aServerThatNeverAnswersFailsAtTheDeadline() {
        Loopback { Thread.sleep(30_000) }.use { server ->
            val started = System.nanoTime()
            val failure = runCatching { send(request(server.url, timeoutSeconds = 0.5)) }.exceptionOrNull()
            val elapsed = (System.nanoTime() - started) / 1e9

            if (failure !is IOException) fail("expected the request to fail, got $failure")
            assertTrue(elapsed < 5.0, "$elapsed s")
        }
    }

    /// The watchdog ends a blocked read by closing the socket under it, which the platform
    /// reports as "Socket closed". That is the mechanism, not what happened; the failure is
    /// named for what happened.
    @Test
    fun theDeadlineIsReportedAsATimeoutNotAsAClosedSocket() {
        val failure = runCatching {
            Loopback { socket ->
                socket.send("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")
                while (true) {
                    socket.send(" ")
                    Thread.sleep(50)
                }
            }.use { server -> send(request(server.url, timeoutSeconds = 0.3)) }
        }.exceptionOrNull()
        assertTrue(failure is java.net.SocketTimeoutException, "named for what it is, got $failure")
    }
}
