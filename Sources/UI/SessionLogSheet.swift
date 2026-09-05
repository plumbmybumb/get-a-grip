// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Logging a session after the fact — at the climbing gym or away from the gauge.
///
/// There is deliberately nothing to start and nothing to time automatically. You are on
/// the wall for two hours with the phone in a bag; the app cannot watch it, cannot measure it, and
/// pretending otherwise would mean a timer running in your pocket that you have to
/// remember to stop. So this is a record of something that already happened, which is
/// also why it can name yesterday: the realistic moment to log Tuesday's session is
/// Wednesday morning.
///
/// Save stays enabled after two taps. Kind + day alone is a complete log; the strain
/// axes are optional, because the fast path must cost exactly what it costs today.
struct SessionLogSheet: View {
    @Environment(TemplateStore.self) private var templates

    var onClose: () -> Void

    /// No default. Volume and limit are genuinely different days and the app cannot
    /// guess which you had — pre-selecting one would get it wrong half the time and
    /// silently mis-describe the week, which is exactly what this feature exists to fix.
    @State private var kind: SessionKind?
    @State private var daysAgo = 0
    @State private var durationMinutes = 120.0
    @State private var rpe: RPE?
    @State private var fingerStrain: FingerStrain?
    @State private var failed = false
    @State private var savedTick = 0

