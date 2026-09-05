// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

// MARK: - Readings

/// One decoded force reading from any supported gauge, before it becomes a
/// `ForceSample`.
///
/// `deviceMicros` is nil for every device except the Progressor: the ported scales
/// and boards carry no sample clock of their own, so the BLE client stamps each
/// reading with `SyntheticSampleClock` at ingestion instead. Downstream nothing
/// changes — the synthetic stamp wraps at the same 2^32 µs the Tindeq's does, and
/// every consumer already subtracts with `&-`.
///
/// TRANSLATION NOTE: Swift's `UInt32` is Kotlin's `UInt`, and Kotlin's unsigned
/// subtraction wraps natively — so `a - b` on `UInt` IS Swift's `a &- b`.
data class GaugeReading(
    val kg: Double,
    val deviceMicros: UInt? = null,
)

// MARK: - Frame decoding

/// A stateful per-connection frame decoder: raw notification payloads in,
/// kilogram readings out.
///
/// Stateful because some protocols (Motherboard) split one frame across several
/// notifications and need a reassembly buffer. A decoder lives and dies with one
/// connection — link cleanup drops it with the rest of the link state, so no
/// half-frame can ever pair with the next connection's bytes. Truncated or
/// malformed input must stop the walk cleanly and return whatever decoded whole;
/// real BLE delivers short reads.
///
/// TRANSLATION NOTE: Swift declares this as a protocol with `mutating func ingest`,
/// which value types adopt; Kotlin has no mutating-method concept, so every conformer
/// is a mutable CLASS and the client mints a fresh instance per link exactly as Swift
/// mints a fresh struct. The protocol extension's `batteryFraction { nil }` default
/// becomes the interface property's default getter.
interface GaugeFrameDecoder {
    fun ingest(data: ByteArray): List<GaugeReading>

    /// Battery level (0…1) for protocols that carry it inside the data stream
    /// (Climbro) rather than in the standard Battery Service. Nil until seen.
    val batteryFraction: Double?
        get() = null
}

// MARK: - Synthetic sample clock

/// µs stamps for devices that have no clock of their own.
///
/// Built from host MONOTONIC uptime, never `Date` — wall time steps under NTP and
/// timezone changes, and a stamp that jumps backwards would read as a stale batch
/// and trip the engine's fail-closed high-water check. Truncating to `UInt32`
/// reproduces the Tindeq clock's ~71.6-minute wrap on purpose: it keeps one wrap
/// rule, already handled everywhere with `&-`, instead of adding a second timing
/// regime with its own edge cases.
object SyntheticSampleClock {
    /// TRANSLATION NOTE: `UInt32(truncatingIfNeeded: Int64(uptime * 1_000_000))` is
    /// `Long.toUInt()`, which is documented to keep the least significant 32 bits —
    /// the same truncation, and the same deliberate wrap.
    fun micros(uptime: Double): UInt = (uptime * 1_000_000).toLong().toUInt()
}

// MARK: - GATT wiring facts

