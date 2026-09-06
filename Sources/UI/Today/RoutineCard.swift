// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI
import UIKit

/// The routine you are committing to, as one card, in three ZONES: identity + today's
/// status (tight), the plan as one line (which IS the edit surface), and the one action.
/// Spacing does the grouping — 6 pt inside a zone, 16 pt between zones — because
/// proximity is how the eye assigns belonging: when every row sat a uniform 14 pt from
/// its neighbour, title, dots, plan and button read as six unrelated things "slapped in"
/// (Nuri's words, and he was right).
///
/// **The grip ladder and the platter it sat in left this card on 2026-08-17** — the
/// per-set finger diagrams read as clutter on a dashboard (Nuri's call). Today is a
/// dashboard, not a document: what a card here owes you is which routine, how much of it
/// you have done, what it costs and the way in. Which fingers on which edge is what the
/// editor and the runner are for, and both are one tap away. Removing it also took the
/// only reason the plan needed a platter with it — see `planRow`.
///
/// Every input is a VALUE — `RoutineSummary` rather than a `SessionTemplate` — so the
/// card previews and reasons without a `ModelContext`, and so every derived number is
/// computed once in the store instead of in a body that runs on every frame of a
/// scroll. `completionText` comes in from `TemplateStore` for the same reason it exists
/// there: the spoken sentence and the "1 of 2" fragment beside it must never be able to
/// drift apart, and rebuilding the sentence here would be a second source of truth.
struct RoutineCard: View {
    let summary: RoutineSummary
    /// `TemplateStore.completionText(_:)` — a whole sentence, which is what VoiceOver
    /// reads in place of the numeral fragment.
    let completionText: String
    /// Marks the deck's HOME card — the routine the app would front on its own (the
    /// last reminder to call, else the one mid-ritual, else the primary). One quiet
    /// graphite line, so swiping away to browse and back still answers "which one is
    /// being asked of me right now". Never set on a single-routine screen, where it
    /// would distinguish the only thing there is.
    var isUpNext: Bool = false
    var deviceState: ProgressorConnectionState = .idle
    var battery: Double? = nil

    var onStart: () -> Void
    /// The same session with no gauge — timers, count-in and hand prompts only.
    var onStartTimerOnly: () -> Void
    var onEdit: () -> Void
    var onDuplicate: () -> Void
    /// Opens the QR sheet. The card hands the tap up rather than building the code
    /// itself — encoding needs the draft, and this view only ever sees a summary VALUE.
    var onShare: () -> Void
    var onNew: () -> Void
    var onMakePrimary: () -> Void
    var onDelete: () -> Void
    var onDemo: () -> Void

    @Environment(\.openURL) private var openURL
    /// Only consulted by `planRow`, to swap the glyphed stats for the plain sentence at
    /// accessibility sizes — a row of pictograms cannot wrap, and someone who asked for
    /// big text is served by words.
    @Environment(\.dynamicTypeSize) private var typeSize
    /// The completion dots are the same ink vocabulary as the consistency strip and the
    /// builder's Sessions-a-day row — a filled dot is a session that happened.
    ///
    /// 16, relative to `.body`, not `.caption`: at 9 pt a row of small round marks reads
    /// as decoration rather than as a COUNT, and "two of these" is the entire message.
    /// The size was won against the grip ladder that used to sit directly below — four
    /// finger marks per set, one row away, at the same size — and it stays now the ladder
    /// has gone, because the dots still have to read as a tally on their own.
    @ScaledMetric(relativeTo: .body) private var sessionDot: CGFloat = 16

