// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// **All time, in History between the trend deck and the sessions** (Nuri, 2026-09-20:
/// "lifetime stats — number of routines done, total load lifetime, anything else?").
/// Sessions, pulls, time under tension, volume, days trained, climbing days, the heaviest
/// pull, and the date they count from. Nothing here is a chart or a trend — the two
/// cards above it own those — this is the odometer, and it sits right over the sessions
/// it adds up. It opened at the top of Settings for an hour; History is where the
/// question is asked.
///
/// A LEDGER, not tiles. The first cut laid six numbers in a three-column grid, and on
/// the phone it read as ragged: a "49" left a hole two numbers wide, "4 hr, 40 min"
/// crowded the right edge, and the whole thing sat above a Device card drawn as
/// label-left, value-right rows (Nuri: "spacing on this is kind of ugly"). Same rows as
/// that neighbour now, so the two cards share one rhythm and every value lands on one
/// right-hand axis.
///
/// Folded from denormalized columns (`Collection.lifetime`) off History's own query, so
/// it costs a row per session, never a decode.
struct LifetimeCard: View {
    var stats: LifetimeStats
    @Environment(\.weightUnit) private var weightUnit

    var body: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 12) {
                CapsLabel(title)
                if stats.isEmpty {
                    Text("Your all-time numbers land here after the first session.")
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    row(String(localized: "Sessions"), stats.sessions.formatted())
                    row(String(localized: "Pulls"), stats.pulls.formatted())
                    row(String(localized: "Under tension"), heldText)
                    row(String(localized: "Volume"), volumeText)
                    row(String(localized: "Days trained"), stats.daysTrained.formatted())
                    if stats.climbDays > 0 {
                        row(String(localized: "Climbing days"), stats.climbDays.formatted())
                    }
                    row(String(localized: "Heaviest pull"), weightUnit.text(stats.heaviestPullKg))
                    Text("Volume is load × pulls, added up. Under tension is every second on the edge.")
                        .font(.system(.caption))
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                }
            }
            .font(.system(.subheadline))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(spoken)
        .accessibilityIdentifier("settings.lifetime")
    }

    private var title: String {
        guard let since = stats.since else { return String(localized: "All time") }
        let from = since.date().formatted(.dateTime.day().month(.abbreviated).year())
        return String(localized: "All time · since \(from)")
    }

    /// The Device card's row, with the value in primary ink and a little weight: these
    /// are facts about the person, not the link's status.
    private func row(_ label: String, _ value: String) -> some View {
        LabeledContent(label) {
            Text(value)
                .fontWeight(.semibold)
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
        }
    }

    /// Hours and minutes once there are hours, minutes and seconds before — two units,
    /// never three: "4 hr, 40 min" reads, "4 hr, 40 min, 12 sec" is a stopwatch.
    private var heldText: String {
        Duration.seconds(stats.heldSeconds)
            .formatted(.units(allowed: [.hours, .minutes, .seconds], width: .abbreviated,
                              maximumUnitCount: 2))
    }

    /// Tonnes once kilograms pass a thousand — "20.3 t" is the odometer reading, "20 300
    /// kg" is a spreadsheet. Pounds stay pounds: a ton is two different weights in English.
    private var volumeText: String {
        switch weightUnit {
        case .kg:
            if stats.volumeKg >= 1000 {
                return String(localized: "\((stats.volumeKg / 1000).formatted(.number.precision(.fractionLength(1)))) t")
            }
            return "\(stats.volumeKg.formatted(.number.precision(.fractionLength(0)))) \(weightUnit.symbol)"
        case .lb:
            return "\(weightUnit.fromKg(stats.volumeKg).formatted(.number.precision(.fractionLength(0)))) \(weightUnit.symbol)"
        }
    }

    private var spoken: String {
        guard !stats.isEmpty else { return String(localized: "All time. Your numbers land here after the first session.") }
        var parts = [
            String(localized: "\(stats.sessions) sessions"),
            String(localized: "\(stats.pulls) pulls"),
            String(localized: "\(heldText) under tension"),
            String(localized: "volume \(volumeText)"),
            String(localized: "\(stats.daysTrained) days trained"),
        ]
        if stats.climbDays > 0 { parts.append(String(localized: "\(stats.climbDays) climbing days")) }
        parts.append(String(localized: "heaviest pull \(weightUnit.number(stats.heaviestPullKg)) \(weightUnit.spokenName)"))
        return "\(title). \(parts.joined(separator: ", "))."
    }
}