/// The connection facts for a GATT-connected gauge, as STRINGS — this file is in
/// `Shared/`, which must never import CoreBluetooth. `CBUUID` conversion happens
/// in `Sources/BLE`.
///
/// TRANSLATION NOTE: `equals`/`hashCode` are hand-written because Kotlin's generated
/// data-class equality compares a `ByteArray` by REFERENCE, where Swift's `Data`
/// compares by value. Two profiles built from the same bytes must be equal — the
/// registry tests assert exactly that.
data class GaugeGattProfile(
    val serviceUUID: String,
    val notifyCharacteristicUUID: String,
    val writeCharacteristicUUID: String? = null,
    /// Written in order to the write characteristic to start the stream.
    /// Empty means subscribing to notifications is itself the start.
    val streamStartPayloads: List<ByteArray> = emptyList(),
    val streamStopPayload: ByteArray? = null,
    /// Written once per LINK, right after subscribing — configuration that must not
    /// ride the repeated start path. Start payloads are re-sent on every re-kick BY
    /// DESIGN (the house rule: never gate the start), so a config write folded into
    /// them would be re-issued ~1500 times across a silent 20-minute session. The
    /// CTS500's sampling-rate command lives here for exactly that reason.
    val oneTimeSetupPayloads: List<ByteArray> = emptyList(),
    /// Seconds to wait between successive `streamStartPayloads` writes. The
    /// Motherboard answers "C" with its calibration table and the reference waits
    /// 2.5 s before sending "S30"; written back-to-back, the start can race the
    /// table home and the device streams packets the decoder must drop.
    val startPayloadDelaySeconds: Double = 0.0,
    /// Extra characteristics to subscribe ALONGSIDE `notifyCharacteristicUUID`,
    /// resolved across every discovered service. For devices where the reference
    /// subscribes everything it marks "rx" and never had to pick one (Entralpi
    /// declares two candidates); subscribing to both is what the reference does,
    /// costs nothing, and the same decoder parses whichever speaks.
    val alternateNotifyCharacteristicUUIDs: List<String> = emptyList(),
    /// Hardware tare, for the devices whose DEVICE tare the reference itself
    /// exercises (CTS500). Everything else gets the client's software tare.
    val tareCharacteristicUUID: String? = null,
    val tarePayload: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GaugeGattProfile) return false
        return serviceUUID == other.serviceUUID &&
            notifyCharacteristicUUID == other.notifyCharacteristicUUID &&
            writeCharacteristicUUID == other.writeCharacteristicUUID &&
            payloadsEqual(streamStartPayloads, other.streamStartPayloads) &&
            payloadEqual(streamStopPayload, other.streamStopPayload) &&
            payloadsEqual(oneTimeSetupPayloads, other.oneTimeSetupPayloads) &&
            startPayloadDelaySeconds == other.startPayloadDelaySeconds &&
            alternateNotifyCharacteristicUUIDs == other.alternateNotifyCharacteristicUUIDs &&
            tareCharacteristicUUID == other.tareCharacteristicUUID &&
            payloadEqual(tarePayload, other.tarePayload)
    }

    override fun hashCode(): Int {
        var result = serviceUUID.hashCode()
        result = 31 * result + notifyCharacteristicUUID.hashCode()
        result = 31 * result + (writeCharacteristicUUID?.hashCode() ?: 0)
        result = 31 * result + streamStartPayloads.sumOf { it.contentHashCode() }
        result = 31 * result + (streamStopPayload?.contentHashCode() ?: 0)
        result = 31 * result + oneTimeSetupPayloads.sumOf { it.contentHashCode() }
        result = 31 * result + startPayloadDelaySeconds.hashCode()
        result = 31 * result + alternateNotifyCharacteristicUUIDs.hashCode()
        result = 31 * result + (tareCharacteristicUUID?.hashCode() ?: 0)
        result = 31 * result + (tarePayload?.contentHashCode() ?: 0)
        return result
    }

    private companion object {
        fun payloadEqual(a: ByteArray?, b: ByteArray?): Boolean = when {
            a == null || b == null -> a == null && b == null
            else -> a.contentEquals(b)
        }

        fun payloadsEqual(a: List<ByteArray>, b: List<ByteArray>): Boolean =
            a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }
    }
}

// MARK: - The registry

/// Every gauge Doigt can drive. One case per physical device family; the codec,
/// capabilities and wiring facts all key off this, so adding a device is one codec
/// file plus one row in each switch below — never a refactor.
///
/// TRANSLATION NOTE: the case names are kept lower-case so every call site reads
/// exactly as the Swift does (`GaugeKind.cts500`), and `rawValue` is the persisted
/// token — the ONE thing about this type that ever reaches storage.
enum class GaugeKind(val rawValue: String) {
    progressor("progressor"),
    whc06("whc06"),
    entralpi("entralpi"),
    forceboard("forceboard"),
    climbro("climbro"),
    motherboard("motherboard"),
    cts500("cts500"),
    pb700bt("pb700bt");

