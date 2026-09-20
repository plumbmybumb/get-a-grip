// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// **All time, at the top of Settings** (Nuri, 2026-09-20: "lifetime stats — number of
/// routines done, total load lifetime, anything else?"). Six numbers and the date they
/// count from: sessions, pulls, time under tension, volume, days trained, the heaviest
/// pull. Nothing here is a chart or a trend — History owns those — this is the odometer.
///
/// Folded from denormalized columns (`Collection.lifetime`), so opening the tab costs a
/// row per session, never a decode.
struct LifetimeCard: View {
    var stats: LifetimeStats
    @Environment(\.weightUnit) private var weightUnit
    /// Tiles wrap on their own: three to a row on a phone, fewer once the type grows,
    /// and never a column squeezed to an ellipsis.
    @ScaledMetric(relativeTo: .title3) private var tileWidth: CGFloat = 100

    var body: some View {
        MaterialCard {
            VStack(alignment: .leading, spacing: 14) {
                CapsLabel(title)
                if stats.isEmpty {
                    Text("Your all-time numbers land here after the first session.")
                        .font(.system(.subheadline))
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: tileWidth), spacing: 8, alignment: .top)],
                              alignment: .leading, spacing: 14) {
                        tile(stats.sessions.formatted(), String(localized: "Sessions"))
                        tile(stats.pulls.formatted(), String(localized: "Pulls"))
                        tile(heldText, String(localized: "Under tension"))
                        tile(volumeText, String(localized: "Volume"))
                        tile(stats.daysTrained.formatted(), String(localized: "Days trained"))
                        tile(weightUnit.text(stats.heaviestPullKg), String(localized: "Heaviest pull"))
                    }
                    Text(footnote)
                        .font(.system(.caption))
                        .foregroundStyle(Ink.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
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

    private func tile(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value)
                .font(.system(.title3, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            CapsLabel(label)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Hours and minutes once there are hours, minutes and seconds before — two units,
    /// never three: "3 hr, 12 min" reads, "3 hr, 12 min, 40 sec" is a stopwatch.
    private var heldText: String {
        Duration.seconds(stats.heldSeconds)
            .formatted(.units(allowed: [.hours, .minutes, .seconds], width: .abbreviated,
                              maximumUnitCount: 2))
    }

    /// Tonnes once kilograms pass a thousand — "21.6 t" is the odometer reading, "21 600
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

    /// What the two numbers people ask about MEAN, and where the climbs went.
    private var footnote: String {
        var lines = [String(localized: "Volume is load × pulls, added up. Under tension is every second on the edge.")]
        if stats.climbs > 0 {
            lines.append(stats.climbs == 1
                         ? String(localized: "One of the days trained was at the climbing gym.")
                         : String(localized: "\(stats.climbs) of the days trained were at the climbing gym."))
        }
        return lines.joined(separator: " ")
    }

    private var spoken: String {
        guard !stats.isEmpty else { return String(localized: "All time. Your numbers land here after the first session.") }
        return String(localized: """
            \(title). \(stats.sessions) sessions, \(stats.pulls) pulls, \(heldText) under tension, \
            volume \(volumeText), \(stats.daysTrained) days trained, heaviest pull \
            \(weightUnit.number(stats.heaviestPullKg)) \(weightUnit.spokenName).
            """)
    }
}
