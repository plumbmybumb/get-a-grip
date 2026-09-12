// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// The guided session — the screen you look at, at arm's length, with chalk on your
/// hands and something heavy on your fingers.
///
/// Everything is arranged around that: what matters most is largest, the phase is
/// legible from its colour before you read a word, and every control is big enough to
/// hit without looking. It owns no timing logic — `RunnerSession` holds the state
/// machine and this only draws what it says.
struct RunnerView: View {
    @Environment(\.weightUnit) private var weightUnit
    let template: SessionTemplate
    /// Run the whole thing on the clock, with no gauge — see `SessionRunner.timerOnly`.
    /// Everything force-shaped leaves the screen rather than sitting there at 0.0 kg,
    /// which would read as a broken gauge instead of an absent one.
    var timerOnly: Bool = false
    #if DEBUG
    /// In-memory screenshot fixtures use the production layout and live snapshot.
    var previewSession: RunnerSession?
    #endif

    @Environment(DeviceStore.self) private var device
    @Environment(TourController.self) private var tour
    @Environment(TemplateStore.self) private var templates
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    @State private var session: RunnerSession?
    /// Whether the grip hangs off the Dynamic Island — which is a fact about the DEVICE,
    /// resolved once the view is in a window (`IslandHand.isSupported` has nothing to read
    /// before that). The layout below reshapes around it, so it is answered here and used
    /// in both places rather than probed twice.
    @State private var hasIsland = false
    @State private var gripEmphasis = false
    @State private var gripBorderOpacity = 0.0
    @State private var emphasizedGripID: String?

    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 76
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 22
    @ScaledMetric(relativeTo: .largeTitle) private var dialDiameter: CGFloat = 240