    val displayName: String
        get() = when (this) {
            progressor -> L10n.tr("Tindeq Progressor")
            whc06 -> L10n.tr("WH-C06 crane scale")
            entralpi -> L10n.tr("Entralpi force plate")
            forceboard -> L10n.tr("PitchSix Force Board")
            climbro -> L10n.tr("Climbro")
            motherboard -> L10n.tr("Griptonite Motherboard")
            cts500 -> L10n.tr("Jlyscales CTS500")
            pb700bt -> L10n.tr("NSD PB-700BT")
        }

    val maker: String
        get() = when (this) {
            progressor -> L10n.tr("Tindeq")
            whc06 -> L10n.tr("Weiheng")
            entralpi -> L10n.tr("Entralpi")
            forceboard -> L10n.tr("PitchSix")
            climbro -> L10n.tr("Climbro")
            motherboard -> L10n.tr("Griptonite")
            cts500 -> L10n.tr("Jlyscales")
            pb700bt -> L10n.tr("NSD")
        }

    val capabilities: GaugeCapabilities
        get() = when (this) {
            progressor ->
                GaugeCapabilities(
                    hasDeviceClock = true, hasHardwareTare = true,
                    isBroadcast = false, hasStandardBattery = false,
                    sustainsBackgroundStreaming = true,
                    nominalSampleRate = 80.0, hardwareVerified = true,
                )
            whc06 ->
                GaugeCapabilities(
                    hasDeviceClock = false, hasHardwareTare = false,
                    isBroadcast = true, hasStandardBattery = false,
                    sustainsBackgroundStreaming = false,
                    nominalSampleRate = 8.0, hardwareVerified = false,
                )
            entralpi ->
                GaugeCapabilities(
                    hasDeviceClock = false, hasHardwareTare = false,
                    isBroadcast = false, hasStandardBattery = true,
                    sustainsBackgroundStreaming = true,
                    nominalSampleRate = 10.0, hardwareVerified = false,
                )
            forceboard ->
                // The board HAS a tare characteristic, but the reference never calls it
                // from its own tare path — its shipped behaviour is the software tare.
                // Promoting an unexercised API to the only mechanism would make Tare a
                // silent no-op if the write is wrong on real firmware, so software tare
                // ships until hardware confirms the write (OPEN check, see the codec).
                GaugeCapabilities(
                    hasDeviceClock = false, hasHardwareTare = false,
                    isBroadcast = false, hasStandardBattery = true,
                    sustainsBackgroundStreaming = true,
                    nominalSampleRate = 10.0, hardwareVerified = false,
                )
            climbro ->
                GaugeCapabilities(
                    hasDeviceClock = false, hasHardwareTare = false,
                    isBroadcast = false, hasStandardBattery = false,
                    sustainsBackgroundStreaming = true,
                    nominalSampleRate = 10.0, hardwareVerified = false,
                )
            motherboard ->
                // One packet sums three LOAD CELLS into one reading — the reference's
                // "3 samples" counts sensors, not moments. Its start command is literally
                // "S30", plausibly 30 Hz; inferred, not established.
                GaugeCapabilities(
                    hasDeviceClock = false, hasHardwareTare = false,
                    isBroadcast = false, hasStandardBattery = true,
                    sustainsBackgroundStreaming = true,
                    nominalSampleRate = 30.0, hardwareVerified = false,
                )
            cts500 ->
                // Hardware tare confirmed in the reference source: TARE_SCALE (0xA6) is a
                // device command and its own `tare()` override sends it.
                GaugeCapabilities(
                    hasDeviceClock = false, hasHardwareTare = true,
                    isBroadcast = false, hasStandardBattery = false,
                    sustainsBackgroundStreaming = true,
                    nominalSampleRate = 40.0, hardwareVerified = false,
                )
            pb700bt ->
                GaugeCapabilities(
                    hasDeviceClock = false, hasHardwareTare = false,
                    isBroadcast = false, hasStandardBattery = true,
                    sustainsBackgroundStreaming = true,
                    nominalSampleRate = 10.0, hardwareVerified = false,
                )
        }

