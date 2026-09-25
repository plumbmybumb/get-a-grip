// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

/// Critical force on Today: ONE line, never a card of its own.
///
/// Today is the daily ritual, and a critical force test happens every four to eight weeks,
/// so it earns a line and not a block. The line states the latest result and is the door
/// to the next test. It turns amber only when a retest is due, because amber means "this
/// is waiting on you" everywhere else in the app.
///
/// Its own `@Query`, in a leaf, so a saved test redraws this line and nothing else.
struct CriticalForceTodayLine: View {
    var onTest: (GripSpec, Side) -> Void

    @Query(sort: [SortDescriptor(\CriticalForceRecord.recordedAt)])
    private var records: [CriticalForceRecord]
    @Environment(TemplateStore.self) private var templates
    @Environment(DayClock.self) private var clock
    @Environment(\.weightUnit) private var weightUnit

    var body: some View {
        let latest = records.last
        Button {
            if let latest { onTest(latest.grip, latest.side) }
            else { onTest(templates.recentGrips.first ?? GripSpec(), .both) }
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                CapsLabel(String(localized: "Critical force"))
                    .fixedSize()
                if let latest {
                    Text(weightUnit.text(latest.criticalForceKg))
                        .font(.system(.subheadline, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(Ink.primary)
                        .fixedSize()
                    if let pct = latest.percentOfMax {
                        Text("\(Int(pct.rounded())) % of max")
                            .font(.system(.footnote))
                            .foregroundStyle(Ink.secondary)
                            .lineLimit(1)
                    }
                } else {
                    Text("Test your endurance · 4 min")
                        .font(.system(.footnote))
                        .foregroundStyle(Ink.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 6)
                if let latest { trailing(latest) }
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Ink.tertiary)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .cardSurface(.material, in: RoundedRectangle(cornerRadius: Metrics.radiusInner, style: .continuous))
            .contentShape(.rect(cornerRadius: Metrics.radiusInner))
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityValue(latest.map { "Tested \($0.recordedAt.formatted(.relative(presentation: .named)))" } ?? "")
        .accessibilityHint(latest == nil ? "Opens the four-minute critical force test."
                                         : "Opens a new critical force test.")
        .accessibilityIdentifier("today.criticalForce")
    }

    /// Only when a retest is due. The age alone is not worth the width: it pushed the
    /// "% of max" off a phone-width line, and "due" is the one thing about age that asks
    /// anything of you.
    @ViewBuilder
    private func trailing(_ latest: CriticalForceRecord) -> some View {
        if isDue(latest) {
            Text("Retest due")
                .font(.system(.footnote, weight: .semibold))
                .foregroundStyle(StatusTint.armed)
                .fixedSize()
        }
    }

    /// Read off `DayClock`, so it flips when the day rolls under an open phone.
    private func isDue(_ latest: CriticalForceRecord) -> Bool {
        let tested = DayStamp(trainingDayOf: latest.recordedAt)
        return clock.today.raw - tested.raw >= CriticalForceRules.retestAfterDays
    }
}
