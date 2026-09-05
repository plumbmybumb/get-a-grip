// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

/** Link-local radio housekeeping. Never an ATT request in the control write queue.
 * Accepted requests are deduplicated; rejected requests can retry after a short cooldown.
 * Acceptance means Android accepted the request, not that the peer granted the interval.
 */
internal class StreamingConnectionPriority {
    private var accepted: Boolean? = null
    private var attempted: Boolean? = null
    private var lastAttempt = Double.NEGATIVE_INFINITY

    fun update(streaming: Boolean, now: Double, request: (Boolean) -> Boolean) {
        if (accepted == streaming) return
        if (attempted == streaming && now - lastAttempt < 5.0) return
        attempted = streaming
        lastAttempt = now
        if (request(streaming)) accepted = streaming
    }

    fun reset() {
        accepted = null
        attempted = null
        lastAttempt = Double.NEGATIVE_INFINITY
    }
}