    var body: some View {
        MaterialCard {
            cardContent
        }
        // Graphite, not bleu: bleu is the live-force signal, and "your next ritual"
        // is ink-family information like the done-dots. 1.5 pt at half strength sits
        // one clear step above the ghost card's hairline (0.35 tertiary) — the ghost
        // outline means "could exist", this means "is the one" — while staying far
        // below an alarm. A STROKE, so it survives Reduce Transparency, greyscale and
        // every colour vision; VoiceOver hears it on the title instead.
        .overlay {
            if isUpNext {
                RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                    .strokeBorder(Accent.graphite.opacity(0.5), lineWidth: 1.5)
                    .accessibilityHidden(true)
            }
        }
        .tourAnchor(.routineCard)
        // The same items as the `⋯` menu: free discoverability at zero hit-target cost,
        // since a long press is not a gesture anything else on this screen wants.
        //
        // **The DEFAULT preview, with the lift SHAPE declared — and no glass in the
        // subtree.** Three device-found failures shaped this line (all 2026-08-18,
        // none reproducible in the Simulator, which composites glass differently):
        // the default preview first rendered as one floating glass button, because
        // Liquid Glass draws in its own pass and ignores the lift; an explicit solid
        // preview fixed the end state but popped in after the source hid; and once
        // the card was de-glassed (`SolidPrimaryButton`/`SolidSecondaryButton`), the
        // now-healthy default morph clipped at the corners because the lift's shape
        // defaulted to the bounds rectangle while its width fought the preview's
        // hardcoded one. So: solid buttons make the source snapshot true, the
        // `.contextMenuPreview` content shape gives the lift the card's own rounded
        // rect, and the system's default morph — source-sized, seamless — needs no
        // explicit preview at all.
        .contentShape(.contextMenuPreview,
                      RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
        .contextMenu { menuItems }
    }

    /// The card's three zones, shared verbatim by the live card and the context-menu
    /// preview — one body, so the preview cannot drift from the card it stands in for.
    private var cardContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Zone 1 — identity and today's status. The dots are a SUBTITLE, locked
            // to the name they qualify; letting them float equidistant between title
            // and the plan was half of what made them read as loose parts.
            VStack(alignment: .leading, spacing: 6) {
                titleRow
                completionRow
            }
            // Still `.gripLadder` to the tour: the target is a stable identifier in
            // `TourTarget`, not a description, and this row is what took the ladder's
            // place as the card's statement of the plan. The tour's copy for that step
            // is written against the old rows and wants rewording.
            planRow
                .tourAnchor(.gripLadder)
            startBlock
        }
    }

    // MARK: - 1 · Title

    private var titleRow: some View {
        HStack(spacing: 0) {
            // The routine's SIGNATURE grip leads the name. When the grip ladder left,
            // it took every drawn element on the card with it and the surface went
            // typographic (Nuri, 2026-08-17: "bland and text heavy"); an identical
            // badge on every card was the first fix and failed the same day ("I don't
            // like how all routines have the same logo"). One derived mark per card:
            // identity that differs exactly when the routines do, never the per-set
            // inventory that was removed as clutter.
            EdgeMark(fingers: summary.signatureFingers ?? .four, rungTint: rungTint)
                .padding(.trailing, 10)

            // `.title2`, one notch up from the rest of the app's card titles: on the
            // deck this card IS the chooser, and the name is the one thing a peeking
            // or passing card must say. Music prints content names at hero size for
            // the same reason. Still `.semibold` — the house weight ceiling holds.
            Text(summary.name)
                .font(.system(.title2, weight: .semibold))
                .foregroundStyle(Ink.primary)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
                // The border's spoken counterpart — a stroke is invisible to VoiceOver,
                // and "which card is being asked of me" must not be sighted-only.
                .accessibilityLabel(isUpNext ? String(localized: "\(summary.name). Up next.") : summary.name)

            Spacer(minLength: 8)

            Menu {
                menuItems
            } label: {
                // 44pt frame + content shape, and NO glass: glass inside a
                // `.regularMaterial` card reads muddy, and a bare glyph's own opaque
                // content is all SwiftUI would otherwise hit-test.
                Image(systemName: "ellipsis")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            // Pulls the glyph back onto the card's inner edge while the target stays 44.
            .padding(.trailing, -10)
            .accessibilityLabel(String(localized: "Routine options"))
        }
    }

    /// Nuri's intensity ladder for the rung (2026-08-17): the card should say how hard
    /// the routine will be, at a glance, in the mark it already wears. Bleu when
    /// nothing resolves (no targets anywhere, or kilogram bands with no max on file —
    /// the identity state, and his own daily routine's state); moss at or under 30 %
    /// of max; the armed orange between; alarm red from 80 % up. Two palette rules are
    /// SPENT here deliberately, recorded in CLAUDE.md: green's first appearance, and
    /// red at rest — defensible because a near-max prescription is attention-family
    /// information about somebody's fingers, not chrome. Colour is reinforcement, not
    /// the only carrier: the plan row SPEAKS the percentage, and greyscale simply
    /// degrades to the mark meaning "a routine", which it always meant.
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
        // The always-available door. The card only offers it in front when there is no
        // gauge connected, but choosing to train unmeasured is legitimate at any time —
        // a weight belt, someone else's board, a session you just don't want logged in kg.
        Button(action: onStartTimerOnly) { Label("Start without a gauge", systemImage: "timer") }
        // A plain `Label`, like every row here — the menu is rendered inside the same
        // subtree the context-menu lift re-composites, so nothing in it may carry glass.
        Button(action: onShare) { Label("Share routine…", systemImage: "qrcode") }
        Divider()
        // A SUBMENU, not a flat destructive row. A live-simulator audit landed a tap
        // meant for "Start without a gauge" one row low, on Delete, with only the
        // Divider's hairline between them — and the only net was the 10 s Undo bar,
        // which the same tap-miss then also failed to catch. This is not the house's
        // banned confirmation DIALOG (no Yes/No prompt, no tax on the 99% of taps that
        // mean it): it is ordinary menu navigation one level deeper, so a mis-tap that
        // lands on "Delete routine…" opens a second small menu and does nothing —
        // nobody's data is gone until they deliberately tap the destructive row inside
        // it, at the SAME menu-row hit height as every other item here.
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

            // A CLIMB DAY says what happened, not what didn't. "0 of 2 today" beside a
            // checkmark is the card contradicting itself, and it is precisely the
            // "you didn't train" reading this feature exists to stop — the tally counts
            // hang sessions, and on this day the training was somewhere else.
            if let climb = summary.climbedToday {
                Text(climb == .climbLimit ? "Limit session" : "Volume session")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                Text(hangSuffix)
                    .font(.system(.subheadline, weight: .medium))
                    .foregroundStyle(Ink.secondary)
            } else if summary.benchmarkedToday {
                // Same anatomy as the climb line: what the day WAS, then any hangs as
                // the extra. Never "at the gym" — testing maxes is a different day.
                Text("Maxes tested")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Ink.primary)
                if summary.completedToday > 0 {
                    Text(summary.completedToday == 1
                         ? "· 1 hang" : "· \(summary.completedToday) hangs")
                        .font(.system(.subheadline, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                }
            } else if summary.isOnDemand {
                // No target, so no tally and NO GUILT — "not done yet today" is exactly
                // the sentence a whenever routine exists to never say.
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
                // A "1 of 1" readout is silly, so the one-a-day routine says the thing
                // the number was standing in for.
                Text(summary.completedToday > 0 ? "Done today" : "Not done yet today")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(summary.completedToday > 0 ? Ink.primary : Ink.secondary)
            }

            Spacer(minLength: 8)

            if summary.targetMet {
                // Graphite, never green: green is not in this palette, and filled ink
                // already means "a session that happened" in the dots and the strip.
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(completionText)
    }

    /// "at the gym", plus any hang rounds that happened anyway — said second, because
    /// they are the extra on a day the training already counted.
    private var hangSuffix: String {
        switch summary.completedToday {
        case 0:  String(localized: "at the gym")
        case 1:  String(localized: "at the gym · 1 hang")
        default: String(localized: "at the gym · \(summary.completedToday) hangs")
        }
    }

    private var sessionDots: some View {
        // Spacing scales with the dot so the group stays legible as a COUNT — two
        // touching circles read as a shape, two separated ones read as "two of these".
        HStack(spacing: sessionDot * 0.45) {
            // ONE notched dot on a climb day, not a row of empty rings: the rings count
            // hang sessions owed, and nothing is owed. A benchmark day gets the same
            // single dot, plain — the notch stays a climbing mark.
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
                // No slots owed, so no empty rings to fill — one dot appears only
                // once a session happened.
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
        // The sentence beside them says the same thing; two readings of one fact is a
        // duplicate swipe, not extra information.
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
                    // 1.5 pt, not 2: the hollow ring has to sit at the same optical
                    // weight as the filled dot beside it, or the pair looks accidental.
                    // 0.85, measured twice on the dark screenshot: 0.5 hit 2.1:1 and
                    // 0.75 still only 2.98:1 against the card, because a 1.5 pt ring is
                    // mostly antialiased edge and its peak pixel never reaches the
                    // stroke colour. Under the 3:1 floor a mark that MEANS something
                    // ("not done yet") is decoration; this clears it with margin.
                    Circle()
                        .strokeBorder(Ink.tertiary.opacity(0.85), lineWidth: 1.5)
                        .frame(width: sessionDot, height: sessionDot)
            }
        }
    }

    // MARK: - 2 · The plan, in one line — and the line IS the editor entry

    /// What the routine costs — "20 mm · 6 sets · 36 pulls · ≈21 min" — and tapping it
    /// opens the editor. The door to change the plan is still the plan itself, which is
    /// the rule that retired the old chevron footnote stranded at the card's foot, three
    /// rows away from the thing it edited. Editing is reachable three ways in all: this
    /// row, the `⋯` menu, and the long-press mirror on the whole card.
    ///
    /// **No platter.** The inset well existed because the card had a DIAGRAM in it and a
    /// diagram floating on the same surface as text reads as debris; with the ladder gone
    /// there is nothing to frame, and a platter drawn around a single footnote makes a
    /// sentence look like a text field. The chevron and the press feedback are what say
    /// this row is a door.
    ///
    /// `summary.metaLine` rather than a line assembled here: it is the value's own
    /// statement of itself, already covered by `TemplateStoreTests`, and the card having
    /// its own second version of it is how two screens end up quoting different pull
    /// counts. It carries the SET COUNT again — the well's line dropped it while six grip
    /// clusters sat directly above saying the same thing in pictures, and now nothing
    /// else on the card would say how many sets there are.
    private var planRow: some View {
        Button(action: onEdit) {
            HStack(spacing: 6) {
                // Glyphed stats rather than one dotted sentence — the values carry the
                // weight and the symbols give the row texture, which is the difference
                // between a dashboard line and a caption. The SENTENCE remains the
                // accessibility value and the fallback: a glyph row cannot wrap, and at
                // accessibility sizes words serve better than pictograms anyway — the
                // same trade the grip ladder made before it left.
                if typeSize >= .accessibility1 {
                    Text(summary.metaLine)
                        .font(.system(.footnote))
                        .monospacedDigit()
                        .foregroundStyle(Ink.secondary)
                        // At these sizes this is the ONLY statement of the plan on the
                        // card, and truncating "≈21 min" off the end of it costs the
                        // reader the fact this row exists for. The extra line only
                        // ever appears on a screen that already scrolls.
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                } else {
                    // Three fits, in order: the full glyph row, a COMPACT glyph row,
                    // and only then the plain sentence. The edge SPAN ("20–10 mm")
                    // plus four symbols overflowed the card on hardware and truncated
                    // its own stat ("20–10…"), and the first fix fell straight back to
                    // the sentence — which threw away the icons on exactly the routine
                    // the span exists for (Nuri: "the little icons being gone is sad").
                    // The compact row keeps every symbol, drops only the count words
                    // the symbol beside them already labels, and keeps the units that
                    // disambiguate a bare number. Width-only choice on one row; the
                    // page-level ViewThatFits trap (large-title proposal) does not
                    // apply here.
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
            // One line of footnote is about 20 pt tall, so the row has to be padded to a
            // legal target rather than hit-tested as drawn — and `contentShape` is
            // mandatory, not tidy: a `Spacer` and empty padding contribute nothing to
            // SwiftUI's default hit area, which is the label's own opaque content.
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .accessibilityLabel(String(localized: "Edit routine"))
        // The card no longer draws the grips, so it must not speak them either — the
        // value is exactly what is on screen. The grips are spoken in the editor this
        // row opens, where they can also be changed. `metaLine`, not the glyph row's
        // fragments: the sentence and the glyphs state the same facts by construction
        // (both read the summary's own fields), and the sentence is the spoken form.
        // The intensity suffix rides here because the rung's colour is invisible to
        // VoiceOver and to greyscale — the number is the fact, the colour the glance.
        .accessibilityValue(summary.metaLine + intensitySuffix)
    }

    /// The plan's numbers with a symbol each — edge, sets, pulls, duration. The VALUE
    /// carries the ink (`Ink.secondary`, medium); the symbol is quiet (`Ink.tertiary`,
    /// deliberately decorative — each one sits beside the word that names it, so it
    /// owes nothing to the 3:1 graphics floor). Numbers stay monospaced so the row
    /// does not shimmer when an edit changes a digit.
    ///
    /// Compact keeps every symbol and sheds only what the symbol makes redundant: the
    /// count words go ("6 sets" → "6" beside the stack the reader learned from the
    /// full row, which is what every single-edge routine still shows), the units that
    /// disambiguate a bare number stay, glued to it ("20–10mm", "≈11min").
    private func statRow(compact: Bool) -> some View {
        HStack(spacing: compact ? 10 : 13) {
            // `edgeLine`, not `sharedEdgeMM`: a mixed ladder states its span in ladder
            // order ("20–10 mm") — dropping the edge because sets disagree read as the
            // app not knowing its own routine.
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
                // The four stats must fit one row through the ordinary size ramp;
                // the accessibility threshold above swaps to the sentence before
                // scaling could make a numeral dishonest.
                .minimumScaleFactor(0.85)
        }
    }

    // MARK: - 3 · Start

    @ViewBuilder private var startBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            // SOLID buttons, not the glass ones, everywhere inside this card — it is
            // the one subtree the context-menu lift re-composites, and Liquid Glass
            // ghosts through the lift (see `SolidPrimaryButton`'s comment).
            if summary.targetMet {
                // Demoted, never gone: a bonus session must stay possible, and nagging
                // must not.
                SolidSecondaryButton(title: String(localized: "Start another"), systemImage: "play.fill", action: onStart)
                    .frame(maxWidth: .infinity)
                    .accessibilityHint(hint)
            } else {
                // Graphite, not bleu — bleu is the live-force signal and is spent the
                // moment the runner opens. And ALWAYS enabled: the runner's first phase
                // is connect-and-tare, so tapping while disconnected is the common path.
                // Disabling the ritual's one button because a peripheral has not been
                // asked for yet turns the ritual into a chore.
                SolidPrimaryButton(title: startTitle, systemImage: "play.fill",
                                   tint: Accent.graphite, action: onStart)
                    .accessibilityHint(hint)
                    .tourAnchor(.startButton)
            }

            if let note = connectionNote { noteRow(note) }
            if let note = batteryNote { noteRow(note) }

            // **Only while there is no gauge on the line.** Flat battery, left at home,
            // Bluetooth off — the cases where the ritual would otherwise just not happen
            // (Nuri, 2026-08-09). Offering it beside a connected Progressor would be
            // offering to throw the measurement away, which nobody wants at 8 a.m.; it
            // stays reachable there through the ⋯ menu.
            if !deviceState.isConnected {
                Button("Start without a gauge", action: onStartTimerOnly)
                    .buttonStyle(PressFeedbackButtonStyle())
                    .font(.system(.footnote, weight: .semibold))
                    .foregroundStyle(Accent.graphite)
                    // 44 tall for the target, but pulled up tight against the note above
                    // it: the row's own height is the floor, and the 10 pt stack gap on
                    // top of it was pure spend on a page that has to fit.
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .padding(.top, -6)
                    .contentShape(.rect)
                    .accessibilityHint(String(localized: "Runs the timers and hand prompts only. Nothing is measured."))
                    .tourAnchor(.startWithoutGauge)
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

    /// The ordinal drops out entirely once disconnected: "Connect and start second
    /// session" is thirty characters, and the line above already says which session
    /// this is.
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

    /// A note under the button is the reason the button says what it says, so it travels
    /// with the button for VoiceOver rather than sitting in a separate element below it.
    /// Empty when there is no note — an empty hint is no hint, and `accessibilityHint`
    /// has no Optional overload to hand it.
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
        let percent = Int((battery * 100).rounded())
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