    var body: some View {
        Group {
            if let session {
                if session.isFinished {
                    SessionSummaryView(template: template,
                                       plan: session.plan,
                                       reps: session.runner.results,
                                       startedAt: session.startedAt,
                                       finishedAt: session.finishedAt ?? session.startedAt,
                                       didAnyWork: session.runner.didAnyWork) { dismiss() }
                        .transition(.opacity)
                } else {
                    live(session)
                }
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .background { AppBackground() }
        // At the ROOT, so `ignoresSafeArea` actually reaches the top of the screen —
        // attached inside `live` it was clipped to a frame the safe area had already
        // shrunk, and drew nothing.
        .islandHand(grip: session?.isFinished == true ? nil : session?.snapshot.grip,
                    side: session?.snapshot.side,
                    isActive: !(session.map { isResting($0) } ?? true),
                    enabled: hasIsland, emphasized: gripEmphasis,
                    restFocused: session.map { showsRestFocus($0) } ?? false)
        .overlay {
            if let session,
               let cue = session.snapshot.screenBorderCue(
                   timerOnly: session.timerOnly,
                   measuredSignalIsLive: device.state.isConnected && device.isStreaming && device.isSignalFresh) {
                RunnerScreenBorder(cue: cue)
            }
        }
        .animation(Motion.state(reduceMotion),
                   value: session?.isFinished ?? false)
        .task(id: GripCueKey(id: session?.snapshot.newGripID,
                            resting: session?.snapshot.gripChangesNext == true)) {
            let id = session?.snapshot.newGripID
            let changed = emphasizedGripID != id
            emphasizedGripID = id
            let holdsForRest = session?.snapshot.gripChangesNext == true
            guard id != nil else {
                withAnimation(nil) { gripEmphasis = false; gripBorderOpacity = 0 }
                return
            }
            if !changed {
                // The same grip leaving rest fades out; it must not start a second cue.
                withAnimation(reduceMotion ? nil : Motion.gripChangeOut) {
                    gripEmphasis = holdsForRest
                    gripBorderOpacity = holdsForRest ? 1 : 0
                }
                return
            }
            withAnimation(nil) { gripEmphasis = false; gripBorderOpacity = 0 }
            // Only a new grip starts this cue; ordinary hand swaps do not restart it.
            withAnimation(reduceMotion ? nil : Motion.gripChangeIn) {
                gripEmphasis = true
                gripBorderOpacity = 1
            }
            if reduceMotion {
                try? await Task.sleep(for: .milliseconds(Motion.gripChangeHoldMilliseconds))
            } else {
                try? await Task.sleep(for: .milliseconds(Motion.gripChangeRiseMilliseconds))
                // Two gentle breaths within the hand's existing hold, not a flashing loop.
                for _ in 0..<2 {
                    guard !Task.isCancelled else { return }
                    withAnimation(Motion.gripChangePulse) { gripBorderOpacity = 0.4 }
                    try? await Task.sleep(for: .milliseconds(Motion.gripChangePulseMilliseconds))
                    guard !Task.isCancelled else { return }
                    withAnimation(Motion.gripChangePulse) { gripBorderOpacity = 1 }
                    try? await Task.sleep(for: .milliseconds(Motion.gripChangePulseMilliseconds))
                }
            }
            guard !Task.isCancelled else { return }
            // The phase owns a resting cue's lifetime, not a guessed wall-clock delay.
            // nextGripDiffers remains true while a rest is paused.
            guard !holdsForRest else { return }
            withAnimation(reduceMotion ? nil : Motion.gripChangeOut) {
                gripEmphasis = false
                gripBorderOpacity = 0
            }
        }
        .onChange(of: session?.snapshot.newGripID) { _, id in
            if id != nil, UIAccessibility.isVoiceOverRunning, let grip = session?.snapshot.grip {
                UIAccessibility.post(notification: .announcement,
                                     argument: String(localized: "New grip: \(grip.spoken)"))
            }
        }
        .onChange(of: session?.snapshot.upcomingGrip) { _, grip in
            if let grip, UIAccessibility.isVoiceOverRunning {
                UIAccessibility.post(notification: .announcement,
                                     argument: String(localized: "Next grip: \(grip.spoken)"))
            }
        }
        .onAppear {
            hasIsland = IslandHand.isSupported
            guard session == nil else { return }
            #if DEBUG
            if let previewSession {
                session = previewSession
                return
            }
            #endif
            // The maxes are read ONCE, here — a session's targets must not move under
            // the climber because a max was recorded on another device mid-workout.
            let new = RunnerSession(template: template, device: device,
                                    maxes: templates.maxTable, timerOnly: timerOnly)
            new.weightUnit = weightUnit
            session = new
            new.begin()
            // AFTER the session exists, in the same block that made it. As its own
            // `.onAppear` this ran first, found `session` nil, and started the tour over a
            // workout that was still counting down behind the scrim.
            guard !timerOnly else { return }
            tour.beginIfUnseen(.session)
            if tour.isRunning { new.send(.pause) }
        }
        .onDisappear { session?.end() }
        .onChange(of: weightUnit) { _, unit in session?.weightUnit = unit }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                // Bin whatever the radio buffered while we were away — see
                // `DeviceStore.dropStaleTrace`.
                device.dropStaleTrace()
                // **AND KICK THE STREAM.** This is the one that actually mattered
                // (Nuri, 2026-08-10: "when you first come back you get a little dot, then
                // after a while the stream continues"). The gauge stops sending while the
                // app is suspended, and the only thing that revived it was the stream
                // RunnerSession watchdog, which checks every 500 ms and requires
                // 0.8 s of Progressor silence (longer for sparse gauges). The dot was the one or
                // two samples that made it through; everything after was the wait.
                //
                // Re-sending start to a stream that is already alive is harmless, which is
                // why `startIfReady` sends it unconditionally. Not sending it is three dead
                // seconds in the middle of a rep.
                session?.startIfReady(cause: .foreground)
            } else if phase == .background, !timerOnly {
                // Clear the device-time anchor BEFORE suspension. Backgrounding already
                // forfeits any unobserved work; this prevents queued old-epoch samples
                // from inheriting a high-water mark across the foreground re-kick.
                session?.send(.streamRestarted)
            }
            // **Backgrounding no longer pauses a CONNECTED session.** With
            // `bluetooth-central` the app stays alive while the Progressor is delivering
            // notifications, so swiping home to change the music keeps the workout
            // running and the Live Activity carries it (Nuri, 2026-08-09).
            //
            // Disconnected, the old rule still holds and still matters: with no BLE to
            // keep the process alive iOS suspends us, samples stop, and a rep would
            // silently stall at whatever it had accrued. Pausing says so. And a gauge
            // whose "connection" is a duplicate-allowing advertisement scan is in exactly
            // that position once backgrounded — hence the capability, not a device check.
            // The whole rule lives in `BackgroundPausePolicy`.
            //
            // **`timerOnly` short-circuits the lot, whatever is connected.** A gauge-free
            // session never streams — `begin()` skips the connect, and every stream path
            // guards on it — so `bluetooth-central` keeps nothing alive even with a
            // Progressor sitting there connected from earlier. The 100 ms ticker stops with
            // the process and `holdTick` freezes with no PAUSED state to explain it, which
            // is precisely the silent stall this guard exists to prevent. With no gauge in
            // the loop, leaving the foreground always means losing the ability to measure.
            guard phase != .active,
                  timerOnly || BackgroundPausePolicy.pausesOnLeavingForeground(
                    isBackground: phase == .background,
                    isConnected: device.state.isConnected,
                    sustainsBackgroundStreaming:
                        device.gaugeCapabilities.sustainsBackgroundStreaming)
            else { return }
            session?.send(.pause)
        }
        .onChange(of: device.state.isConnected) { _, connected in
            session?.connectionChanged(isConnected: connected)
        }
        // Its own host: a full-screen cover draws over the root overlay.
        .tourHost(tour, act: .session)
        // **The session PAUSES while the tour talks**, and resumes when it is done.
        // Teaching over a running clock costs you the pull being explained, and a scrim
        // that blocks Pause and Skip while a hold counts down is worse than no tutorial.
        // Measured sessions only: with no gauge there is no lane to point at and the last
        // step would light an empty card.
        .onChange(of: tour.isRunning) { was, now in
            if was, !now { session?.send(.resume) }
        }
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.8), trigger: session?.repTick ?? 0)
        .sensoryFeedback(.selection, trigger: session?.phaseTick ?? 0)
    }

    // MARK: - The session screen

    @ViewBuilder
    private func live(_ session: RunnerSession) -> some View {
        if typeSize.isAccessibilitySize {
            // At large accessibility sizes a small phone cannot hold five readable
            // actions and the measurements at once. Keep every action reachable by
            // scrolling instead of clipping labels or reducing their chosen type.
            GeometryReader { geometry in
                ScrollView {
                    liveContent(session)
                        .frame(minHeight: max(0, geometry.size.height - (hasIsland ? 46 : 0)), alignment: .top)
                }
                .scrollBounceBehavior(.basedOnSize)
                .accessibilityIdentifier("runner.content")
                // The camera hand is fixed at the root. Keep the scrolling viewport
                // below it, so the top content cannot slide through the fingers.
                .padding(.top, hasIsland ? 46 : 0)
            }
        } else {
            liveContent(session)
        }
    }

    private func liveContent(_ session: RunnerSession) -> some View {
        VStack(spacing: 12) {
            if timerOnly {
                timerOnlyIdentity(session)
                timerDial(session)
                timerPositionLine(session)
            } else {
                measuredTop(session)
                ZStack {
                    LiveTrace(thresholdKg: session.plan.thresholdKg,
                              // Only while the rep is actually live. A lane drawn during
                              // the rest would ask you to hold a load you are not holding.
                              targetBand: isWorking(session) || isArmed(session)
                                  ? session.snapshot.targetBand : nil,
                              tint: tint(session))
                    // A connected gauge that is not sending is the one failure "0.0 kg"
                    // renders as a lie — it reads as a device measuring nothing rather
                    // than an app receiving nothing, and there is no way to tell them
                    // apart by looking. Say it, and say what to do.
                    if !session.snapshot.hasSignal
                        || (showsRestFocus(session) && !restSignalIsAvailable(session)) {
                        noSignalNotice(session)
                            .accessibilityIdentifier("runner.signalWarning")
                    }
                }
                .frame(minHeight: typeSize.isAccessibilitySize ? 240 : nil,
                       maxHeight: .infinity)
                .background(.regularMaterial,
                            in: RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                        .strokeBorder(StatusTint.armed, lineWidth: 3)
                        .opacity(session.snapshot.hasSignal ? gripBorderOpacity : 0)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                .overlay(alignment: .topLeading) {
                    // An overlay never participates in the graph's layout. Keep the
                    // newest readings at the right edge clear, and let signal warnings
                    // take priority over the brief grip cue.
                    if gripEmphasis, session.snapshot.hasSignal, !showsRestFocus(session),
                       let grip = session.snapshot.grip {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("New grip")
                                .font(.headline)
                                .foregroundStyle(Color(hex: "1B1F25"))
                            Text(grip.shortName)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color(hex: "1B1F25"))
                                .lineLimit(2)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(StatusTint.armed,
                                    in: RoundedRectangle(cornerRadius: Metrics.radiusInner))
                        .padding(12)
                        .padding(.trailing, 64)
                        .transition(.opacity)
                        .allowsHitTesting(false)
                        // The existing grip-change announcement already speaks this.
                        .accessibilityHidden(true)
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("runner.graph")
                .tourAnchor(.runnerTrace)
            }
            controls(session)
        }
        .padding(.horizontal, Metrics.hPadding)
        // The fingers reach ~92 pt down the screen and content starts at 59, so the hand
        // needs the gap bought for it — otherwise the grip name lands under the knuckles.
        .padding(.top, hasIsland ? (typeSize.isAccessibilitySize ? 0 : 46) : 8)
        .padding(.bottom, Metrics.spacing)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    /// The scheduled interval, not the dwindling numeral,
    /// chooses the rest layout, so a ten-second rest stays readable all the way to zero.
    private func showsRestFocus(_ session: RunnerSession) -> Bool {
        !timerOnly && session.snapshot.showsRestFocus
    }

    private func restSignalIsAvailable(_ session: RunnerSession) -> Bool {
        session.snapshot.hasSignal && !session.snapshot.linkIsDown
            && device.state.isConnected && device.isSignalFresh
    }

    /// The long-rest summary uses only the existing space above the graph. Reserve
    /// that whole block so the graph and controls stay anchored through a hand swap.
    /// Accessibility sizes reflow naturally inside the existing scrolling layout.
    private func measuredTop(_ session: RunnerSession) -> some View {
        let focused = showsRestFocus(session)
        return Group {
            if focused && typeSize.isAccessibilitySize {
                RunnerRestFocusSummary(snapshot: session.snapshot, showsGlyph: !hasIsland)
            } else {
                Group {
                    if focused {
                        // Hidden retains geometry and removes the old live readout
                        // from both the drawing and the accessibility tree.
                        measuredTopContents(session).hidden()
                    } else {
                        measuredTopContents(session)
                    }
                }
                .overlay {
                    if focused {
                        RunnerRestFocusSummary(snapshot: session.snapshot, showsGlyph: !hasIsland)
                            .transition(.opacity)
                    }
                }
            }
        }
        .animation(Motion.state(reduceMotion), value: focused)
    }

    private func measuredTopContents(_ session: RunnerSession) -> some View {
        VStack(spacing: 12) {
            if hasIsland {
                gripLineText(session)
            } else {
                gripLine(session)
            }
            prompt(session)
            hero(session)
            progress(session)
            counters(session)
        }
    }

    /// Pushed OUT to the screen edges and up a size (Nuri, 2026-08-09). They are the two
    /// numbers you check from a metre away between pulls, and at 12 pt inside the house
    /// 20 pt margin they were a caption. The negative padding cancels most of the
    /// content margin for this row only, so they frame the island rather than crowding it.
    private func counters(_ session: RunnerSession) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                CapsLabel(setLine(session), size: 14).fixedSize()
                Spacer(minLength: 0)
                restPhaseLabel(session)
                Spacer(minLength: 0)
                CapsLabel(pullLine(session), size: 14).fixedSize()
            }
            VStack(spacing: 4) {
                restPhaseLabel(session)
                HStack(alignment: .top, spacing: 8) {
                    CapsLabel(setLine(session), size: 14)
                    Spacer(minLength: 0)
                    CapsLabel(pullLine(session), size: 14).multilineTextAlignment(.trailing)
                }
            }
        }
        .monospacedDigit()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(spokenState(session)), \(countdownCaption(session) ?? "")")
        .accessibilityIdentifier("runner.counters")
    }

    /// Reserve the widest rest word in every phase, so rest-to-pull cannot move the
    /// graph. Compact widths and translated labels can use the two-row fallback.
    private func restPhaseLabel(_ session: RunnerSession) -> some View {
        Text("SET BREAK")
            .hidden().accessibilityHidden(true)
            .overlay {
                if let caption = countdownCaption(session) {
                    Text(caption).foregroundStyle(Ink.secondary)
                        .accessibilityIdentifier("runner.restPhase")
                }
            }
            .font(.system(.body, weight: .semibold))
            .fixedSize()
    }

    private func setLine(_ session: RunnerSession) -> String {
        guard let set = session.snapshot.setNumber else { return String(localized: "Session") }
        return String(localized: "Set \(set) of \(session.snapshot.setCount)")
    }

    /// Which pull you are ON, not how many you have completed.
    ///
    /// The count of RECORDED reps includes skipped ones, so a session skipped through
    /// read "34 of 36 pulls" — which says you did 34. Position through the plan is what
    /// this row is for, and it matches the "Set 6 of 6" beside it.
    private func pullLine(_ session: RunnerSession) -> String {
        let planned = session.snapshot.plannedRepCount
        let position = min(session.snapshot.completedRepCount + 1, planned)
        return String(localized: "Pull \(position) of \(planned)")
    }

    /// The grip, and — during a rest — the fact that it is the one COMING UP.
    ///
    /// The snapshot already looks forward while resting (`SessionRunner.displaySlot`), so
    /// this row silently changed meaning between phases. "Next" is what makes that legible
    /// instead of leaving you to work out which grip you are being shown.
    /// The grip, as a BIG CENTRED GLYPH over its own name.
    ///
    /// It used to be a 9 pt glyph inline at the head of a left-aligned text row, and on
    /// the wall that is the wrong size for the wrong thing (Nuri, 2026-08-08, from the
    /// board: *"increase the size of each finger position while you're actually doing
    /// this session, so you know exactly what you need to do"*). Which fingers go on the
    /// edge is the one instruction you act on with chalk on your hands; the words beside
    /// it are the confirmation, not the instruction. So the picture gets its own line,
    /// centred, at roughly double size, and the sentence sits under it.
    ///
    /// The height comes out of the force trace, which absorbs the slack (`maxHeight:
    /// .infinity`) — a graph is worth less than knowing which hand shape to make.
    /// The name row alone — used by the island experiment, where the picture has moved
    /// to the top of the screen and drawing it twice would be silly.
    @ViewBuilder
    private func gripLineText(_ session: RunnerSession, timerOnly: Bool = false) -> some View {
        if session.snapshot.grip != nil {
            nameRow(session, timerOnly: timerOnly).frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private func gripLine(_ session: RunnerSession, timerOnly: Bool = false) -> some View {
        if let grip = session.snapshot.grip {
            VStack(spacing: 6) {
                RunnerGripGlyph(grip: grip, emphasized: gripEmphasis)
                nameRow(session, timerOnly: timerOnly)
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(spokenGrip(session, grip: grip)
                                + (timerOnly ? String(localized: ", timing only") : ""))
        }
    }

    @ViewBuilder
    private func nameRow(_ session: RunnerSession, timerOnly: Bool = false) -> some View {
        if let grip = session.snapshot.grip {
            if timerOnly {
                VStack(spacing: 6) {
                    // The SAME badge as the measured layout, for the same two facts: the
                    // grip named here is the UPCOMING one while resting, and amber text
                    // inline could not pass contrast where a filled capsule can (see
                    // `restBadge`). One vocabulary for the change of tense, both modes.
                    //
                    // RESERVED, not conditional — the same trick the target chip below
                    // uses, and for the same measured reason: this block sits above the
                    // dial, and a badge that exists only during rest changes the line's
                    // height at every REST→WORK boundary, shifting the dial under the
                    // climber's eye. The widest form is laid out hidden for the whole
                    // session, so neither the phase nor Next↔New grip ever moves a pixel.
                    HStack(spacing: 8) {
                        ZStack {
                            badgeCapsule(String(localized: "New grip"), changing: true).hidden()
                            if session.snapshot.newGripID != nil {
                                badgeCapsule(String(localized: "New grip"), changing: true)
                            } else if isResting(session) {
                                restBadge(session)
                            }
                        }
                        Text(gripDisplayName(session, grip: grip))
                            .font(.system(.subheadline, weight: .medium))
                            .foregroundStyle(Ink.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    // `ViewThatFits`, not a plain `HStack`: three chips — and a band as
                    // long as "100.0–120.0 kg" is reachable — cannot share one line at
                    // accessibility sizes, and an HStack would squeeze the TEXT INSIDE
                    // each capsule rather than move a whole chip to the next row. Whole
                    // chips wrap or the capsules stop looking like capsules.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) { timerChips(session) }
                        VStack(spacing: 6) { timerChips(session) }
                    }
                    .frame(maxWidth: .infinity)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(spokenGrip(session, grip: grip) + String(localized: ", timing only"))
            } else {
                ZStack {
                    // Reserve the rest badge's height in every phase. Its padding
                    // otherwise moves the graph a few points when REST becomes PULL.
                    // The existing grip row is wider, so this adds no empty badge slot.
                    restBadge(session).hidden().accessibilityHidden(true)
                    HStack(spacing: 8) {
                        if isResting(session) {
                            restBadge(session)
                        }
                        Text(gripDisplayName(session, grip: grip))
                            .font(.system(.subheadline, weight: .medium))
                            .foregroundStyle(Ink.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                        if let band = session.snapshot.targetBand {
                            LiveTargetChip(band: band, isWorking: isWorking(session))
                        }
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(spokenGrip(session, grip: grip))
            }
        }
    }

    /// The rest screen's change of tense — and, when the pull ahead is on a different
    /// grip, the whole cue that it is (Nuri, 2026-08-19: a grip change between sets is
    /// easy to miss while you shake out). One badge, not a second mark beside it: the
    /// row already carries the grip's name, and this is the word qualifying it.
    ///
    /// Amber is the house colour for "waiting on you", which choosing a new grip during
    /// a rest literally is — alarm red stays reserved for attention. The WORD changes
    /// with the colour, so the cue survives greyscale and colourblindness on its own.
    ///
    /// A SOLID amber capsule with fixed dark ink, not amber TEXT: measured on the
    /// pinned sim (2026-08-19), `StatusTint.armed` glyphs on the light field came out
    /// 1.72:1 against a 4.5:1 floor — amber ink cannot carry small text on this
    /// background in either scheme. Filling the capsule flips the arithmetic (~7:1),
    /// and both colours are fixed literals, so the ratio cannot move with the scheme.
    private func restBadge(_ session: RunnerSession) -> some View {
        let changing = session.snapshot.gripChangesNext
        // The badge gets its OWN key rather than sharing the generic "Next" that the coach
        // card and the tour use for their forward buttons: this is a runner STATE word in
        // small caps beside a dial, and a language whose "next button" word is long (fr
        // "Suivant") needs the short state word ("Suite") here without lengthening two
        // buttons elsewhere. English is unchanged — `defaultValue` is what ships when a
        // catalog has no entry.
        return badgeCapsule(changing ? String(localized: "New grip")
                                     : String(localized: "runner.rest.badge.next", defaultValue: "Next"),
                            changing: changing)
    }

    /// The badge's drawing, split out so the timer layout can lay the widest form out
    /// hidden as a size reservation.
    private func badgeCapsule(_ text: String, changing: Bool) -> some View {
        CapsLabel(text, tint: changing ? Color(hex: "1B1F25") : Ink.tertiary)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(changing ? StatusTint.armed
                                                : Ink.tertiary.opacity(0.16)))
    }

    /// A gauge-free session is a legitimate timing protocol, so its mode belongs beside
    /// the target instruction rather than underneath a card as an apology. Keeping these
    /// chips on their own row also leaves the grip sentence readable at accessibility sizes.
    private var timingOnlyChip: some View {
        CapsLabel(String(localized: "Timing only"))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(Ink.tertiary.opacity(0.12)))
    }

    /// Advance notice shares the existing identity line; it never borrows graph height.
    private func gripDisplayName(_ session: RunnerSession, grip: GripSpec) -> String {
        if let next = session.snapshot.upcomingGrip {
            return "\(grip.shortName) → \(next.shortName)"
        }
        return isCrowded(session) ? grip.shortName : grip.line
    }

    /// Whether the grip row is carrying anything besides the grip itself.
    private func isCrowded(_ session: RunnerSession) -> Bool {
        session.snapshot.targetBand != nil || isResting(session) || session.snapshot.newGripID != nil
    }

    private func isResting(_ session: RunnerSession) -> Bool {
        switch session.snapshot.phase {
        case .resting: true
        case .paused(let inner): if case .resting = inner { true } else { false }
        default: false
        }
    }

    private func spokenGrip(_ session: RunnerSession, grip: GripSpec) -> String {
        // The amber badge is the sighted half of the grip-change cue; this is the other
        // half, and neither may be the only one.
        let resting = isResting(session)
        let changingGrip = session.snapshot.gripChangesNext
        guard let band = session.snapshot.targetBand else {
            if resting {
                return changingGrip
                    ? String(localized: "New grip next: \(grip.spoken)")
                    : String(localized: "Next: \(grip.spoken)")
            }
            return grip.spoken
        }
        let lo = weightText(band.lowerBound)
        let hi = weightText(band.upperBound)
        if resting {
            return changingGrip
                ? String(localized: "New grip next: \(grip.spoken), target \(lo) to \(hi) \(weightUnit.spokenName)")
                : String(localized: "Next: \(grip.spoken), target \(lo) to \(hi) \(weightUnit.spokenName)")
        }
        return String(localized: "\(grip.spoken), target \(lo) to \(hi) \(weightUnit.spokenName)")
    }

    private func isWorking(_ session: RunnerSession) -> Bool {
        if case .working = session.snapshot.phase { return true }
        return false
    }

    private func isArmed(_ session: RunnerSession) -> Bool {
        if case .armed = session.snapshot.phase { return true }
        return false
    }

    private func weightText(_ kg: Double) -> String {
        weightUnit.number(kg)
    }

    /// The one thing that has to be readable across a room: which hand, and whether to
    /// be pulling right now.
    private func prompt(_ session: RunnerSession) -> some View {
        ZStack {
            // Keep the original phase label's line height when a longer translated
            // hand instruction scales to fit. The graph must not move at REST→PULL
            // or REST→PAUSED just because one prompt needs smaller lettering.
            Text("REST").hidden().accessibilityHidden(true)
            Text(measuredPromptText(session))
        }
            .tourAnchor(.runnerHand)
            .font(.system(.largeTitle, weight: .heavy))
            .foregroundStyle(tint(session))
            .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
            .minimumScaleFactor(typeSize.isAccessibilitySize ? 1 : 0.6)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: typeSize.isAccessibilitySize)
            .frame(maxWidth: .infinity)
            // The one decision-critical word on this screen — PULL, RE-GRIP, EASE OFF,
            // LET GO, PAUSED — was hidden from VoiceOver with no substitute anywhere
            // else: `spokenState` speaks set/pull/hand/grip but never the phase, and the
            // cues cannot stand in for it either (`.dropoutWarning` fires the identical
            // tone for both RE-GRIP and EASE OFF, which CLAUDE.md itself resolves with
            // "only the screen has words"; pause/resume emit no cue at all). An explicit
            // label — matching what `timerDial`'s `spokenDialState` already does for the
            // gauge-free fallback — replaces the old `.accessibilityHidden(true)`.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(measuredPromptText(session))
            .accessibilityIdentifier("runner.prompt")
    }

    /// The next hand is the large instruction while resting; REST / SET BREAK sits
    /// beside the countdown instead. Both occupy existing space, so the graph stays
    /// exactly the same height when a hand changes or a rest begins.
    private func measuredPromptText(_ session: RunnerSession) -> String {
        if case .resting = session.snapshot.phase,
           let nextHand = session.snapshot.nextRestHandPrompt { return nextHand }
        return promptText(session)
    }

    private func promptText(_ session: RunnerSession) -> String {
        switch session.snapshot.phase {
        case .idle: device.state.isConnected ? String(localized: "GET READY") : String(localized: "CONNECTING")
        case .leadIn: String(localized: "GET READY")
        case .armed: String(localized: "\(session.snapshot.side?.prompt ?? "") — PULL")
        // The clock stopping without saying so looks like a bug, and the instinct it
        // provokes — pull harder — is the wrong one. Say what to do instead, and note
        // that with a target range there are now TWO ways to stall it: the reflex that
        // fixes one makes the other worse, so the word has to name which.
        case .working:
            if session.snapshot.isDropped { String(localized: "RE-GRIP") }
            else if session.snapshot.isOverTarget { String(localized: "EASE OFF") }
            else { session.snapshot.side?.prompt ?? "" }
        // The hold is banked and the rest has NOT started — say the one thing that
        // starts it. Silence here would read as a frozen clock, which is the same bug
        // "RE-GRIP" exists to prevent at the other end of the rep.
        case .releasing: String(localized: "LET GO")
        case .resting: session.snapshot.isSetBreak ? String(localized: "SET BREAK") : String(localized: "REST")
        case .paused: String(localized: "PAUSED")
        case .finished: String(localized: "DONE")
        }
    }


    /// Blue while the clock runs, amber while it waits on you, red when something needs
    /// attention, steel while resting. Read before any word is.
    private func tint(_ session: RunnerSession) -> Color {
        // A gauge-free session is never "disconnected": there is nothing to be connected
        // to, and painting REST in alarm red because of a device nobody asked for is the
        // app raising an alarm about its own choice.
        if !timerOnly, !device.state.isConnected || session.snapshot.linkIsDown {
            return StatusTint.alarm
        }
        switch session.snapshot.phase {
        case .working: return isStalled(session) ? StatusTint.armed : StatusTint.engaged
        // Amber, the app's "waiting on you" colour — which is exactly what this is.
        case .armed, .releasing, .paused: return StatusTint.armed
        case .resting, .leadIn, .idle, .finished: return StatusTint.calm
        }
    }

    /// BOTH numbers, always: what you are pulling and how much longer.
    ///
    /// They answer different questions and you need them at the same moment — the
    /// force tells you whether to pull harder or ease off, the clock tells you whether
    /// to hang on. An earlier build swapped one for the other and the load simply
    /// vanished for the ten seconds it mattered most.
    @ViewBuilder
    private func hero(_ session: RunnerSession) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            HStack(alignment: .lastTextBaseline, spacing: 18) {
                // No gauge, no kilogram. The clock takes the whole hero rather than sharing
                // it with a permanent 0.0 — an empty measurement reads as a fault.
                if !timerOnly {
                    LiveForceReadout(tint: forceTint(session), size: heroSize, unitSize: unitSize)
                }
                readout(value: "\(session.snapshot.secondsShown)",
                        unit: String(localized: "s"),
                        tint: isStalled(session) ? StatusTint.armed : Ink.primary,
                        rolls: true,
                        caption: nil)
                    .tourAnchor(.runnerClock)
            }
        }
        .frame(maxWidth: .infinity)
        // A numeral changing 80×/second is unusable under VoiceOver; the cue sounds and
        // the counters row are the accessible channel.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(countdownCaption(session) ?? "")
        .accessibilityIdentifier("runner.hero")
        .accessibilityHidden(showsRestFocus(session) || countdownCaption(session) == nil)
    }

    private func countdownCaption(_ session: RunnerSession) -> String? {
        guard session.snapshot.nextRestHand != nil else { return nil }
        if session.snapshot.phase.isPaused { return nil }
        return session.snapshot.isSetBreak ? String(localized: "SET BREAK") : String(localized: "REST")
    }

    /// `rolls` is the difference between a CLOCK and a MEASUREMENT.
    ///
    /// A countdown rolling digit-by-digit looks right — it is counting, and the motion
    /// says so. The force readout is not counting, it is REPORTING, and at ~10 updates a
    /// second the same animation turns the one number you are trying to read mid-pull
    /// into a permanent blur. It snaps.
    private func readout(value: String, unit: String, tint: Color,
                         rolls: Bool, caption: String? = nil) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 4) {
            ZStack(alignment: Alignment(horizontal: .center, vertical: .lastTextBaseline)) {
                // Reserve the numeral's original line height even when horizontal
                // pressure scales it down. A translated next-hand caption must neither
                // split 20 into 2/0 nor move the graph when pausing a rest.
                Text("0").hidden().accessibilityHidden(true)
                Text(value)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .contentTransition(rolls ? .numericText() : .identity)
                    .foregroundStyle(tint)
            }
            .font(.system(size: heroSize, weight: .thin))
            .displayTracking(heroSize)
            .monospacedDigit()
            VStack(alignment: .leading, spacing: 2) {
                if let caption {
                    Text(caption)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Ink.secondary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: 92, alignment: .leading)
                        // Ask for the caption's ideal width within that cap: a short
                        // REST label must not reserve an invisible 92-point column.
                        .fixedSize(horizontal: true, vertical: true)
                }
                Text(unit)
                    .font(.system(size: unitSize))
                    .foregroundStyle(Ink.tertiary)
            }
        }
        .animation(rolls ? Motion.live : nil, value: value)
    }


    /// The force number carries the "are you actually on it" signal: blue while the load
    /// is in range and the clock is banking, amber the moment it leaves — either end.
    private func forceTint(_ session: RunnerSession) -> Color {
        guard case .working = session.snapshot.phase else { return Ink.primary }
        return isStalled(session) ? StatusTint.armed : StatusTint.engaged
    }

    /// The rep is alive but its clock is not running — off the edge, or over the top of
    /// the target range. One question, because the screen answers it the same way.
    private func isStalled(_ session: RunnerSession) -> Bool {
        session.snapshot.isDropped || session.snapshot.isOverTarget
    }

    /// One definition, laid out twice by `ViewThatFits` — the chips must be identical in
    /// the one-row and stacked forms or the layout would change content as it wraps.
    /// **A FIXED set of chips, and that is what stops the dial moving.**
    ///
    /// `Next` used to be a chip here, appearing only during rest — which meant the row
    /// could hold three chips resting and two working. At REST→WORK that can flip
    /// `ViewThatFits` from the stacked candidate back to the single row, or simply drop a
    /// row; either way the identity block changes height and the dial below it shifts
    /// underneath the climber's eye, every single rep. `Next` now lives on the grip line
    /// as the rest badge, RESERVED at its widest form for the whole session (see
    /// `nameRow`'s timer branch) — same constant-height property, achieved the same way
    /// as the target chip below.
    /// **The chip COUNT is fixed for the whole session, not for the current slot.**
    ///
    /// Moving `Next` out was only half the fix: `targetBand` is per-slot, so a routine
    /// carrying a target on some sets and not others still changed the chip count at a
    /// phase boundary — and at accessibility sizes that can flip `ViewThatFits` between
    /// its one-row and stacked candidates, changing the identity block's height and
    /// shifting the dial under the climber's eye mid-session.
    ///
    /// So the slot is reserved for the SESSION: if any set in the plan has a target, the
    /// chip is always laid out and merely invisible where this slot has none. A plan with
    /// no targets anywhere reserves nothing, so it pays no empty space — in both cases
    /// the count is constant, which is the property that matters.
    @ViewBuilder
    private func timerChips(_ session: RunnerSession) -> some View {
        let bands = sessionTargetBands(session)
        if !bands.isEmpty {
            // **Every slot's chip occupies the SAME width — the widest this session can
            // produce — whatever band it is currently showing.**
            //
            // A constant chip COUNT was not enough, and neither was a wide placeholder for
            // the empty case: slots carrying different bands render different-width
            // labels, so `5.0–10.0 kg` and `100.0–120.0 kg` could still pick different
            // `ViewThatFits` candidates and change the block's height mid-session, moving
            // the dial under the climber's eye.
            //
            // Laying every possible band out HIDDEN inside the ZStack settles it without
            // guessing which label is longest — picking by `upperBound` gets that wrong
            // ("0.0–120.0 kg" is wider-valued but narrower than "100.0–110.0 kg"). The
            // stack simply takes the largest, and the real chip draws on top of it.
            ZStack {
                ForEach(Array(bands.enumerated()), id: \.offset) { _, band in
                    LiveTargetChip(band: band, isWorking: false, timerOnly: true)
                        .hidden()
                }
                LiveTargetChip(band: session.snapshot.targetBand ?? bands[0],
                               isWorking: isWorking(session), timerOnly: true)
                    .opacity(session.snapshot.targetBand == nil ? 0 : 1)
                    .accessibilityHidden(session.snapshot.targetBand == nil)
            }
        }
        timingOnlyChip
    }

    /// Every distinct band this session can show, from the RUNNER'S RESOLVED SLOTS.
    ///
    /// Resolved, not `plan.sets`: the raw plan misses the plan-level percent band, which
    /// the engine resolves onto sets carrying no target of their own and which stored
    /// templates are not normalised for on read. A plan-level check would have reported
    /// "no targets" for a session that shows one, deleting the chip outright. These are
    /// the same slots the snapshot publishes, so the reservation cannot disagree with
    /// what is drawn.
    private func sessionTargetBands(_ session: RunnerSession) -> [ClosedRange<Double>] {
        var seen: Set<String> = []
        return session.runner.slots.compactMap(\.targetBand).filter {
            seen.insert("\($0.lowerBound)-\($0.upperBound)").inserted
        }
    }

    @ViewBuilder
    private func timerOnlyIdentity(_ session: RunnerSession) -> some View {
        // The island already owns the glyph on supported phones. Drawing it again in the
        // identity block would make the hand read as two instructions, not one.
        if hasIsland {
            gripLineText(session, timerOnly: true)
        } else {
            gripLine(session, timerOnly: true)
        }
    }

    /// One centred position line keeps whole-session progress in words; a second ring
    /// would bring back the duplication this mode is designed to remove.
    private func timerPositionLine(_ session: RunnerSession) -> some View {
        CapsLabel(String(localized: "\(setLine(session)) · \(pullLine(session))"))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
            .monospacedDigit()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(setLine(session)), \(pullLine(session))")
    }

    /// The gauge-free hero: the countdown numeral and the phase's remaining time are one
    /// object, because proximity is the mapping that makes a timer readable at a glance.
    ///
    /// The ring depletes per PHASE, not per rep: `repProgress` is hold-only and is zero
    /// through all of lead-in and rest, which made the old ring empty exactly when the
    /// timer-only user needed it most. The ring's fraction uses the same countdown
    /// clock as the numeral, so the two channels cannot drift.
    private func timerDial(_ session: RunnerSession) -> some View {
        let lineWidth: CGFloat = isTimerWorking(session) ? 12 : 7

        return ZStack {
            LiveTimerRing(session: session, lineWidth: lineWidth, tint: tint(session))
            VStack(spacing: 2) {
                Text("\(session.snapshot.secondsShown)")
                    .font(.system(size: heroSize, weight: .thin))
                    .displayTracking(heroSize)
                    .monospacedDigit()
                    .minimumScaleFactor(0.55)
                    .lineLimit(1)
                    .contentTransition(.numericText())
                    // `.numericText()` needs an animation OBSERVING the value or it does
                    // nothing — the digits just cut. The outer animation watches `phase`,
                    // which changes once per phase, not once per second, so without this
                    // the hero numeral of a timer snapped instead of rolling.
                    .animation(Motion.live, value: session.snapshot.secondsShown)
                    .foregroundStyle(Ink.primary)
                CapsLabel(promptText(session), tint: tint(session))
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
                if let nextHand = session.snapshot.nextRestHandPrompt {
                    Text(nextHand)
                        .font(.headline.weight(.semibold))
                        .foregroundStyle(Ink.secondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.75)
                        .padding(.horizontal, 24)
                }
            }
        }
        // **A PREFERRED size, not a fixed one.** `dialDiameter` is `@ScaledMetric`, so at
        // accessibility sizes 240 becomes ~600 — half again wider than the screen — and a
        // fixed frame drew a ring clipped off both edges while shoving the identity block
        // up under the Dynamic Island's fingers. `aspectRatio(.fit)` keeps it circular
        // inside whatever it is actually offered, so the dial shrinks to make room rather
        // than overflowing. Measured at the largest accessibility size on the pinned sim.
        .frame(maxWidth: dialDiameter, maxHeight: dialDiameter)
        .aspectRatio(1, contentMode: .fit)
        // **THE COLUMN MUST STILL FILL THE SCREEN.** In the measured layout the trace card
        // carries `maxHeight: .infinity`, and that is what made the whole VStack tall.
        // Without an equivalent here the timer column hugged its content and got centred,
        // which broke two things at once: a dead band above the identity block, and the
        // Dynamic Island hand landing on top of the grip line — `.islandHand` is an
        // `overlay(alignment: .top)` on the root, so it pins to the top of the CONTENT,
        // and the content had walked down the screen. The dial is the hero, so it is the
        // element that takes the slack.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(Motion.state(reduceMotion), value: session.snapshot.phase)
        .tourAnchor(.runnerClock)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenDialState(session))
    }

    private func isTimerWorking(_ session: RunnerSession) -> Bool {
        switch session.snapshot.phase {
        case .working: true
        case .paused(let inner):
            if case .working = inner { true } else { false }
        default: false
        }
    }

    private func spokenDialState(_ session: RunnerSession) -> String {
        let countdown = String(localized: "\(promptText(session)), \(session.snapshot.secondsShown) seconds remaining")
        guard let nextHand = session.snapshot.nextRestHandPrompt else { return countdown }
        return "\(countdown), \(nextHand)"
    }

    private func noSignalNotice(_ session: RunnerSession) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(.title, weight: .light))
                .foregroundStyle(Ink.tertiary)
                .accessibilityHidden(true)
            Text(device.state.isConnected ? "Waiting for the gauge" : "Gauge not connected")
                .font(.system(.headline, weight: .semibold))
                .foregroundStyle(Ink.secondary)
            Text(device.state.isConnected
                 ? String(localized: "Connected, but no readings yet. Tap Wake to restart it.")
                 : connectHint)
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
        .accessibilityElement(children: .combine)
    }

    /// **Names the gauge that is actually selected, and does not promise a pairing that
    /// does not exist.** A broadcast scale is never paired with — the app listens for its
    /// advertisements — so telling somebody to pair with a Progressor they do not own is
    /// wrong twice over.
    private var connectHint: String {
        if device.canCancelBroadcastSearch { return device.state.label }
        return device.gaugeCapabilities.isBroadcast
            ? String(localized: "Tap Connect to start listening for your \(device.gaugeKind.displayName).")
            : String(localized: "Tap Connect to pair with your \(device.gaugeKind.displayName).")
    }

    @ViewBuilder
    private func progress(_ session: RunnerSession) -> some View {
        if case .working = session.snapshot.phase {
            // `LiveRepProgress`, not a `ProgressView` reading `session.snapshot` inline
            // — see its own doc comment for why the value has to be read one level down.
            LiveRepProgress(session: session)
                .id(session.snapshot.phase.slotIndex)
        } else {
            // Reserve the row so the layout doesn't jump every time a rep starts.
            Color.clear.frame(height: 4)
        }
    }

    // MARK: - Controls

    private func controls(_ session: RunnerSession) -> some View {
        let phase = session.snapshot.phase
        let pauseEnabled = RunnerControlPolicy.pauseEnabled(for: phase)
        let pauseReason = RunnerControlPolicy.pauseDisabledReason(for: phase)
        let skipEnabled = RunnerControlPolicy.skipEnabled(for: phase)
        let skipReason = RunnerControlPolicy.skipDisabledReason(for: phase)

        return VStack(spacing: 10) {
            AdaptiveActionRow(spacing: 10) {
                // The buttons KEEP their identity while disabled: the visible reason
                // the house rule demands is the prompt above them, which says PAUSED /
                // CONNECTING at large-title weight — swapping the labels spent the two
                // Skips' names on the same repeated word, and VoiceOver read "Paused,
                // dimmed. Paused." twice with no way to tell them apart. The full
                // sentence rides the hint instead.
                wideButton(phase.isPaused ? String(localized: "Resume") : String(localized: "Pause"),
                           systemImage: phase.isPaused ? "play.fill" : "pause.fill",
                           enabled: pauseEnabled, disabledReason: pauseReason) {
                    session.send(phase.isPaused ? .resume : .pause)
                }
                .accessibilityIdentifier("runner.pause")
                // Neither Tare nor Connect belongs here without a gauge: one has nothing
                // to zero and the other would offer to change the session you are in.
                if timerOnly {
                    EmptyView()
                } else if device.state.isConnected {
                    TareButton(session: session)
                        .accessibilityIdentifier("runner.tare")
                } else if device.canCancelBroadcastSearch {
                    wideButton(String(localized: "Cancel"), systemImage: "xmark") {
                        device.disconnect()
                    }
                    .accessibilityLabel(String(localized: "Cancel"))
                    .accessibilityValue(device.state.label)
                    .accessibilityIdentifier("gauge.connectionAction")
                } else {
                    wideButton(String(localized: "Connect"), systemImage: "dot.radiowaves.left.and.right") {
                        device.connect()
                    }
                }
            }
            AdaptiveActionRow(spacing: 10) {
                wideButton(String(localized: "Skip pull"), enabled: skipEnabled,
                           disabledReason: skipReason) { session.send(.skipRep) }
                    .accessibilityIdentifier("runner.skipPull")
                wideButton(String(localized: "Skip set"), enabled: skipEnabled,
                           disabledReason: skipReason) { session.send(.skipSet) }
                    .accessibilityIdentifier("runner.skipSet")
                HoldToEndButton(allowsScrolling: typeSize.isAccessibilitySize) { session.send(.abort) }
                    .accessibilityIdentifier("runner.end")
            }
        }
    }

    /// Flexible-width glass button. `SecondaryGlassButton` hugs its label, which is
    /// right on a sheet and wrong here — three hugging buttons in one row truncated
    /// "Pause" to "Pa…" on the pinned sim. Glass INSIDE the label, then the content
    /// shape, then the style outside.
    ///
    /// `enabled`/`disabledReason` dim AND disable, with the reason surfaced as the
    /// accessibility hint. The label is never swapped: sighted use reads the reason
    /// off the screen's own PAUSED / CONNECTING prompt, and the hint carries the full
    /// sentence for VoiceOver.
    private func wideButton(_ title: String, systemImage: String? = nil,
                            tint: Color = Ink.primary,
                            enabled: Bool = true, disabledReason: String? = nil,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(.subheadline, weight: .semibold))
            .foregroundStyle(enabled ? tint : Ink.tertiary.opacity(0.5))
            .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
            .accessibleGlass(nil, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .disabled(!enabled)
        .accessibilityHint(disabledReason ?? "")
    }

    private func spokenState(_ session: RunnerSession) -> String {
        guard let grip = session.snapshot.grip, let set = session.snapshot.setNumber else {
            return String(localized: "Session finished")
        }
        return String(localized: """
            Set \(set) of \(session.snapshot.setCount), \
            pull \(session.snapshot.completedRepCount + 1) of \(session.snapshot.plannedRepCount), \
            \(session.snapshot.side?.name ?? "") hand, \(grip.spoken)
            """)
    }
}

