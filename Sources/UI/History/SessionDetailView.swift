// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

// MARK: - What the detail shows, frozen at the tap

/// One past workout as a VALUE, copied off its `WorkoutLog` when the row is tapped.
///
/// A value rather than the model for one reason: Delete lives on this screen, and a view
/// still reading a SwiftData object after `deleteSession` removed it reads freed backing
/// data on the pop animation's last frames. A snapshot cannot be deleted out from under
/// its view. It is also everything the screen reads, so the screen is previewable and the
/// one rule that is not a plain copy — which maxes this session saved — is testable.
///
/// Nothing here is derived from anything but the frozen log: the reps blob (through
/// History's `RepsCache`), the denormalized columns, and the max records the save wrote.
struct SessionDetailContent: Identifiable, Hashable {
    /// A max this session recorded (or, on a benchmark day, that the day measured).
    struct SavedMax: Identifiable, Hashable {
        var grip: GripSpec
        var side: Side
        var kg: Double
        /// 0 is a peak max; otherwise the window a timed max averages over.
        var seconds: Int
        var id: String { "\(MaxTable.key(grip: grip.key, side: side))|\(seconds)|\(kg)" }
    }

    /// The facts about a stored max record the matching rule reads — plain values, so the
    /// rule runs in a test with no store behind it.
    struct RecordFact {
        var grip: GripSpec
        var side: Side
        var kg: Double
        var seconds: Int
        var measured: Bool
        var recordedAt: Date
    }

    let id: UUID
    /// `WorkoutLog.displayName(in:)` — the routine's live name, the frozen one once deleted.
    let name: String
    let kind: SessionKind
    let day: DayStamp
    let startedAt: Date
    let minutes: Int?
    let reps: [RepSummary]
    /// Pull counts per set from the frozen PLAN, so pulls never reached still draw as
    /// track. Empty when the plan did not decode; the pills then fold the reps instead.
    let setSizes: [Int]
    let plannedReps: Int
    let completedReps: Int
    let heldSeconds: Int
    let peakKg: Double
    let avgKg: Double
    let grade: RPE?
    let fingerStrain: FingerStrain?
    let notes: String
    let savedMaxes: [SavedMax]

    /// Identity is the log's: two snapshots of one session are the same destination.
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }

    /// A runner session, with pulls to account for.
    var isRunnerHang: Bool { kind == .hang }

    /// Whether a gauge measured it. INFERRED, exactly as the analysis export infers it: a
    /// hang with pulls but no pull that read any load ran on the timer alone. A row with no
    /// column for it cannot say more, and "0.0 kg" would state a measurement nobody took.
    var measured: Bool { peakKg > 0 }

    /// Where the routine stood when it ended: one pill per planned pull.
    var progress: SessionProgressModel? {
        guard isRunnerHang, !reps.isEmpty else { return nil }
        return SessionProgressModel(setSizes: setSizes.isEmpty ? Self.setSizes(of: reps) : setSizes,
                                    reps: reps)
    }

    init(log: WorkoutLog, name: String, reps: [RepSummary], records: [RecordFact]) {
        id = log.id
        self.name = name
        kind = log.kind
        day = log.day
        startedAt = log.startedAt
        minutes = log.sessionMinutes
        self.reps = reps
        setSizes = log.kind == .hang
            ? log.plan.map { Self.setSizes(of: PlanMath.sequence(for: $0).map(\.setIndex)) } ?? []
            : []
        plannedReps = log.plannedReps
        completedReps = log.completedReps
        heldSeconds = Int(log.totalHeldSeconds.rounded())
        peakKg = log.peakKg
        avgKg = log.avgKg
        grade = log.grade
        fingerStrain = log.fingerStrain
        notes = log.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        savedMaxes = Self.savedMaxes(kind: log.kind, day: log.day, finishedAt: log.finishedAt,
                                     reps: reps, records: records)
    }

    /// Which max records belong to this session.
    ///
    /// No column links a max to the session that produced it, so the link is RECOVERED,
    /// and only from facts the save itself froze:
    /// - **A hangboard session** saves the maxes chosen on its summary, each a MEASURED peak
    ///   whose kilograms are copied bit for bit from one completed pull on that grip and hand,
    ///   stamped when the summary was saved — after the session finished, and in practice
    ///   within minutes. A record counts only when all of that holds, so a max measured on
    ///   its own the same evening cannot be claimed unless it equals a pull to the last bit.
    /// - **A benchmark day** is the day measured maxes landed; it lists the measured maxes of
    ///   that training day.
    /// - Climbs and hand-logged hangs record no maxes, so they claim none.
    static func savedMaxes(kind: SessionKind, day: DayStamp, finishedAt: Date,
                           reps: [RepSummary], records: [RecordFact],
                           calendar: Calendar = .current) -> [SavedMax] {
        let matched: [RecordFact]
        switch kind {
        case .hang:
            let window = finishedAt.addingTimeInterval(-60)...finishedAt.addingTimeInterval(Self.saveWindow)
            matched = records.filter { record in
                record.measured && record.seconds == 0 && window.contains(record.recordedAt)
                    && reps.contains { rep in
                        rep.outcome == .completed && rep.side == record.side
                            && rep.grip.key == record.grip.key && rep.peakKg == record.kg
                    }
            }
        case .benchmark:
            matched = records.filter {
                $0.measured && DayStamp(trainingDayOf: $0.recordedAt, calendar: calendar) == day
            }
        default:
            matched = []
        }
        var seen = Set<String>()
        return matched
            .map { SavedMax(grip: $0.grip, side: $0.side, kg: $0.kg, seconds: $0.seconds) }
            .filter { seen.insert($0.id).inserted }
            .sorted { $0.kg == $1.kg ? $0.id < $1.id : $0.kg > $1.kg }
    }

    /// How long after finishing a summary can still be saved and count as this session's:
    /// a day covers a summary left open and the unsaved-session recovery on next launch.
    static let saveWindow: TimeInterval = 24 * 3600

    /// Pull counts per set, in order, from consecutive set indices.
    static func setSizes(of setIndices: [Int]) -> [Int] {
        var sizes: [Int] = []
        var last: Int?
        for index in setIndices {
            if index == last { sizes[sizes.count - 1] += 1 } else { sizes.append(1) }
            last = index
        }
        return sizes
    }

    static func setSizes(of reps: [RepSummary]) -> [Int] { setSizes(of: reps.map(\.setIndex)) }
}

