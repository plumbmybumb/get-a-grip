// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit

/// The routine you are committing to, as one card, in three ZONES: identity + today's
/// status (tight), the plan as one line (opening its overview), and the one action.
/// Spacing does the grouping — 6 pt inside a zone, 16 pt between zones: at a uniform
/// 14 pt, title, dots, plan and button read as six unrelated things "slapped in".
///
/// **The grip ladder left this card on 2026-08-17** as dashboard clutter (Nuri's call).
/// A card here owes you which routine, how much you have done, what it costs and the
/// way in; which fingers on which edge is the editor's and runner's job, one tap away.
///
/// Every input is a VALUE (`RoutineSummary`, not a `SessionTemplate`), so the card
/// previews without a `ModelContext` and every derived number is computed once in the
/// store, not in a body that runs on every scroll frame. `completionText` comes from
/// `TemplateStore` so the spoken sentence and the "1 of 2" fragment cannot drift apart.
struct RoutineCard: View, Equatable {
    let summary: RoutineSummary
    /// `TemplateStore.completionText(_:)` — a whole sentence, which is what VoiceOver
    /// reads in place of the numeral fragment.
    let completionText: String
    /// Marks the deck's HOME card — the routine the app would front on its own (the last
    /// reminder to call, else the one mid-ritual, else the primary) — so swiping away and
    /// back still answers "which one is being asked of me now". Never set on a
    /// single-routine screen.
    let isUpNext: Bool
    let deviceState: ProgressorConnectionState
    let battery: Double?

    var onStart: () -> Void
    /// The same session with no gauge — timers, count-in and hand prompts only.
    var onStartTimerOnly: () -> Void
    var onEdit: () -> Void
    var onOverview: () -> Void = {}
    var onDuplicate: () -> Void
    /// Opens the QR sheet. Handed up because encoding needs the draft, and this view only
    /// sees a summary VALUE.
    var onShare: () -> Void
    var onNew: () -> Void
    var onMakePrimary: () -> Void
    var onDelete: () -> Void
    var onDemo: () -> Void

