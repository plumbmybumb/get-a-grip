// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import SwiftData

/// One critical force test, frozen: the result, every pull, and the force trace it came
/// from. See `CriticalForceTest` and `docs/CRITICAL_FORCE.md`.
///
/// Shaped like `MaxRecord` on purpose. It is APPEND-ONLY, identified by grip value AND
/// hand, and the grip is stored as its components with the key always computed. It is a
/// model of its own rather than a `WorkoutLog` kind, because an older build reads an
/// unknown kind as `.hang` and would count a maximal test as a routine session. The
/// testing DAY is still a `.benchmark` log, which every build already understands. See
/// `TemplateStore.recordCriticalForce`.
///
/// Same CloudKit rules as the other models: every attribute defaulted, nothing unique,
/// no relationships. **Deploy the CloudKit schema to Production before shipping a build
/// that writes this**, or production devices will not sync it.
@Model
final class CriticalForceRecord {
    var id: UUID = UUID()
    var edgeMM: Int = 20
    var fingersRaw: String = "IMRL"
    var positionRaw: String = "halfCrimp"
    var sideRaw: String = "both"
    var recordedAt: Date = Date.now
    /// `CriticalForceProtocol.key`, e.g. `7:3x24`. A CF is only comparable with one from
    /// the same protocol.
    var protocolKey: String = CriticalForceProtocol.standard.key

    var criticalForceKg: Double = 0
    var wPrimeKgS: Double = 0
    var peakKg: Double = 0
    /// nil when the final pulls had too little data for the end window.
    var endForceKg: Double? = nil
    var repsRun: Int = 0
    var restsKept: Int = 0
    var restsTotal: Int = 0
    /// Body weight at the time of the test, FROZEN. The setting can change later; a
    /// test's share of body weight must not.
    var bodyMassKg: Double? = nil
    /// The grip's max for this hand when the test ran, FROZEN, for the same reason.
    var maxAtTestKg: Double? = nil

    var repsData: Data = Data()      // [CriticalForceRep], write-once
    var traceData: Data = Data()     // CriticalForceTrace, write-once
    var note: String = ""

    init(grip: GripSpec, side: Side, result: CriticalForceResult, trace: Data,
         bodyMassKg: Double?, maxAtTestKg: Double?, recordedAt: Date = .now) {
        self.id = UUID()
        self.edgeMM = grip.edgeMM
        self.fingersRaw = grip.fingers.token
        self.positionRaw = grip.position.rawValue
        self.sideRaw = side.rawValue
        self.recordedAt = recordedAt
        self.protocolKey = result.protocolUsed.key
        self.criticalForceKg = result.criticalForceKg
        self.wPrimeKgS = result.wPrimeKgS
        self.peakKg = result.peakKg
        self.endForceKg = result.endForceKg
        self.repsRun = result.repsRun
        self.restsKept = result.restsKept
        self.restsTotal = result.restsTotal
        self.bodyMassKg = bodyMassKg
        self.maxAtTestKg = maxAtTestKg
        self.repsData = CriticalForceRepsCodec.encode(result.reps)
        self.traceData = trace
        self.note = ""
    }
}

extension CriticalForceRecord {
    var grip: GripSpec {
        GripSpec(edgeMM: edgeMM, fingers: FingerSet(token: fingersRaw), position: GripPosition(positionRaw))
    }

    var gripKey: String { grip.key }

    var side: Side { Side(rawValue: sideRaw) ?? .both }

    /// Grip AND hand, like `MaxRecord.maxKey`: a right-hand test must never supersede the
    /// left-hand one.
    var testKey: String { MaxTable.key(grip: gripKey, side: side) }

    var protocolUsed: CriticalForceProtocol { CriticalForceProtocol(key: protocolKey) }

    var reps: [CriticalForceRep] { CriticalForceRepsCodec.decode(repsData) }

    var trace: [CriticalForcePoint] { CriticalForceTrace.decode(traceData) }

    var percentOfBodyMass: Double? {
        guard let bodyMassKg, bodyMassKg > 0 else { return nil }
        return criticalForceKg / bodyMassKg * 100
    }

    var percentOfMax: Double? {
        guard let maxAtTestKg, maxAtTestKg > 0 else { return nil }
        return criticalForceKg / maxAtTestKg * 100
    }
}

extension Collection where Element == CriticalForceRecord {
    /// The newest test overall: what Today's line reports.
    var newest: CriticalForceRecord? { self.max { $0.recordedAt < $1.recordedAt } }
}