private struct GripCueKey: Equatable {
    var id: String?
    var resting: Bool
}

// MARK: - The things that change 80× a second

/// The live kilogram readout, isolated in its OWN view.
///
/// `DeviceStore.currentKg` changes with every force sample. Read from `RunnerView`'s
/// body, that rebuilt the entire screen — counters, prompt, grip line, controls — 80
/// times a second to move one number. A leaf view reading the store directly means the
/// invalidation stops here, at the only thing that actually changed.
private struct LiveForceReadout: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    var tint: Color
    var size: CGFloat
    var unitSize: CGFloat

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(weightUnit.number(device.currentKg))
                .font(.system(size: size, weight: .thin))
                    .displayTracking(size)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                // A measurement snaps; only clocks roll.
                .contentTransition(.identity)
                .foregroundStyle(tint)
            Text(weightUnit.symbol)
                .font(.system(size: unitSize))
                .foregroundStyle(Ink.tertiary)
        }
        .accessibilityHidden(true)
    }
}

/// The timer ring updates at 10 Hz; the surrounding numeral, prompts and controls
/// only observe the whole-second snapshot. Its animation never drives app state.
private struct LiveTimerRing: View {
    var session: RunnerSession
    var lineWidth: CGFloat
    var tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let fraction = session.phaseRemainingFraction ?? 0
        ZStack {
            Circle().stroke(Ink.tertiary.opacity(0.18), lineWidth: lineWidth)
            if fraction > 0 {
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(reduceMotion ? nil : Motion.live, value: fraction)
            }
        }
    }
}

