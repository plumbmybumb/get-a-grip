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
    let template: SessionTemplate
    /// Run the whole thing on the clock, with no gauge — see `SessionRunner.timerOnly`.
    /// Everything force-shaped leaves the screen rather than sitting there at 0.0 kg,
    /// which would read as a broken gauge instead of an absent one.
    var timerOnly: Bool = false

    @Environment(DeviceStore.self) private var device
    @Environment(TourController.self) private var tour
    @Environment(TemplateStore.self) private var templates
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
                    enabled: hasIsland, emphasized: gripEmphasis)
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
            // The maxes are read ONCE, here — a session's targets must not move under
            // the climber because a max was recorded on another device mid-workout.
            let new = RunnerSession(template: template, device: device,
                                    maxes: templates.maxTable, timerOnly: timerOnly)
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
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                // Bin whatever the radio buffered while we were away — see
                // `DeviceStore.dropStaleTrace`.
                device.dropStaleTrace()
                // **AND KICK THE STREAM.** This is the one that actually mattered
                // (Nuri, 2026-08-10: "when you first come back you get a little dot, then
                // after a while the stream continues"). The gauge stops sending while the
                // app is suspended, and the only thing that revived it was the stream
                // watchdog — which sleeps 1.5 s between checks and then wants 1.6 s of
                // silence, so up to about three seconds of nothing. The dot was the one or
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

    private func live(_ session: RunnerSession) -> some View {
        VStack(spacing: 12) {
            if timerOnly {
                timerOnlyIdentity(session)
                timerDial(session)
                timerPositionLine(session)
            } else {
                // WITH AN ISLAND the grip hangs off it, so the in-content glyph would be the
                // same picture twice — and the counters move DOWN to sit above the graph,
                // which hands the whole top band to the hand (Nuri, 2026-08-09). They read
                // just as well there: they are the two numbers you check between pulls, not
                // while pulling. Without one (a notch, an SE, the simulator's older devices)
                // the original layout stands — the glyph is the instruction, and it cannot
                // simply go missing because the hardware has no cutout to hang it from.
                if hasIsland {
                    gripLineText(session)
                } else {
                    counters(session)
                    gripLine(session)
                }
                prompt(session)
                hero(session)
                progress(session)
                if hasIsland { counters(session) }
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
                    if !session.snapshot.hasSignal {
                        noSignalNotice(session)
                    }
                }
                .frame(maxHeight: .infinity)
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
                    if gripEmphasis, session.snapshot.hasSignal,
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
                .tourAnchor(.runnerTrace)
            }
            controls(session)
        }
        .padding(.horizontal, Metrics.hPadding)
        // The fingers reach ~92 pt down the screen and content starts at 59, so the hand
        // needs the gap bought for it — otherwise the grip name lands under the knuckles.
        .padding(.top, hasIsland ? 46 : 8)
        .padding(.bottom, Metrics.spacing)
        .frame(maxWidth: Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    /// Pushed OUT to the screen edges and up a size (Nuri, 2026-08-09). They are the two
    /// numbers you check from a metre away between pulls, and at 12 pt inside the house
    /// 20 pt margin they were a caption. The negative padding cancels most of the
    /// content margin for this row only, so they frame the island rather than crowding it.
    private func counters(_ session: RunnerSession) -> some View {
        HStack {
            CapsLabel(setLine(session), size: 14)
            Spacer(minLength: 8)
            CapsLabel(pullLine(session), size: 14)
        }
        .padding(.horizontal, -10)
        .monospacedDigit()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(spokenState(session))
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
                FingerGlyph(fingers: grip.fingers, position: grip.position,
                            dot: 18, gap: 7,
                            tint: gripEmphasis ? StatusTint.armed : Accent.graphite)
                    .scaleEffect(gripEmphasis && !reduceMotion ? 1.25 : 1)
                    .frame(height: 44)
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
                HStack(spacing: 8) {
                    if isResting(session) {
                        restBadge(session)
                    }
                    // The full name now fits: the glyph no longer shares this row, so
                    // the old truncation to "…half cri…" that forced the short form only
                    // has to be avoided when a target chip is also present.
                    Text(gripDisplayName(session, grip: grip))
                        .font(.system(.subheadline, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                        .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    if let band = session.snapshot.targetBand {
                        LiveTargetChip(band: band, isWorking: isWorking(session))
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
        let lo = kgText(band.lowerBound)
        let hi = kgText(band.upperBound)
        if resting {
            return changingGrip
                ? String(localized: "New grip next: \(grip.spoken), target \(lo) to \(hi) kilograms")
                : String(localized: "Next: \(grip.spoken), target \(lo) to \(hi) kilograms")
        }
        return String(localized: "\(grip.spoken), target \(lo) to \(hi) kilograms")
    }

    private func isWorking(_ session: RunnerSession) -> Bool {
        if case .working = session.snapshot.phase { return true }
        return false
    }

    private func isArmed(_ session: RunnerSession) -> Bool {
        if case .armed = session.snapshot.phase { return true }
        return false
    }

    private func kgText(_ kg: Double) -> String {
        kg.formatted(.number.precision(.fractionLength(1)))
    }

    /// The one thing that has to be readable across a room: which hand, and whether to
    /// be pulling right now.
    private func prompt(_ session: RunnerSession) -> some View {
        Text(promptText(session))
            .tourAnchor(.runnerHand)
            .font(.system(.largeTitle, weight: .heavy))
            .foregroundStyle(tint(session))
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .frame(maxWidth: .infinity)
            // The one decision-critical word on this screen — PULL, RE-GRIP, EASE OFF,
            // LET GO, PAUSED — was hidden from VoiceOver with no substitute anywhere
            // else: `spokenState` speaks set/pull/hand/grip but never the phase, and the
            // cues cannot stand in for it either (`.dropoutWarning` fires the identical
            // tone for both RE-GRIP and EASE OFF, which CLAUDE.md itself resolves with
            // "only the screen has words"; pause/resume emit no cue at all). An explicit
            // label — matching what `timerDial`'s `spokenDialState` already does for the
            // gauge-free fallback — replaces the old `.accessibilityHidden(true)`.
            .accessibilityLabel(promptText(session))
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
        HStack(alignment: .firstTextBaseline, spacing: 18) {
            // No gauge, no kilogram. The clock takes the whole hero rather than sharing
            // it with a permanent 0.0 — an empty measurement reads as a fault.
            if !timerOnly {
                LiveForceReadout(tint: forceTint(session), size: heroSize, unitSize: unitSize)
            }
            readout(value: "\(session.snapshot.secondsShown)",
                    unit: String(localized: "s"),
                    tint: isStalled(session) ? StatusTint.armed : Ink.primary,
                    rolls: true)
                .tourAnchor(.runnerClock)
        }
        .frame(maxWidth: .infinity)
        // A numeral changing 80×/second is unusable under VoiceOver; the cue sounds and
        // the counters row are the accessible channel.
        .accessibilityHidden(true)
    }

    /// `rolls` is the difference between a CLOCK and a MEASUREMENT.
    ///
    /// A countdown rolling digit-by-digit looks right — it is counting, and the motion
    /// says so. The force readout is not counting, it is REPORTING, and at ~10 updates a
    /// second the same animation turns the one number you are trying to read mid-pull
    /// into a permanent blur. It snaps.
    private func readout(value: String, unit: String, tint: Color,
                         rolls: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(value)
                .font(.system(size: heroSize, weight: .thin))
                    .displayTracking(heroSize)
                .monospacedDigit()
                .contentTransition(rolls ? .numericText() : .identity)
                .foregroundStyle(tint)
            Text(unit)
                .font(.system(size: unitSize))
                .foregroundStyle(Ink.tertiary)
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
        String(localized: "\(promptText(session)), \(session.snapshot.secondsShown) seconds remaining")
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
        device.gaugeCapabilities.isBroadcast
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
            HStack(spacing: 10) {
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
                // Neither Tare nor Connect belongs here without a gauge: one has nothing
                // to zero and the other would offer to change the session you are in.
                if timerOnly {
                    EmptyView()
                } else if device.state.isConnected {
                    TareButton(session: session)
                } else {
                    wideButton(String(localized: "Connect"), systemImage: "dot.radiowaves.left.and.right") {
                        device.connect()
                    }
                }
            }
            HStack(spacing: 10) {
                wideButton(String(localized: "Skip pull"), enabled: skipEnabled,
                           disabledReason: skipReason) { session.send(.skipRep) }
                wideButton(String(localized: "Skip set"), enabled: skipEnabled,
                           disabledReason: skipReason) { session.send(.skipSet) }
                HoldToEndButton { session.send(.abort) }
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
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .frame(maxWidth: .infinity)
            .frame(height: 48)
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
    @Environment(DeviceStore.self) private var device
    var tint: Color
    var size: CGFloat
    var unitSize: CGFloat

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(device.currentKg.formatted(.number.precision(.fractionLength(1))))
                .font(.system(size: size, weight: .thin))
                    .displayTracking(size)
                .monospacedDigit()
                // A measurement snaps; only clocks roll.
                .contentTransition(.identity)
                .foregroundStyle(tint)
            Text("kg")
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
    @Environment(DeviceStore.self) private var device
    var band: ClosedRange<Double>
    var isWorking: Bool
    var timerOnly = false

    var body: some View {
        // In a timer-only session the gauge value is zero or stale by definition. Letting
        // it light this instruction chip would claim that an unmeasured pull is engaged.
        let live = !timerOnly && isWorking && band.contains(device.currentKg)
        Text("\(kgText(band.lowerBound))–\(kgText(band.upperBound)) kg")
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

    private func kgText(_ kg: Double) -> String {
        kg.formatted(.number.precision(.fractionLength(1)))
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
        ForceTraceView(samples: device.trace, thresholdKg: thresholdKg,
                       targetBand: targetBand, tint: tint,
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
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .frame(maxWidth: .infinity)
            .frame(height: 48)
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
            Text("There's \(promptedKg.formatted(.number.precision(.fractionLength(1)))) kg on the gauge. Zero it?")
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

    /// Long enough to be deliberate, short enough not to feel like a punishment.
    private static let holdSeconds: Double = 0.9
    private static let slideSlop: CGFloat = 24

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Capsule().fill(Accent.alarm.opacity(0.16))
                // The fill IS the progress indicator — no separate spinner to read.
                //
                // **Masked to the button's OWN capsule, not a second shape at partial
                // width.** A `Capsule()` sized to `progress * width` draws its OWN fully
                // rounded outline at that width — bigger than the button at low progress
                // (Nuri: "the loading is more rectangular than the shape of the button
                // itself… and bigger than the actual button"), and at small `progress` a
                // width-constrained capsule degenerates into a circle. Masking a
                // full-bleed capsule with a leading-aligned rectangle keeps the fill's
                // outline exactly the button's own outline — rounded leading edge,
                // straight trailing sweep — and it can never exceed the button's bounds.
                Capsule()
                    .fill(Accent.alarm.opacity(0.42))
                    .mask(alignment: .leading) {
                        Rectangle()
                            .frame(width: geo.size.width * progress)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                Text(isHolding ? "Keep holding…" : "Hold to end")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(Accent.alarm)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    // Belt and braces: even reached by some other animated transaction, the
                    // label REPLACES rather than dissolving through the outgoing one.
                    .contentTransition(.identity)
                    .animation(nil, value: isHolding)
            }
            .contentShape(.capsule)
            // `minimumDistance: 0` so the fill starts on touch-DOWN; a LongPressGesture
            // gives no progress to draw until it has already succeeded.
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { updateHold(at: $0.location, in: geo.size) }
                    .onEnded { _ in endHold() }
            )
        }
        .frame(maxWidth: .infinity)
        .frame(height: 48)
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