    private static let durationStops: [Double] = [30, 45, 60, 90, 120, 150, 180, 240]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    styleBlock
                    dayBlock
                    durationBlock
                    overallStrainBlock
                    fingerStrainBlock
                    consequenceLine
                    if failed { errorLine }
                }
                .padding(.horizontal, Metrics.hPadding)
                .padding(.top, 12)
                .padding(.bottom, 28)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .background { AppBackground() }
            .scrollBounceBehavior(.basedOnSize)
            .scrollEdgeEffectStyle(.soft, for: .bottom)
            .navigationTitle("Log a session")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onClose() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .bold()
                        .disabled(kind == nil)
                        // The nearest explanation otherwise sits three sections down the
                        // scroll (`consequenceCopy`'s "Choose a session kind to see how
                        // it counts."); this puts the reason on the control itself.
                        .accessibilityHint(kind == nil ? "Choose a session kind first" : "")
                }
            }
        }
        .sensoryFeedback(.success, trigger: savedTick)
    }

    // MARK: - Blocks

    private var styleBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "WHAT KIND OF SESSION"))
            // `ChipGrid`, not an `HStack`: a third chip is what tips this row over at
            // accessibility sizes, and the grid wraps where a row would squeeze three
            // labels past legibility.
            ChipGrid(base: 3) {
                ForEach([SessionKind.climbVolume, .climbLimit, .hangManual], id: \.self) { option in
                    Chip(title: option.shortName, isSelected: kind == option) {
                        kind = option
                    }
                    .accessibilityLabel(option.name)
                }
            }
            // The explainer for the SELECTED one, or both while undecided — "volume" and
            // "limit" are jargon somebody may only half-know, and a mis-picked chip
            // quietly mis-describes the week this screen exists to describe honestly.
            VStack(alignment: .leading, spacing: 4) {
                ForEach(kind.map { [$0] } ?? [.climbVolume, .climbLimit, .hangManual], id: \.self) { option in
                    Text(kind == nil ? String(localized: "\(option.shortName) — \(option.explainer)") : option.explainer)
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .sensoryFeedback(.selection, trigger: kind)
    }

    /// Today or yesterday, and nothing further back. A full date picker would be the
    /// heaviest control on this fast log to serve a case — logging Thursday's session on
    /// Sunday — that barely happens and that History can already show is missing.
    private var dayBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "WHEN"))
            HStack(spacing: 8) {
                Chip(title: String(localized: "Today"), isSelected: daysAgo == 0) { daysAgo = 0 }
                Chip(title: String(localized: "Yesterday"), isSelected: daysAgo == 1) { daysAgo = 1 }
            }
        }
        .sensoryFeedback(.selection, trigger: daysAgo)
    }

    private var durationBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "HOW LONG"))
            Text(Self.durationLabel(Int(durationMinutes)))
                .font(.system(.title3, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
                .contentTransition(.numericText())
            // Tap-to-type belongs on the row above. Two hours versus two hours fifteen
            // is noise inside a five-point self-report, and a keyboard would cost the
            // fast path this sheet is built around.
            // `DialTrack` collapses to ONE element with children ignored, so without a
            // label all three dials announce as an unnamed "adjustable" and the two
            // strain ones are indistinguishable from each other.
            DialTrack(value: $durationMinutes,
                      values: Self.durationStops,
                      format: { Self.durationLabel(Int($0)) },
                      spokenUnit: "")
                .accessibilityLabel("How long the session was")
            // Two hours is pre-filled: a close-enough duration is more useful to the
            // load model than nil, and it is visible and one drag from right.
        }
    }

    private var overallStrainBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "HOW HARD OVERALL"))
            // No `.numericText()` here or on the finger row: these read out WORDS, and
            // the numeric transition is for digits rolling. The house rule pairs it with
            // `.monospacedDigit()` for exactly that reason.
            Text(rpe?.name ?? String(localized: "Not set"))
                .font(.system(.title3, weight: .semibold))
                .foregroundStyle(rpe == nil ? Ink.tertiary : Ink.primary)
            DialTrack(value: rpeDialValue,
                      values: RPE.allCases.map { Double($0.rawValue) },
                      format: { RPE(rawValue: Int($0))?.name ?? "" },
                      spokenUnit: "",
                      isUnset: rpe == nil)
                .accessibilityLabel("How hard the session was overall")
        }
    }

    private var fingerStrainBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            CapsLabel(String(localized: "ON YOUR FINGERS"))
            Text(fingerStrain?.name ?? String(localized: "Not set"))
                .font(.system(.title3, weight: .semibold))
                .foregroundStyle(fingerStrain == nil ? Ink.tertiary : Ink.primary)
            DialTrack(value: fingerStrainDialValue,
                      values: FingerStrain.allCases.map { Double($0.rawValue) },
                      format: { FingerStrain(rawValue: Int($0))?.name ?? "" },
                      spokenUnit: "",
                      isUnset: fingerStrain == nil)
                .accessibilityLabel("How hard it was on your fingers")
        }
    }

    private var rpeDialValue: Binding<Double> {
        Binding(
            get: { Double(rpe?.rawValue ?? RPE.easy.rawValue) },
            set: { rpe = RPE(rawValue: Int($0)) }
        )
    }

    private var fingerStrainDialValue: Binding<Double> {
        Binding(
            get: { Double(fingerStrain?.rawValue ?? FingerStrain.nothing.rawValue) },
            set: { fingerStrain = FingerStrain(rawValue: Int($0)) }
        )
    }

    private static func durationLabel(_ minutes: Int) -> String {
        switch minutes {
        case 30: String(localized: "30m")
        case 45: String(localized: "45m")
        case 60: String(localized: "1h")
        case 90: String(localized: "1h30")
        case 120: String(localized: "2h")
        case 150: String(localized: "2h30")
        case 180: String(localized: "3h")
        case 240: String(localized: "4h")
        default: String(localized: "\(minutes)m")
        }
    }

    /// States the rule on the screen that invokes it. A climb settles the day; a manual
    /// hang only fills one session share, so this copy must follow the selected kind.
    private var consequenceLine: some View {
        Text(consequenceCopy)
            .font(.system(.footnote))
            .foregroundStyle(Ink.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var consequenceCopy: String {
        let day = daysAgo == 0 ? String(localized: "today") : String(localized: "yesterday")
        switch kind {
        case .hangManual:
            return String(localized: "That counts as one session for \(day), but it does not settle the day. You can still do another session if you want one.")
        case .climbVolume, .climbLimit:
            return String(localized: "That completes \(day) — no reminders, and a full day on your calendar. You can still do a hang session if you want one.")
        case nil, .hang, .benchmark:
            return String(localized: "Choose a session kind to see how it counts.")
        }
    }

    private var errorLine: some View {
        Text("That couldn't be saved — nothing was logged. Try again.")
            .font(.system(.footnote, weight: .medium))
            .foregroundStyle(Accent.alarm)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - Commit

    private func save() {
        guard let kind else { return }
        failed = false
        guard templates.recordLoggedSession(kind, daysAgo: daysAgo,
                                            minutes: Int(durationMinutes),
                                            rpe: rpe,
                                            fingerStrain: fingerStrain) != nil else {
            // The sheet STAYS OPEN on a rollback: dismissing on failure loses the two
            // decisions and tells the user it worked.
            failed = true
            return
        }
        savedTick += 1
        onClose()
    }
}