/// The exact measured fraction belongs to this leaf alone. Linear settling fills the
/// frames between BLE packets without forecasting credited work or easing to a stop
/// per packet. The caller keys this view to the working phase so a skipped/next pull
/// starts cleanly instead of draining the previous pull's bar backwards.
private struct LiveRepProgress: View {
    var session: RunnerSession
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ProgressView(value: session.repProgress)
            .tint(StatusTint.engaged)
            .animation(reduceMotion ? nil : Motion.measuredProgress, value: session.repProgress)
            .accessibilityHidden(true)
    }
}

/// The target's live state changes with every force sample, so the chip owns that
/// high-frequency observation instead of invalidating the runner screen around it.
private struct LiveTargetChip: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    var band: ClosedRange<Double>
    var isWorking: Bool
    var timerOnly = false

    var body: some View {
        // In a timer-only session the gauge value is zero or stale by definition. Letting
        // it light this instruction chip would claim that an unmeasured pull is engaged.
        let live = !timerOnly && isWorking && band.contains(device.currentKg)
        Text(String(localized: "\(weightText(band.lowerBound))–\(weightText(band.upperBound)) \(weightUnit.symbol)"))
            .font(.system(.footnote, weight: .semibold))
            .monospacedDigit()
            .foregroundStyle(live ? Color.white : Ink.secondary)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background {
                Capsule()
                    .fill(live ? StatusTint.engaged : Color.clear)
                    .overlay(Capsule().stroke(Ink.tertiary.opacity(live ? 0 : 0.6), lineWidth: 1))
            }
            .animation(Motion.live, value: live)
            .accessibilityHidden(true)
    }

    private func weightText(_ kg: Double) -> String {
        weightUnit.number(kg)
    }
}

