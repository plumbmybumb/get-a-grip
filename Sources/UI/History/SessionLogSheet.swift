// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// Logging a session after the fact — at the climbing gym or away from the gauge.
///
/// Nothing to start and nothing timed: on the wall for two hours with the phone in a bag,
/// the app cannot measure it, and a pocket timer is one more thing to remember to stop.
/// A record of what happened — which is why it can name yesterday.
///
/// Save stays enabled after two taps. Kind + day alone is a complete log; the strain
/// axes are optional, because the fast path must cost exactly what it costs today.
struct SessionLogSheet: View {
    @Environment(TemplateStore.self) private var templates

    var onClose: () -> Void

    /// No default kind: volume and limit are different days, and a pre-selection would be
    /// wrong half the time and silently mis-describe the week.
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var showKindHelp = false
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
                VStack(alignment: .leading, spacing: 12) {
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
                .padding(.bottom, 12)
                .frame(maxWidth: Metrics.maxContentWidth)
                .frame(maxWidth: .infinity)
            }
            .background { AppBackground() }
            .scrollBounceBehavior(.basedOnSize)
            .accessibilityIdentifier("sessionLog.form")
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
                        // The nearest explanation is three sections down the scroll; this puts the
                        // reason on the control.
                        .accessibilityHint(kind == nil ? "Choose a session kind first" : "")
                }
            }
        }
        .sensoryFeedback(.success, trigger: savedTick)
    }

    // MARK: - Blocks

    private var styleBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                CapsLabel(String(localized: "WHAT KIND OF SESSION"))
                Spacer()
                Button { showKindHelp.toggle() } label: {
                    Image(systemName: "info.circle")
                        .frame(width: 44, height: 44).contentShape(.rect)
                }
                .foregroundStyle(Ink.secondary)
                .accessibilityLabel("About session types")
                .accessibilityValue(showKindHelp ? "Expanded" : "Collapsed")
            }
            // `ChipGrid`, not an `HStack`: a third chip tips this row over at
            // accessibility sizes, and the grid wraps where a row would squeeze.
            ChipGrid(base: 3) {
                ForEach([SessionKind.climbVolume, .climbLimit, .hangManual], id: \.self) { option in
                    Chip(title: option.shortName, isSelected: kind == option) {
                        kind = option
                    }
                    .accessibilityLabel(option.name)
                }
            }
            // The explainer for the SELECTED kind, or all while undecided: "volume" and
            // "limit" are jargon, and a mis-picked chip mis-describes the week.
            if showKindHelp {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(kind.map { [$0] } ?? [.climbVolume, .climbLimit, .hangManual], id: \.self) { option in
                        Text(kind == nil ? String(localized: "\(option.shortName) — \(option.explainer)") : option.explainer)
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.tertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .sensoryFeedback(.selection, trigger: kind)
    }

    /// Today or yesterday only. A full date picker would be the heaviest control on a fast
    /// log, for a case that barely happens.
    private var dayBlock: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 16))
        return layout {
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
            HStack {
                CapsLabel(String(localized: "HOW LONG"))
                Spacer()
                Text(Self.durationLabel(Int(durationMinutes)))
                    .font(.system(.title3, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Ink.primary)
                    .contentTransition(.numericText())
            }
            // Tap-to-type belongs on the row above: 2:00 versus 2:15 is noise inside a
            // five-point self-report, and a keyboard would cost the fast path.
            // `DialTrack` is ONE element with children ignored, so without a label the
            // three dials announce as indistinguishable unnamed "adjustable"s.
            DialTrack(value: $durationMinutes,
                      values: Self.durationStops,
                      format: { Self.durationLabel(Int($0)) },
                      spokenUnit: "")
                .accessibilityLabel("How long the session was")
            // Two hours pre-filled: close enough beats nil for the load model, and it is
            // visible and one drag from right.
        }
    }

    private var overallStrainBlock: some View {
        EffortPicker(selection: Binding(get: { rpe?.rawValue },
                                         set: { rpe = $0.flatMap(RPE.init(rawValue:)) }),
                     labels: RPE.allCases.map(\.name),
                     title: String(localized: "How hard did it feel?"), identifier: "effort.overall")
    }

    private var fingerStrainBlock: some View {
        EffortPicker(selection: Binding(get: { fingerStrain?.rawValue },
                                         set: { fingerStrain = $0.flatMap(FingerStrain.init(rawValue:)) }),
                     labels: FingerStrain.allCases.map(\.name),
                     title: String(localized: "On your fingers"), identifier: "effort.fingers")
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

    /// States the rule where it applies: a climb settles the day, a manual hang fills one
    /// session share, so the copy follows the selected kind.
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
            return String(localized: "Counts as one session for \(day).")
        case .climbVolume, .climbLimit:
            return String(localized: "Marks \(day) as trained and stops its reminders.")
        case nil, .hang, .benchmark:
            return String(localized: "Choose a session kind to see how it counts.")
        }
    }

    private var errorLine: some View {
        Text("Couldn't save. Try again.")
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
            // STAYS OPEN on a rollback: dismissing loses the input and claims success.
            failed = true
            return
        }
        savedTick += 1
        onClose()
    }
}