    /// Compared on what the card DRAWS, never on its eleven closures, which made it unequal
    /// to itself on every Today render. Each closure acts on `summary.id`, so equal values
    /// act identically. The house pattern from `BuilderInputs`: values plus closures, no
    /// `Binding`, nonisolated `==` over `let` Sendable inputs. A new drawn input must join
    /// this list the day the card starts reading it.
    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.summary == b.summary && a.completionText == b.completionText
            && a.isUpNext == b.isUpNext && a.deviceState == b.deviceState
            && a.battery == b.battery
    }

    @Environment(\.openURL) private var openURL
    /// Only for `planRow`'s swap to the plain sentence at accessibility sizes: a row of
    /// pictograms cannot wrap.
    @Environment(\.dynamicTypeSize) private var typeSize
    /// Completion dots: the same ink vocabulary as the consistency strip and the builder's
    /// Sessions-a-day row — a filled dot is a session that happened.
    ///
    /// 16, relative to `.body`, not `.caption`: at 9 pt a row of small round marks reads as
    /// decoration rather than a COUNT, and "two of these" is the entire message.
    @ScaledMetric(relativeTo: .body) private var sessionDot: CGFloat = 16

    var body: some View {
        // FLAT, not material: the long press lifts the card through a portal of its
        // own layers, and a backdrop blur is the one thing that can come out
        // differently in the copy. Fitted to match the material (see `CardFill`).
        // The grey band past the corners during the lift was NOT the material but the
        // deck's scroll view clipping the card — see `routineDeck`'s
        // `.scrollClipDisabled()`.
        MaterialCard(surface: .flat) {
            cardContent
        }
        // The glass vocabulary without the glass — see `glassRim`.
        .glassRim(in: RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        // Graphite, not bleu: bleu is the live-force signal, and "your next ritual"
        // is ink-family information like the done-dots. 1.5 pt at half strength sits
        // a clear step above the ghost card's hairline ("could exist" vs "is the one")
        // and far below an alarm. A STROKE, so it survives Reduce Transparency,
        // greyscale and colour vision; VoiceOver hears it on the title.
        .overlay {
            if isUpNext {
                RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                    .strokeBorder(Accent.graphite.opacity(0.5), lineWidth: 1.5)
                    .accessibilityHidden(true)
            }
        }
        // The same items as the `⋯` menu: free discoverability, since nothing else on
        // this screen wants a long press.
        //
        // **The DEFAULT preview, with the lift SHAPE declared — and no glass in the
        // subtree.** Found on device 2026-08-18, none of it reproducible in the
        // Simulator: Liquid Glass draws in its own pass and ignores the lift (the
        // preview rendered as one floating glass button); an explicit solid preview
        // popped in after the source hid; and the default morph clipped at the
        // corners because the lift's shape defaulted to the bounds rectangle. So:
        // solid buttons make the source snapshot true, the `.contextMenuPreview`
        // content shape gives the lift the card's rounded rect, and the system's
        // default morph needs no explicit preview.
        .contentShape(.contextMenuPreview,
                      RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .contextMenu { menuItems }
    }

    /// The card's three zones, shared by the live card and the context-menu preview so
    /// the preview cannot drift.
    private var cardContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Zone 1 — identity and today's status. The dots are a SUBTITLE, locked to
            // the name they qualify, not floating between title and plan.
            VStack(alignment: .leading, spacing: 6) {
                titleRow
                completionRow
            }
            planRow
            startBlock
        }
    }

    // MARK: - 1 · Title

    private var titleRow: some View {
        HStack(spacing: 0) {
            // The routine's SIGNATURE grip leads the name. Without the ladder the card
            // went "bland and text heavy", and an identical badge on every card failed
            // too (Nuri, 2026-08-17). One derived mark: identity that differs exactly
            // when the routines do.
            EdgeMark(fingers: summary.signatureFingers ?? .four, rungTint: rungTint)
                .padding(.trailing, 10)

            // `.title2`, a notch above other card titles: on the deck this card IS the
            // chooser, and the name is what a peeking card must say. Still `.semibold`.
            Text(summary.name)
                .font(.system(.title2, weight: .semibold))
                .foregroundStyle(Ink.primary)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
                // The border's spoken counterpart: a stroke is invisible to VoiceOver.
                .accessibilityLabel(isUpNext ? String(localized: "\(summary.name). Up next.") : summary.name)

            Spacer(minLength: 8)

            Menu {
                menuItems
            } label: {
                // 44pt frame + content shape, and NO glass (muddy inside a material card);
                // otherwise only the glyph's opaque pixels hit-test.
                Image(systemName: "ellipsis")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            // Pulls the glyph back onto the card's inner edge while the target stays 44.
            .padding(.trailing, -10)
            .accessibilityLabel(String(localized: "Routine options"))
            // The timer-only option lives here while a connected gauge hides its shortcut.
        }
    }

    /// Nuri's intensity ladder for the rung (2026-08-17): how hard the routine is, at a
    /// glance, in the mark it already wears. Bleu when nothing resolves (no targets, or
    /// kilogram bands with no max — the identity state); moss at or under 30 % of max;
    /// armed orange between; alarm red from 80 %. Green's first appearance and red at rest
    /// are both spent on purpose: a near-max prescription is attention-family information
    /// about somebody's fingers. Colour only reinforces: the plan row SPEAKS the
    /// percentage, and in greyscale the mark still means "a routine".
    private var rungTint: Color {
        PlanMath.IntensityBand.band(for: summary.peakIntensity).tint
    }

    /// The spoken (and colourblind-proof) form of the rung's colour.
    private var intensitySuffix: String {
        guard let peak = summary.peakIntensity else { return "" }
        return String(localized: ". Peak target \(Int((peak * 100).rounded())) percent of max.")
    }

    @ViewBuilder private var menuItems: some View {
        Button(action: onEdit) { Label("Edit routine", systemImage: "slider.horizontal.3") }
        Button(action: onDuplicate) { Label("Duplicate", systemImage: "plus.square.on.square") }
        Button(action: onNew) { Label("New routine…", systemImage: "plus") }
        Button(action: onMakePrimary) { Label("Make this the one Today opens on", systemImage: "arrow.up.to.line") }
        // Always available here: the card only offers it in front with no gauge
        // connected, but training unmeasured is legitimate at any time.
        Button(action: onStartTimerOnly) { Label("Start without a gauge", systemImage: "timer") }
        // A plain `Label`: the menu lives inside the subtree the context-menu lift
        // re-composites, so nothing in it may carry glass.
        Button(action: onShare) { Label("Share routine…", systemImage: "qrcode") }
        Divider()
        // A SUBMENU, not a flat destructive row: a tap meant for "Start without a
        // gauge" landed one row low on Delete, and the same mis-tap missed the Undo
        // bar too. Not the banned confirmation DIALOG — just menu navigation one
        // level deeper, so a mis-tap opens a second small menu and deletes nothing.
        Menu {
            Button(role: .destructive, action: onDelete) { Label("Delete routine", systemImage: "trash") }
        } label: {
            Label("Delete routine…", systemImage: "trash")
        }
    }

    // MARK: - 2 · Completion

    @ViewBuilder private var completionRow: some View {
        HStack(spacing: 8) {
            sessionDots

            // A CLIMB DAY says what happened, not what didn't: "0 of 2 today" beside a
            // checkmark contradicts itself and says "you didn't train". The tally
            // counts hang sessions; today the training was somewhere else.
            if let climb = summary.climbedToday {
                Text(climb == .climbLimit ? "Limit session" : "Volume session")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                Text(hangSuffix)
                    .font(.system(.subheadline, weight: .medium))
                    .foregroundStyle(Ink.secondary)
            } else if summary.benchmarkedToday {
                // Same anatomy as the climb line: what the day WAS, then any hangs as
                // the extra. Never "at the gym" — a testing day (a max or critical force) is its own kind of day.
                Text("Testing day")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                if summary.completedToday > 0 {
                    Text(summary.completedToday == 1
                         ? "· 1 hang" : "· \(summary.completedToday) hangs")
                        .font(.system(.subheadline, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                }
            } else if summary.isOnDemand {
                // No target, so no tally and NO GUILT.
                Text(summary.completedToday > 0 ? "Done today" : "Whenever you're fresh")
                    .font(.system(.subheadline, weight: summary.completedToday > 0 ? .semibold : .medium))
                    .foregroundStyle(summary.completedToday > 0 ? Ink.primary : Ink.secondary)
            } else if summary.sessionsPerDay > 1 {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(summary.completedToday, format: .number)
                        .font(.system(.title3, weight: .medium))
                        .monospacedDigit()
                        .contentTransition(.numericText())
                        .foregroundStyle(Ink.primary)
                    Text("of \(summary.sessionsPerDay) today")
                        .font(.system(.subheadline, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                }
            } else {
                // "1 of 1" is silly; say what the number stood for.
                Text(summary.completedToday > 0 ? "Done today" : "Not done yet today")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(summary.completedToday > 0 ? Ink.primary : Ink.secondary)
            }

            Spacer(minLength: 8)

            if summary.targetMet {
                // Graphite, never green: filled ink already means "a session that happened".
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(completionText)
    }

    /// "at the gym", plus any hang rounds anyway — said second, as the extra on a day
    /// already counted.
    private var hangSuffix: String {
        switch summary.completedToday {
        case 0:  String(localized: "at the gym")
        case 1:  String(localized: "at the gym · 1 hang")
        default: String(localized: "at the gym · \(summary.completedToday) hangs")
        }
    }

    private var sessionDots: some View {
        // Spacing scales with the dot: touching circles read as a shape, separated
        // ones as "two of these".
        HStack(spacing: sessionDot * 0.45) {
            // ONE notched dot on a climb day, not empty rings: nothing is owed. A
            // benchmark day gets a plain single dot — the notch is a climbing mark.
            if summary.climbedToday != nil {
                Circle()
                    .fill(Accent.graphite)
                    .frame(width: sessionDot, height: sessionDot)
                    .climbNotch(true)
            } else if summary.benchmarkedToday {
                Circle()
                    .fill(Accent.graphite)
                    .frame(width: sessionDot, height: sessionDot)
            } else if summary.isOnDemand {
                // No slots owed, so no empty rings; a dot appears once a session happened.
                if summary.completedToday > 0 {
                    Circle()
                        .fill(Accent.graphite)
                        .frame(width: sessionDot, height: sessionDot)
                }
            } else {
                ForEach(0..<max(1, summary.sessionsPerDay), id: \.self) { index in
                    dot(filled: index < summary.completedToday)
                }
            }
        }
        // The sentence beside them says the same; reading both is a duplicate swipe.
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func dot(filled: Bool) -> some View {
        Group {
            if filled {
                    Circle()
                        .fill(Accent.graphite)
                        .frame(width: sessionDot, height: sessionDot)
                } else {
                    // 1.5 pt, not 2: the hollow ring must match the filled dot's optical
                    // weight. 0.85 opacity, measured on the dark screenshot: 0.5 hit 2.1:1 and
                    // 0.75 only 2.98:1, because a thin ring is mostly antialiased edge. A mark
                    // that MEANS "not done yet" must clear 3:1.
                    Circle()
                        .strokeBorder(Ink.tertiary.opacity(0.85), lineWidth: 1.5)
                        .frame(width: sessionDot, height: sessionDot)
            }
        }
    }

    // MARK: - 2 · The plan, in one line — and the line IS the editor entry

    /// What the routine costs — "20 mm · 6 sets · 36 pulls · ≈21 min" — and tapping it
    /// opens the editor: the door to change the plan is the plan itself. Also reachable
    /// from the `⋯` menu and the long-press mirror.
    ///
    /// **No platter.** The inset well framed a DIAGRAM; with the ladder gone there is
    /// nothing to frame, and a platter around one line makes a sentence look like a text
    /// field. The chevron and press feedback say this row is a door.
    ///
    /// `summary.metaLine`, not a line assembled here: it is tested in `TemplateStoreTests`,
    /// and a second version is how two screens end up quoting different pull counts. It
    /// carries the SET COUNT, which nothing else on the card now states.
    private var planRow: some View {
        Button(action: onOverview) {
            HStack(spacing: 6) {
                // Glyphed stats rather than one dotted sentence: values carry the weight,
                // symbols give texture. The SENTENCE stays the accessibility value and the
                // fallback at accessibility sizes, since a glyph row cannot wrap.
                if typeSize >= .accessibility1 {
                    Text(summary.metaLine)
                        .font(.system(.footnote))
                        .monospacedDigit()
                        .foregroundStyle(Ink.secondary)
                        // At these sizes this is the ONLY statement of the plan, and truncating
                        // "≈21 min" costs the fact the row exists for. The screen already scrolls.
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                } else {
                    // Three fits, in order: the full glyph row, a COMPACT glyph row, then the
                    // sentence. An edge SPAN ("20–10 mm") overflowed the card on hardware, and
                    // falling straight back to the sentence lost the icons on exactly the
                    // routine the span exists for (Nuri: "the little icons being gone is sad").
                    // Width-only choice on one row, so the page-level ViewThatFits trap
                    // (large-title proposal) does not apply.
                    ViewThatFits(in: .horizontal) {
                        statRow(compact: false)
                        statRow(compact: true)
                        Text(summary.metaLine)
                            .font(.system(.footnote))
                            .monospacedDigit()
                            .foregroundStyle(Ink.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }
                Spacer(minLength: 6)
                Image(systemName: "chevron.right")
                    .font(.system(.caption, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
            }
            // A footnote line is ~20 pt tall, so it is padded to a legal target, and
            // `contentShape` is mandatory: a `Spacer` and padding are not hit-tested.
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "Routine overview"))
        .accessibilityIdentifier("routine.overview.open")
        // The card no longer draws the grips, so it must not speak them — the
        // overview speaks grips and timing, with an Edit action. `metaLine` states
        // the same facts as the glyphs by construction. The intensity suffix rides
        // here because the rung's colour is invisible to VoiceOver and greyscale.
        .accessibilityValue(summary.metaLine + intensitySuffix)
    }

    /// The plan's numbers with a symbol each — edge, sets, pulls, duration. The VALUE
    /// carries the ink (`Ink.secondary`, medium); the symbol is `Ink.tertiary` decoration
    /// (each sits beside the word that names it, so it owes nothing to the 3:1 floor).
    /// Monospaced so an edit does not shimmer the row.
    ///
    /// Compact sheds only what the symbol makes redundant: count words go ("6 sets" → "6"),
    /// units that disambiguate a bare number stay, glued on ("20–10mm", "≈11min").
    private func statRow(compact: Bool) -> some View {
        HStack(spacing: compact ? 10 : 13) {
            // `edgeLine`, not `sharedEdgeMM`: a mixed ladder states its span in order
            // ("20–10 mm"); dropping the edge read as the app not knowing its routine.
            if let edge = summary.edgeLine {
                stat("ruler", compact ? edge.replacingOccurrences(of: " mm", with: "mm") : edge)
            }
            stat("square.stack",
                 compact ? "\(summary.setCount)"
                         : String(localized: "\(summary.setCount) \(summary.setCount == 1 ? String(localized: "set") : String(localized: "sets"))"))
            stat("repeat",
                 compact ? "\(summary.totalReps)"
                         : String(localized: "\(summary.totalReps) \(summary.totalReps == 1 ? String(localized: "pull") : String(localized: "pulls"))"))
            stat("clock",
                 compact ? PlanMath.approxMinutes(summary.estimatedSeconds)
                               .replacingOccurrences(of: " min", with: "min")
                         : PlanMath.approxMinutes(summary.estimatedSeconds))
        }
    }

    private func stat(_ symbol: String, _ value: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: symbol)
                .font(.system(.caption2, weight: .medium))
                .foregroundStyle(Ink.tertiary)
            Text(value)
                .font(.system(.footnote, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(Ink.secondary)
                .lineLimit(1)
                // Must fit one row through the ordinary ramp; the accessibility threshold
                // swaps to the sentence before scaling could make a numeral dishonest.
                .minimumScaleFactor(0.85)
        }
    }

    // MARK: - 3 · Start

    @ViewBuilder private var startBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            // SOLID buttons, not glass, everywhere in this card: Liquid Glass ghosts
            // through the context-menu lift (see `SolidPrimaryButton`).
            if summary.targetMet {
                // Demoted, never gone: a bonus session stays possible, nagging does not.
                SolidSecondaryButton(title: String(localized: "Start another"), systemImage: "play.fill", action: onStart)
                    .frame(maxWidth: .infinity)
                    .accessibilityHint(hint)
            } else {
                // Graphite, not bleu (bleu is spent once the runner opens). ALWAYS enabled:
                // the runner's first phase is connect-and-tare, so tapping while
                // disconnected is the common path, and disabling it turns a ritual into a chore.
                SolidPrimaryButton(title: startTitle, systemImage: "play.fill",
                                   tint: Accent.graphite, action: onStart)
                    .accessibilityHint(hint)
            }

            if let note = connectionNote { noteRow(note) }
            if let note = batteryNote { noteRow(note) }

            // **Only while there is no gauge on the line** — flat battery, left at
            // home, Bluetooth off (Nuri, 2026-08-09). Beside a connected Progressor it
            // would offer to throw the measurement away; the ⋯ menu still has it.
            if !deviceState.isConnected {
                Button(action: onStartTimerOnly) {
                    Text("Start without a gauge")
                        .font(.system(.footnote, weight: .semibold))
                        .foregroundStyle(Accent.graphite)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(.rect)
                }
                    .buttonStyle(PressFeedbackButtonStyle())
                    // 44 tall for the target, pulled up tight under the note: the stack gap on
                    // top was pure spend on a page that has to fit.
                    .padding(.top, -6)
                    .accessibilityHint(String(localized: "Runs the timers and hand prompts only. Nothing is measured."))
            }

            switch deviceState {
            case .unauthorized:
                SolidSecondaryButton(title: String(localized: "Open Settings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
            case .unsupported:
                // Always compiled in, never `#if DEBUG` — without hardware, in the
                // Simulator or in App Review, this is the only way to see it work.
                SolidSecondaryButton(title: String(localized: "Try demo mode"), action: onDemo)
            default:
                EmptyView()
            }
        }
    }

    /// No ordinal once disconnected: "Connect and start second session" is too long, and
    /// the line above already says which session.
    private var startTitle: String {
        guard deviceState.isConnected else { return String(localized: "Connect and start") }
        guard summary.sessionsPerDay > 1 else { return String(localized: "Start session") }
        switch summary.completedToday {
        case 0: return String(localized: "Start first session")
        case 1: return String(localized: "Start second session")
        case 2: return String(localized: "Start third session")
        case 3: return String(localized: "Start fourth session")
        default: return String(localized: "Start session \(summary.completedToday + 1)")
        }
    }

    /// The note under the button explains its title, so it travels with the button for
    /// VoiceOver. Empty when there is none — `accessibilityHint` has no Optional overload.
    private var hint: String {
        [connectionNote?.text, batteryNote?.text]
            .compactMap { $0 }
            .joined(separator: " ")
    }

    private struct Note {
        var symbol: String?
        var text: String
        var tint: Color
    }

    private var connectionNote: Note? {
        switch deviceState {
        case .connected:
            return nil
        case .idle, .disconnected:
            return Note(symbol: nil, text: String(localized: "Keep the gauge unloaded while it connects and tares."), tint: Ink.tertiary)
        case .scanning, .connecting:
            return Note(symbol: nil, text: String(localized: "Searching for your gauge…"), tint: Ink.tertiary)
        case .bluetoothOff:
            return Note(symbol: "exclamationmark.triangle.fill",
                        text: String(localized: "Bluetooth is off — turn it on to measure."), tint: Accent.alarm)
        case .unauthorized:
            return Note(symbol: "exclamationmark.triangle.fill",
                        text: String(localized: "Bluetooth access is off for Get a Grip."), tint: Accent.alarm)
        case .unsupported:
            return Note(symbol: nil, text: String(localized: "This device has no Bluetooth radio."), tint: Ink.tertiary)
        }
    }

    /// Non-blocking, and amber rather than red: a low gauge battery is a thing to know
    /// before you start, not a reason not to.
    private var batteryNote: Note? {
        guard let battery, battery < 0.15 else { return nil }
        let percent = BatteryDisplay.percentage(battery)
        return Note(symbol: "bolt.badge.exclamationmark",
                    text: String(localized: "Gauge battery at \(percent)% — charge it soon."), tint: StatusTint.armed)
    }

    private func noteRow(_ note: Note) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            if let symbol = note.symbol {
                Image(systemName: symbol)
                    .font(.system(.caption, weight: .semibold))
            }
            Text(note.text)
                .font(.system(.footnote))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(note.tint)
        .frame(maxWidth: .infinity, alignment: .leading)
        // The Start button already carries this as its hint; read twice it is noise.
        .accessibilityHidden(true)
    }

}