extension SessionDetailContent.RecordFact {
    init(_ record: MaxRecord) {
        self.init(grip: record.grip, side: record.side, kg: record.kg,
                  seconds: record.durationSeconds, measured: record.source == .measured,
                  recordedAt: record.recordedAt)
    }
}

// MARK: - The screen

/// A past workout, read back: the finish summary's own pieces, with nothing left to decide.
///
/// Built from the summary's extracted views (`SessionSummaryHeader`, `SessionSummaryStats`,
/// `SessionSetBreakdown`, `SessionPeakFigure`), so the two screens cannot drift. No Save,
/// no grade picker — the session is frozen. Export and Delete sit on the toolbar; Delete
/// goes through the store's `deleteSession` and History's ten-second Undo, never a dialog.
///
/// A hand-logged session or a climb has no pulls, so it shows what was logged — the kind,
/// the day, the duration and the effort — and nothing invented.
struct SessionDetailView: View {
    let content: SessionDetailContent
    var onExport: () -> Void
    var onDelete: () -> Void

    @Environment(\.weightUnit) private var weightUnit
    @Environment(\.dynamicTypeSize) private var typeSize
    /// Open: the per-set account is what someone opening an old session came for.
    @State private var showsSets = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.spacing) {
                SessionSummaryHeader(caption: caption, title: dateTitle)
                if content.isRunnerHang {
                    SessionSummaryStats(completed: content.completedReps, planned: content.plannedReps,
                                        heldSeconds: content.heldSeconds,
                                        peakKg: content.measured ? content.peakKg : nil)
                    if let progress = content.progress { pills(progress) }
                }
                facts
                if !content.savedMaxes.isEmpty { maxes }
                if !content.reps.isEmpty {
                    SessionSetBreakdown(reps: content.reps, expanded: $showsSets,
                                        showsReps: true, measured: content.measured)
                }
            }
            .padding(.horizontal, Metrics.hPadding)
            .padding(.vertical, Metrics.spacing)
            .frame(maxWidth: Metrics.maxContentWidth)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        // `.background {}`, never a ZStack sibling — see HistoryView.
        .background { AppBackground() }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button(action: onExport) {
                    Image("glyph.export")
                        .font(.system(.body, weight: .semibold))
                        .foregroundStyle(Accent.graphite)
                        .contentShape(.rect)
                }
                .accessibilityLabel("Export workout")
                .accessibilityIdentifier("sessionDetail.export")
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(.body, weight: .semibold))
                        .foregroundStyle(Accent.alarm)
                        .contentShape(.rect)
                }
                .accessibilityLabel("Delete workout")
                .accessibilityIdentifier("sessionDetail.delete")
            }
        }
    }

    /// The routine for a session the runner timed; the kind for anything logged by hand,
    /// whose frozen name was the kind's name in whatever language it was logged in.
    private var caption: String {
        content.kind.isLoggedByHand || content.kind == .benchmark ? content.kind.name : content.name
    }

    /// The TRAINING day, the one the grid and the row count by. The year only once it is
    /// not this one.
    private var dateTitle: String {
        let date = content.day.date()
        let sameYear = Calendar.current.isDate(date, equalTo: .now, toGranularity: .year)
        return sameYear
            ? date.formatted(.dateTime.weekday(.wide).day().month(.wide))
            : date.formatted(.dateTime.weekday(.wide).day().month(.wide).year())
    }

    // MARK: - The routine as pills

    /// The runner's own pills, still: what was pulled, what was skipped, what was never
    /// reached. Decorative to VoiceOver — the stat cards and the set rows say it in words.
    private func pills(_ progress: SessionProgressModel) -> some View {
        RoutinePills(model: progress, currentTint: .clear, thickness: 8)
            .padding(.horizontal, 14)
            .padding(.vertical, 16)
            .cardSurface(.flat, in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
            .accessibilityHidden(true)
    }

    // MARK: - Facts

    private struct Fact: Identifiable {
        var label: String
        var value: String
        var spoken: String?
        var id: String { label }
    }

    private var factRows: [Fact] {
        var rows: [Fact] = []
        if content.isRunnerHang {
            rows.append(Fact(label: String(localized: "Started"),
                             value: content.startedAt.formatted(date: .omitted, time: .shortened)))
        }
        if let minutes = content.minutes {
            let duration = Duration.seconds(minutes * 60)
            rows.append(Fact(label: String(localized: "Duration"),
                             value: duration.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated)),
                             spoken: duration.formatted(.units(allowed: [.hours, .minutes], width: .wide))))
        }
        if content.isRunnerHang, content.measured, content.avgKg > 0 {
            rows.append(Fact(label: String(localized: "Average"),
                             value: "\(weightUnit.number(content.avgKg)) \(weightUnit.symbol)",
                             spoken: "\(weightUnit.number(content.avgKg)) \(weightUnit.spokenName)"))
        }
        if let grade = content.grade {
            rows.append(Fact(label: String(localized: "Effort"), value: grade.name))
        }
        if let strain = content.fingerStrain {
            rows.append(Fact(label: String(localized: "Fingers"), value: strain.name))
        }
        return rows
    }

    /// What the log says beyond the numbers. A row with nothing logged says where it
    /// happened instead, in the row's own words — never a blank card.
    private var facts: some View {
        let rows = factRows
        return VStack(alignment: .leading, spacing: 0) {
            if content.isRunnerHang, !content.measured {
                Text("Timing only")
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Ink.secondary)
                    .padding(.vertical, 10)
            }
            ForEach(rows) { row in
                factRow(row)
                if row.id != rows.last?.id {
                    Divider().overlay(Ink.tertiary.opacity(0.08))
                }
            }
            if rows.isEmpty, content.notes.isEmpty {
                Text(emptyLine)
                    .font(.system(.subheadline))
                    .foregroundStyle(Ink.secondary)
                    .padding(.vertical, 12)
            }
            if !content.notes.isEmpty {
                if !rows.isEmpty { Divider().overlay(Ink.tertiary.opacity(0.08)) }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Note").font(.system(.subheadline)).foregroundStyle(Ink.secondary)
                    Text(content.notes)
                        .font(.system(.body))
                        .foregroundStyle(Ink.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 12)
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(.flat, in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
    }

    private var emptyLine: String {
        switch content.kind {
        case .benchmark: String(localized: "Tested your maxes")
        case let kind where kind.isClimb: String(localized: "At the climbing gym")
        default: String(localized: "Away from the gauge")
        }
    }

    /// Label and value on one line; at accessibility sizes the value moves under its
    /// label rather than squeezing a number into an ellipsis.
    @ViewBuilder
    private func factRow(_ fact: Fact) -> some View {
        Group {
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 2) {
                    Text(fact.label).font(.system(.subheadline)).foregroundStyle(Ink.secondary)
                    Text(fact.value).font(.system(.body, weight: .semibold)).monospacedDigit()
                        .foregroundStyle(Ink.primary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(fact.label).font(.system(.subheadline)).foregroundStyle(Ink.secondary)
                    Spacer(minLength: 8)
                    Text(fact.value).font(.system(.body, weight: .semibold)).monospacedDigit()
                        .foregroundStyle(Ink.primary)
                        .multilineTextAlignment(.trailing)
                }
            }
        }
        .padding(.vertical, 12)
        .frame(minHeight: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(fact.label)
        .accessibilityValue(fact.spoken ?? fact.value)
    }

    // MARK: - Maxes

    private var maxes: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(content.kind == .benchmark ? String(localized: "Maxes tested")
                                                 : String(localized: "Maxes saved"))
            VStack(spacing: 0) {
                ForEach(content.savedMaxes) { saved in
                    HStack(spacing: 12) {
                        FingerGlyph(fingers: saved.grip.fingers, position: saved.grip.position,
                                    dot: 8, gap: 3, tint: StatusTint.armed)
                            .accessibilityHidden(true)
                        SessionPeakFigure(grip: saved.grip, side: saved.side, kg: saved.kg,
                                          previous: nil, seconds: saved.seconds)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 10)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(spokenMax(saved))
                    if saved.id != content.savedMaxes.last?.id {
                        Divider().overlay(Ink.tertiary.opacity(0.08))
                    }
                }
            }
            .padding(.horizontal, 14)
            .cardSurface(.flat, in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
        }
    }

    private func spokenMax(_ saved: SessionDetailContent.SavedMax) -> String {
        let kg = weightUnit.number(saved.kg)
        let line = String(localized: "\(SessionPeakFigure.handLabel(saved.side)) · \(saved.grip.shortName) · \(kg) \(weightUnit.symbol)")
        return saved.seconds > 0 ? line + ", " + String(localized: "\(saved.seconds) s") : line
    }
}