    /// GATT wiring, nil for the two kinds that don't use the generic connected
    /// client: the Progressor (its own battle-tested client) and the WH-C06
    /// (broadcast, nothing to connect to).
    val gatt: GaugeGattProfile?
        get() = when (this) {
            progressor, whc06 -> null
            entralpi -> EntralpiCodec.profile
            forceboard -> ForceBoardCodec.profile
            climbro -> ClimbroCodec.profile
            motherboard -> MotherboardCodec.profile
            cts500 -> CTS500Codec.profile
            pb700bt -> PB700BTCodec.profile
        }

    /// Nil for the same two kinds, for the same reasons.
    fun makeFrameDecoder(): GaugeFrameDecoder? = when (this) {
        progressor, whc06 -> null
        entralpi -> EntralpiCodec.Decoder()
        forceboard -> ForceBoardCodec.Decoder()
        climbro -> ClimbroCodec.Decoder()
        motherboard -> MotherboardCodec.Decoder()
        cts500 -> CTS500Codec.Decoder()
        pb700bt -> PB700BTCodec.Decoder()
    }

    companion object {
        fun fromRaw(raw: String): GaugeKind? = entries.firstOrNull { it.rawValue == raw }

        /// Picker order: the two devices this project has in hand first, then the
        /// ports alphabetically by maker.
        ///
        /// **`.pb700bt` is deliberately absent.** The NSD PB-700BT turned out to be a
        /// gyroscopic hand exerciser whose stream is REVOLUTIONS PER MINUTE — the
        /// reference library funnels rpm into its single "mass" channel, and passing
        /// that through as kilograms would arm every rep instantly and record a
        /// five-figure "max" that then sets the grip's percentage targets. Its codec
        /// decodes the frames (fail-closed: no force readings) so a future spin-training
        /// mode is an addition, not a refactor — the same reason the Tindeq codec
        /// decodes RFD tags nothing consumes yet.
        val selectable: List<GaugeKind> =
            listOf(progressor, whc06, climbro, entralpi, motherboard, cts500, forceboard)
    }
}

/// What a given gauge can and cannot do. UI and runner behaviour gate on THESE
/// flags, never on the kind itself — a rule keyed to a capability survives the
/// next device; a rule keyed to a device name is a bug waiting in the one after.
data class GaugeCapabilities(
    /// Device µs timestamps in the stream (Tindeq only). Without them the client
    /// synthesizes stamps and the runner clamps per-sample credit, because host
    /// arrival gaps are radio facts, not measurements of the hand.
    val hasDeviceClock: Boolean,
    /// A tare the DEVICE executes. False means the client subtracts a captured
    /// baseline app-side — the Tare button works either way.
    val hasHardwareTare: Boolean,
    /// Broadcast-only: weight arrives in advertisements, there is no connection
    /// at all (WH-C06). "Connected" means "advertisements are arriving".
    val isBroadcast: Boolean,
    /// Standard Battery Service 0x180F / 0x2A19 percentage read at connect.
    val hasStandardBattery: Boolean,
    /// Whether a session may keep running while backgrounded. `bluetooth-central`
    /// keeps a CONNECTED stream alive; broadcast scanning coalesces duplicates in
    /// the background and effectively goes silent, so those sessions must pause.
    val sustainsBackgroundStreaming: Boolean,
    /// Approximate samples per second — for UI copy and debounce sanity checks,
    /// never for timing.
    val nominalSampleRate: Double,
    /// Verified against real hardware by THIS project. Everything false here is a
    /// port of hangtime-grip-connect's documented protocol and Settings says so —
    /// the same honesty rule as the codec's inferred RFD layout.
    val hardwareVerified: Boolean,
)