/// Same reasoning for the trace: `DeviceStore.trace` grows with every sample, so the
/// dependency belongs to the graph alone.
private struct LiveTrace: View {
    @Environment(DeviceStore.self) private var device
    var thresholdKg: Double?
    var targetBand: ClosedRange<Double>?
    var tint: Color
    var body: some View {
        ForceTraceView(samples: device.trace,
                       thresholdKg: thresholdKg, targetBand: targetBand, tint: tint,
                       nominalSampleRate: device.gaugeCapabilities.nominalSampleRate,
                       bridgesSparseDelivery: device.gaugeCapabilities.isBroadcast,
                       diagnostics: device.pipelineDiagnostics)
    }
}

/// Loaded taring is useful for a static sling or mounted block, so the control confirms
/// instead of silently refusing a meaningful reading. The phase guard — not the load —
/// keeps taring out of a live rep; confirmation is the warning that prevents an allowed
/// phase from zeroing a load the climber did not mean to discard.
private struct TareButton: View {
    @Environment(\.weightUnit) private var weightUnit
    @Environment(DeviceStore.self) private var device
    var session: RunnerSession

    @State private var promptedKg = 0.0
    @State private var promptedConnectionEpoch: UInt64 = 0
    @State private var showingConfirmation = false

    var body: some View {
        let enabled = canTareNow
        Button {
            // Re-check on touch-up against the live stores. A pull that starts after an
            // unloaded touch-down must not slip through an enabled frame and zero load.
            guard device.state.isConnected else { return }
            switch tapDecision {
            case .blocked:
                return
            case .wakeStream:
                // Not a tare, and `wakeStream()` cannot become one. See
                // `TareTapDecision.wakeStream`: with no live samples the load is unknown,
                // and the frozen reading says 0 kg however loaded the gauge actually is.
                session.wakeStream()
            case .confirm, .tare:
                // The rendered decision said the reading was live; confirm that against
                // the exact clock before doing anything irreversible. See
                // `TarePolicy.isSafeToTareNow` — this can only downgrade to a wake.
                // Same bound the button's own mode was drawn from
                // (`device.tareReadingMaxAge`), so the tap can never disagree with what
                // it was shown.
                guard TarePolicy.isSafeToTareNow(
                    sampleAge: device.secondsSinceLastSample(),
                    maxAgeSeconds: device.tareReadingMaxAge) else {
                    session.wakeStream()
                    return
                }
                if TarePolicy.shouldConfirm(readingKg: device.currentKg) {
                    promptTare()
                } else {
                    session.tare()
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: tapDecision == .wakeStream
                      ? "arrow.clockwise" : "arrow.counterclockwise")
                // The label says what the tap will actually DO — "Wake" when the stream
                // is dead, and the phase's reason while disabled (see
                // `TarePolicy.disabledLabel`). A button reading "Tare" that restarts the
                // stream instead would be lying about itself.
                Text(tapDecision == .wakeStream
                     ? String(localized: "Wake")
                     : (TarePolicy.disabledLabel(for: session.snapshot.phase) ?? String(localized: "Tare")))
            }
            .font(.system(.subheadline, weight: .semibold))
            .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
            .accessibleGlass(nil, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
        .disabled(!enabled)
        .foregroundStyle(enabled ? Ink.primary : Ink.tertiary.opacity(0.5))
        .accessibilityHint(tareDisabledReason
                           ?? (tapDecision == .wakeStream
                               ? String(localized: "Restart the reading. The gauge is connected but not sending.")
                               : String(localized: "Zero the gauge.")))
        .alert("Zero the gauge?", isPresented: $showingConfirmation) {
            Button("Zero it", role: .destructive) { confirmTare() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(String(localized: "There's \(weightUnit.number(promptedKg)) \(weightUnit.symbol) on the gauge. Zero it?"))
        }
    }

    private var tapDecision: TareTapDecision {
        // `device.isLoadedForTare`, not `device.currentKg` — the coarse, change-guarded
        // flag, so this computed property (read three times in `body`) does not register
        // a dependency on a value moving at sample rate. See its doc comment on
        // `DeviceStore` and `TarePolicy.tapDecision`.
        TarePolicy.tapDecision(phase: session.snapshot.phase,
                               isReadingLive: device.isReadingLive,
                               isLoadedForTare: device.isLoadedForTare)
    }

    /// Enabled for the WAKE even in a phase that forbids taring — waking never zeroes
    /// anything, and a dead stream mid-pull is when you most need it back.
    private var canTareNow: Bool { tapDecision != .blocked }

    private var tareDisabledReason: String? {
        tapDecision == .blocked
            ? TarePolicy.disabledReason(for: session.snapshot.phase)
            : nil
    }

    private func promptTare() {
        promptedKg = device.currentKg
        promptedConnectionEpoch = device.connectionEpoch
        showingConfirmation = true
    }

    private func confirmTare() {
        switch TarePolicy.confirmationDecision(
            promptedKg: promptedKg,
            currentKg: device.currentKg,
            promptedEpoch: promptedConnectionEpoch,
            currentEpoch: device.connectionEpoch,
            isConnected: device.state.isConnected,
            sampleAge: device.secondsSinceLastSample(),
            phase: session.snapshot.phase,
            maxAgeSeconds: device.tareReadingMaxAge
        ) {
        case .reject:
            // Deliberately silent. A reject means the phase moved into a pull, the link
            // changed, or the gauge went away — and in every one of those cases the
            // screen behind the alert has already changed to say so, including the Tare
            // button's own label. A second alert explaining why the first one did
            // nothing would be noise stacked on noise.
            return
        case .reask:
            // The quoted number is no longer safe to authorize. Re-arm with the fresh
            // SIGNED reading on the next runloop pass: this button is inside the alert
            // that is dismissing right now, and setting `showingConfirmation` back to
            // true synchronously is swallowed by that dismissal.
            promptedKg = device.currentKg
            promptedConnectionEpoch = device.connectionEpoch
            Task { @MainActor in
                await Task.yield()
                showingConfirmation = true
            }
        case .tare:
            session.tare()
        }
    }
}

// MARK: - Hold to end

/// Ending a session takes a deliberate HOLD, not a tap plus a dialog.
///
/// A confirmation sheet mid-workout is two taps with chalk on your hands, and the second
/// one is the reflex you learn to fire without reading. A hold carries the same "are you
/// sure" in the gesture itself: the button fills while you mean it, and letting go early
/// costs nothing. Nothing is destroyed either way — everything already done is kept.
private struct HoldToEndButton: View {
    var allowsScrolling = false
    var action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress: Double = 0
    /// SEPARATE from `progress`, and that is the entire fix for the overlap.
    ///
    /// The label used to read `progress > 0`, so it changed INSIDE the 0.9 s
    /// `withAnimation` that drives the fill — and SwiftUI cross-fades a Text whose
    /// content changes under an animation. Two strings of different widths, both
    /// half-opaque, sat on top of each other for the whole hold. Flipping a plain Bool
    /// outside the transaction swaps the label instantly instead.
    @State private var isHolding = false
    @State private var holdTask: Task<Void, Never>?
    @State private var firedTick = 0
    @State private var slidOff = false
    @State private var hitFrame: CGRect = .zero

    /// Long enough to be deliberate, short enough not to feel like a punishment.
    private static let holdSeconds: Double = 0.9
    private static let slideSlop: CGFloat = 24

    var body: some View {
        ZStack {
            // Reserve both titles so beginning a hold cannot reflow the action row.
            Text("Keep holding…").hidden().accessibilityHidden(true)
            Text("Hold to end").hidden().accessibilityHidden(true)
            Text(isHolding ? "Keep holding…" : "Hold to end")
                .foregroundStyle(Accent.alarm)
                .contentTransition(.identity)
                .animation(nil, value: isHolding)
        }
        .font(.system(.subheadline, weight: .semibold))
        .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
        .background {
            GeometryReader { geo in
                ZStack {
                    Capsule().fill(Accent.alarm.opacity(0.16))
                    Capsule()
                        .fill(Accent.alarm.opacity(0.42))
                        .mask(alignment: .leading) {
                            Rectangle()
                                .frame(width: geo.size.width * progress)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                }
            }
        }
        .contentShape(.capsule)
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { hitFrame = $0 }
        .simultaneousGesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged {
                    if allowsScrolling,
                       abs($0.translation.width) > 10 || abs($0.translation.height) > 10 {
                        slidOff = true
                        cancelHold()
                    } else {
                        let localPoint = CGPoint(x: $0.location.x - hitFrame.minX,
                                                 y: $0.location.y - hitFrame.minY)
                        updateHold(at: localPoint, in: hitFrame.size)
                    }
                }
                .onEnded { _ in endHold() }
        )
        .onDisappear { endHold() }
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.9), trigger: firedTick)
        .accessibilityElement()
        .accessibilityLabel("End session")
        .accessibilityHint("Press and hold to end. Everything you've already done is kept.")
        .accessibilityAddTraits(.isButton)
        // VoiceOver cannot express a hold, so an activation ends it outright — the
        // gesture is the safeguard for a thumb, not a substitute for the action.
        .accessibilityAction { action() }
    }

    private func updateHold(at location: CGPoint, in size: CGSize) {
        guard !slidOff else { return }
        let bounds = CGRect(origin: .zero, size: size)
            .insetBy(dx: -Self.slideSlop, dy: -Self.slideSlop)
        guard bounds.contains(location) else {
            slidOff = true
            cancelHold()
            return
        }
        beginHold()
    }

    private func endHold() {
        cancelHold()
        slidOff = false
    }

    private func beginHold() {
        guard holdTask == nil else { return }
        // OUTSIDE the animation, deliberately — see `isHolding`.
        isHolding = true
        // UNCONDITIONAL — deliberately not gated on `reduceMotion`, and that is a
        // decision rather than an oversight (audit rank 28). This fill is the
        // functional progress readout for a 0.9 s hold-to-confirm gesture — how much
        // longer to keep holding — not decorative motion; snapping straight to a full
        // bar under Reduce Motion would remove the one signal that the hold is
        // registering at all, while the gesture itself still takes exactly 0.9 s
        // either way. It also has to match `holdTask`'s real sleep, which no token
        // can express.
        withAnimation(.linear(duration: Self.holdSeconds)) { progress = 1 }
        holdTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.holdSeconds))
            guard !Task.isCancelled else { return }
            firedTick += 1
            action()
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holdTask = nil
        isHolding = false
        // `Motion.state(reduceMotion)` already resolves to `Motion.reduced` when the
        // flag is set — the ternary was redundant and produced a second, divergent,
        // untokenised reduced-motion curve.
        withAnimation(Motion.state(reduceMotion)) { progress = 0 }
    }
}
