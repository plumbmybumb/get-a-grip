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
    /// The clocks roll — and stop rolling in Low Power Mode, where the phone has capped its
    /// refresh rate and said it wants fewer frames. See `NumeralRoll`; the force readout
    /// never rolled to begin with.
    private var clockRolls: Bool {
        NumeralRoll.rolls(luminanceReduced: false, lowPower: PowerState.shared.isLowPowerModeEnabled)
    }
    @Environment(\.dynamicTypeSize) private var typeSize
    /// Size CLASS, never the idiom — see `live(_:)`.
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// The wide layout's glass column — the panel and the dock in the two left
    /// corners at the phone's measured width, the graph filling the rest
    /// (Nuri, 2026-09-19: *"offset the squares to one side, still big enough to see"*).
    ///
    /// The house token, not a literal of the same value: "the phone's measured width"
    /// is exactly what `Metrics.maxContentWidth` means, and `liveContent` already caps
    /// the stacked column with it. Two copies of 440 would be two places to change.
    private static let wideColumnWidth: CGFloat = Metrics.maxContentWidth
    /// How much larger the identity block draws in the wide layout — the numbers,
    /// the hand word and the grip picture, read from a bench. 1.4 is what that
    /// column's width holds with the two numerals side by side.
    private static let wideScale: CGFloat = 1.4

    @State private var session: RunnerSession?
    /// Whether the grip hangs off the Dynamic Island — which is a fact about the DEVICE,
    /// resolved once the view is in a window (`IslandHand.isSupported` has nothing to read
    /// before that). The layout below reshapes around it, so it is answered here and used
    /// in both places rather than probed twice.
    @State private var hasIsland = false
    @State private var gripEmphasis = false
    @State private var gripBorderOpacity = 0.0
    @State private var emphasizedGripID: String?
    /// Where the stacked layout's full-bleed graph goes, measured from the layout that
    /// sits on top of it — see `backgroundTrace`.
    @State private var traceGeometry = BackgroundTraceGeometry()
    /// The trace's colour GLIDES between phases — see `BlendedTint`.
    @State private var traceTintFrom: Color = StatusTint.calm
    @State private var traceTintTo: Color = StatusTint.calm
    @State private var traceTintFraction: Double = 1

    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 76
    @ScaledMetric(relativeTo: .title3) private var unitSize: CGFloat = 22
    @ScaledMetric(relativeTo: .largeTitle) private var dialDiameter: CGFloat = 240
    /// The prompt's and the grip name's base sizes — scaled up with the rest of the
    /// identity block in the wide layout, where the room is real.
    @ScaledMetric(relativeTo: .largeTitle) private var promptSize: CGFloat = 34
    @ScaledMetric(relativeTo: .subheadline) private var nameSize: CGFloat = 15
    /// The ambient countdown's size — see `ambientCountdown`.
    @ScaledMetric(relativeTo: .largeTitle) private var ambientSize: CGFloat = 176

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
        // step would light an empty graph.
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
                    liveContent(session, wide: false)
                        .frame(minHeight: max(0, geometry.size.height - (hasIsland ? 46 : 0)), alignment: .top)
                }
                .scrollBounceBehavior(.basedOnSize)
                .accessibilityIdentifier("runner.content")
                // The camera hand is fixed at the root. Keep the scrolling viewport
                // below it, so the top content cannot slide through the fingers.
                .padding(.top, hasIsland ? 46 + handPush : 0)
                .background {
                    backgroundTrace(session, wide: false)
                }
            }
        } else {
            // WIDE when the window is regular-width AND wider than tall: an iPad in
            // landscape, or a foldable opened sideways. Size class and aspect, never the
            // idiom — an iPad in portrait keeps the stacked column, and a Slide Over
            // column is a phone. Apple's own guidance for the foldable says the same.
            GeometryReader { geometry in
                let wide = sizeClass == .regular && geometry.size.width > geometry.size.height
                liveContent(session, wide: wide)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    // The graph is the SCREEN in both layouts — see `backgroundTrace`.
                    .background {
                        backgroundTrace(session, wide: wide)
                    }
            }
        }
    }

    private func liveContent(_ session: RunnerSession, wide: Bool) -> some View {
        Group {
            if wide {
                wideContent(session)
            } else {
                stackedContent(session)
            }
        }
        .padding(.horizontal, Metrics.hPadding)
        // The fingers reach ~92 pt down the screen and content starts at 59, so the hand
        // needs the gap bought for it — otherwise the grip name lands under the knuckles.
        .padding(.top, hasIsland ? (typeSize.isAccessibilitySize ? 0 : 46 + handPush) : 8)
        .padding(.bottom, Metrics.spacing)
        // The stacked column keeps the PHONE's width even on a regular-width screen: the
        // hero numeral, the ring and the button rows were all measured at 440, and an
        // iPad in portrait shows that same picture with wider margins. The wide layout
        // spans the room: its column is fixed and the graph takes whatever is left.
        .frame(maxWidth: wide ? .infinity : Metrics.maxContentWidth)
        .frame(maxWidth: .infinity)
    }

    /// **The enlarged grip-change hand pushes the panel down** by exactly the distance
    /// its fingertips grow (`IslandHand.tipDrop`), in the same transaction that grows
    /// them, so the hand and the glass move as one thing and the fingers never reach
    /// into the numbers (Nuri, 2026-09-19). The ordinary long-rest enlargement does
    /// NOT push: that would move the graph at every REST→PULL boundary, which is the
    /// jump the rest layout was measured to avoid, and at a fifth larger the tips still
    /// clear the panel. A changed grip is rare and is meant to be felt. Reduce Motion
    /// draws no enlarged hand, so there is nothing to make room for.
    private var handPush: CGFloat {
        gripEmphasis && !reduceMotion ? IslandHand.tipDrop(scale: IslandHand.emphasisScale) : 0
    }

    /// The phone's layout: identity, hero, graph, controls, top to bottom.
    ///
    /// One `GlassEffectContainer` for the two glass surfaces on this screen — the
    /// panel and the dock — so Liquid Glass renders them in a single pass rather than
    /// blurring the live canvas twice. Nothing here changes shape between phases (the
    /// panel reserves its geometry on purpose), so no morphing identities are needed.
    private func stackedContent(_ session: RunnerSession) -> some View {
        GlassEffectContainer(spacing: 24) {
            VStack(spacing: 12) {
                if timerOnly {
                    timerOnlyIdentity(session)
                    timerDial(session)
                    timerPositionLine(session)
                } else {
                    infoPanel(session)
                    graphRegion(session)
                }
                controls(session)
            }
        }
    }

    /// The wide window (an iPad in landscape, a foldable opened flat): the graph fills
    /// the whole screen and the phone's two objects sit in the two left corners — the
    /// panel top-left, the dock bottom-left, at the phone's measured width — so the
    /// curve's history slides under the glass and its newest seconds run in the clear
    /// on the right (Nuri, 2026-09-19: *"the back being just the graph that fills the
    /// whole screen, then offset the squares to one side, still big enough to see"*).
    ///
    /// The NEXT card that used to sit between them is gone (Nuri, same evening). It
    /// only ever existed because the old layout had spare room; over a live graph every
    /// glass surface is a place the curve cannot be read, a third object made "what
    /// comes next" as heavy as the pull under way, and the phone has never had one —
    /// the rest panel already names the next hand and grip.
    private func wideContent(_ session: RunnerSession) -> some View {
        GlassEffectContainer(spacing: 24) {
            HStack(alignment: .top, spacing: Metrics.spacing) {
                VStack(spacing: 16) {
                    if timerOnly {
                        VStack(spacing: 14) {
                            timerOnlyIdentity(session, scale: Self.wideScale)
                            timerPositionLine(session)
                        }
                    } else {
                        infoPanel(session, scale: Self.wideScale)
                    }
                    Spacer(minLength: 16)
                    controls(session)
                }
                .frame(width: Self.wideColumnWidth)
                .frame(maxHeight: .infinity)
                if timerOnly {
                    timerDial(session)
                } else {
                    graphRegion(session, wide: true)
                }
            }
        }
    }

    /// Only while the rep is actually live. A lane drawn during the rest would ask you
    /// to hold a load you are not holding.
    private func liveTargetBand(_ session: RunnerSession) -> ClosedRange<Double>? {
        isWorking(session) || isArmed(session) ? session.snapshot.targetBand : nil
    }

    /// A connected gauge that is not sending is the one failure "0.0 kg" renders as a
    /// lie — it reads as a device measuring nothing rather than an app receiving
    /// nothing, and there is no way to tell them apart by looking. Say it, and say what
    /// to do — the notice itself is drawn by `graphRegion`.
    private func showsSignalNotice(_ session: RunnerSession) -> Bool {
        !session.snapshot.hasSignal
            || (showsRestFocus(session) && !restSignalIsAvailable(session))
    }

    /// **The countdown you can read from the wall.** While the clock is the only thing
    /// happening — the count-in, a rest, a paused rest — the open graph is four
    /// hundred points of nothing, so the seconds go there, huge and thin, and fade the
    /// moment the trace has something to show (Nuri, 2026-09-19: *"clear understanding
    /// of the app from a distance"*). It is THE rest countdown: the panel no longer
    /// repeats it (see `RunnerRestFocusSummary`), so this numeral carries the
    /// `runner.restFocus.countdown` identity and stays accessible.
    private func showsAmbientCountdown(_ session: RunnerSession) -> Bool {
        switch session.snapshot.phase {
        case .resting, .leadIn: true
        case .paused(let inner):
            switch inner {
            case .resting, .leadIn: true
            default: false
            }
        default: false
        }
    }

    private func ambientCountdown(_ session: RunnerSession) -> some View {
        Text("\(session.snapshot.secondsShown)")
            .font(.system(size: ambientSize, weight: .thin))
            .displayTracking(ambientSize)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.35)
            // A clock rolls — unless Reduce Motion or Low Power Mode says otherwise
            // (`clockRolls`). Measured at 0.75 the secondary ink clears 3:1 on the light
            // field for a numeral this size.
            .contentTransition(clockRolls && !reduceMotion ? .numericText(countsDown: true) : .identity)
            .animation(clockRolls && !reduceMotion ? Motion.live : nil,
                       value: session.snapshot.secondsShown)
            .foregroundStyle(Ink.secondary.opacity(0.75))
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
            .accessibilityIdentifier("runner.restFocus.countdown")
    }

    // MARK: - The stacked layout: the graph is the screen, the numbers are glass

    /// **The information panel** — the identity block on one Liquid Glass surface
    /// floating over the graph (Nuri's sketch, 2026-09-19: *"a full background and a
    /// liquid glass frame over the graph that has the info on it instead of two
    /// distinct sections"*). The same `measuredTop` as before — grip, prompt, hero,
    /// progress, counters, and the rest summary in their place — so nothing about what
    /// the panel SAYS changed, only what it sits on.
    ///
    /// `accessibleGlass`, never raw `.glassEffect`: under Reduce Transparency the panel
    /// becomes an opaque card, which is the only way the numbers stay legible over a
    /// live curve. The grip-change outline moves here from the graph's card — the panel
    /// is where the changed grip is NAMED, and the open graph has no edge to draw it on.
    private func infoPanel(_ session: RunnerSession, scale: CGFloat = 1) -> some View {
        measuredTop(session, scale: scale)
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 12)
            .frame(maxWidth: .infinity)
            // The rim is applied BEFORE the glass, so it is part of the panel's content.
            // Inside a `GlassEffectContainer` the glass is composited above anything
            // applied after `.glassEffect`, and an overlay there vanished under the
            // material — measured in pixels, not by eye, which had read a warm edge
            // as the rim (2026-09-19).
            .overlay {
                RunnerGlass.surfaceShape
                    .strokeBorder(StatusTint.armed, lineWidth: 3)
                    .opacity(session.snapshot.hasSignal ? gripBorderOpacity : 0)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
            .runnerFloatingShadow()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("runner.panel")
    }

    /// **The open graph** — the stretch of screen between the panel and the controls
    /// where the curve runs in the clear. It draws nothing itself: the trace is the
    /// screen's background (`backgroundTrace`), and this is the frame that PLACES the
    /// plot inside it, plus what still belongs on the graph — the no-signal notice, the
    /// tour anchor, and the `runner.graph` element the UI tests measure the layout by.
    ///
    /// NOT the grip-change chip. In the old card it sat in a corner; on an open graph
    /// it floated loose thirty points under the panel, saying what the panel's amber
    /// rim, its NEW GRIP badge and the orange hand already say — a stray box over the
    /// trace for no new information (measured 2026-09-19). Neither layout draws it now.
    private func graphRegion(_ session: RunnerSession, wide: Bool = false) -> some View {
        let notice = showsSignalNotice(session)
        let ambient = showsAmbientCountdown(session)
        return ZStack {
            Color.clear
            // Both at once when the gauge goes quiet mid-rest: the countdown is the
            // rest's own clock and does not depend on the gauge, so losing the link
            // must not hide it — it moves up and the notice takes the room below.
            VStack(spacing: 8) {
                if ambient {
                    // It materializes: a whisper of scale with the fade, critically
                    // damped, so the numeral arrives rather than switches on. Under
                    // Reduce Motion it is the cross-fade alone.
                    ambientCountdown(session)
                        .transition(reduceMotion ? .opacity
                                                 : .opacity.combined(with: .scale(scale: 0.96)))
                }
                if notice {
                    noSignalNotice(session)
                        .accessibilityIdentifier("runner.signalWarning")
                }
            }
        }
        .animation(Motion.state(reduceMotion), value: ambient)
        .frame(minHeight: typeSize.isAccessibilitySize ? 240 : nil,
               maxHeight: .infinity)
        // The trace is the region's own background, stretched sideways to the screen
        // edges (the column's margins) so it still reads as the screen's graph, but
        // never extended under the panel or the dock — see `backgroundTrace` for the
        // measured reason. The plot keeps the card's small edge clearances; on a wide
        // screen it keeps clear of the bezel.
        .background {
            if !timerOnly {
                LiveTrace(thresholdKg: session.plan.thresholdKg,
                          targetBand: liveTargetBand(session),
                          tint: tint(session),
                          plot: ForceTraceView.PlotInsets(top: wide ? 24 : 12,
                                                          bottom: wide ? 24 : 6,
                                                          trailing: 8),
                          lit: true)
                    .padding(.leading, wide ? 0 : -Metrics.hPadding)
                    .padding(.trailing, -Metrics.hPadding)
                    // The wash already cross-fades between phases; the trace snapped,
                    // and on an object this size a hard cut of colour is a jolt. The
                    // blend runs on the same house curve, so the two move as one thing.
                    .modifier(BlendedTint(fraction: traceTintFraction,
                                          from: traceTintFrom, to: traceTintTo))
                    .onAppear {
                        traceTintFrom = tint(session)
                        traceTintTo = tint(session)
                    }
                    .onChange(of: tint(session)) { old, new in
                        traceTintFrom = old
                        traceTintTo = new
                        traceTintFraction = 0
                        withAnimation(Motion.state(reduceMotion)) { traceTintFraction = 1 }
                    }
            }
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
            traceGeometry.region = $0
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("runner.graph")
        .tourAnchor(.runnerTrace)
    }

    /// **The trace as the SCREEN, not a card** — the stacked layout's graph.
    ///
    /// The canvas runs edge to edge, under the status bar, the panel and the controls;
    /// the PLOT inside it is placed by the glass it runs beneath, see
    /// `BackgroundTraceGeometry`. Only the graph's home changed: the same `LiveTrace`
    /// leaf, the same lane, the same phase tint, still read from the store one level
    /// down so a sample invalidates nothing but the canvas. Nothing force-shaped in a
    /// gauge-free session, exactly as before.
    @ViewBuilder
    private func backgroundTrace(_ session: RunnerSession, wide: Bool) -> some View {
        if !timerOnly {
            // ONLY the wash lives under the glass. The live canvas used to run under
            // the panel and the dock too, and iOS re-blurs a glass backdrop every frame
            // the layer beneath it changes — so the trace was being rendered and then
            // blurred twice over, at 120 Hz, and the line's own motion went uneven on
            // Nuri's phone (his recording, 2026-09-19 evening: "jittery, different from
            // the old line"). The wash is a fill that changes once per phase, which is
            // exactly what a glass surface can sit on for free; the trace now draws in
            // the open region alone (`graphRegion`), edge to edge sideways but never
            // beneath glass.
            PhaseWash(tint: tint(session),
                      edge: wide ? .leading : .top,
                      length: wide ? traceGeometry.regionLeadingInCanvas
                                   : traceGeometry.regionTopInCanvas)
            // Measured INSIDE `ignoresSafeArea`: outside it the reported frame is the
            // safe-area frame the parent proposed, not the full-bleed one.
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
                traceGeometry.canvas = $0
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
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
    private func measuredTop(_ session: RunnerSession, scale: CGFloat = 1) -> some View {
        let focused = showsRestFocus(session)
        return Group {
            if focused && typeSize.isAccessibilitySize {
                RunnerRestFocusSummary(snapshot: session.snapshot, showsGlyph: !hasIsland)
            } else {
                Group {
                    if focused {
                        // Hidden retains geometry and removes the old live readout
                        // from both the drawing and the accessibility tree.
                        measuredTopContents(session, scale: scale).hidden()
                    } else {
                        measuredTopContents(session, scale: scale)
                    }
                }
                .overlay {
                    if focused {
                        RunnerRestFocusSummary(snapshot: session.snapshot, showsGlyph: !hasIsland,
                                               scale: scale)
                            .transition(.opacity)
                    }
                }
            }
        }
        .animation(Motion.state(reduceMotion), value: focused)
    }

    private func measuredTopContents(_ session: RunnerSession, scale: CGFloat = 1) -> some View {
        VStack(spacing: 12 * scale) {
            if hasIsland {
                gripLineText(session)
            } else {
                gripLine(session, scale: scale)
            }
            prompt(session, scale: scale)
            hero(session, scale: scale)
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

    /// Which pull you are ON, not how many you have completed — see
    /// `RunnerSnapshot.pullPosition`. It matches the "Set 6 of 6" beside it.
    private func pullLine(_ session: RunnerSession) -> String {
        String(localized: "Pull \(session.snapshot.pullPosition) of \(session.snapshot.plannedRepCount)")
    }

    /// The name row alone — used by the island layout, where the picture has moved to
    /// the top of the screen and drawing it twice would be silly.
    @ViewBuilder
    private func gripLineText(_ session: RunnerSession, timerOnly: Bool = false) -> some View {
        if session.snapshot.grip != nil {
            nameRow(session, timerOnly: timerOnly).frame(maxWidth: .infinity)
        }
    }

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
    @ViewBuilder
    private func gripLine(_ session: RunnerSession, timerOnly: Bool = false,
                          scale: CGFloat = 1) -> some View {
        if let grip = session.snapshot.grip {
            VStack(spacing: 6 * scale) {
                RunnerGripGlyph(grip: grip, emphasized: gripEmphasis, scale: scale)
                nameRow(session, timerOnly: timerOnly, scale: scale)
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(spokenGrip(session, grip: grip)
                                + (timerOnly ? String(localized: ", timing only") : ""))
        }
    }

    /// The grip, and — during a rest — the fact that it is the one COMING UP.
    ///
    /// The snapshot already looks forward while resting (`SessionRunner.displaySlot`), so
    /// this row silently changed meaning between phases. "Next" is what makes that legible
    /// instead of leaving you to work out which grip you are being shown.
    @ViewBuilder
    private func nameRow(_ session: RunnerSession, timerOnly: Bool = false,
                         scale: CGFloat = 1) -> some View {
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
                            .font(.system(size: nameSize * scale, weight: .medium))
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
                            .font(.system(size: nameSize * scale, weight: .medium))
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
        let lo = weightUnit.number(band.lowerBound)
        let hi = weightUnit.number(band.upperBound)
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

    /// The one thing that has to be readable across a room: which hand, and whether to
    /// be pulling right now.
    private func prompt(_ session: RunnerSession, scale: CGFloat = 1) -> some View {
        ZStack {
            // Keep the original phase label's line height when a longer translated
            // hand instruction scales to fit. The graph must not move at REST→PULL
            // or REST→PAUSED just because one prompt needs smaller lettering.
            Text("REST").hidden().accessibilityHidden(true)
            Text(measuredPromptText(session))
        }
            .tourAnchor(.runnerHand)
            .font(.system(size: promptSize * scale, weight: .heavy))
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
            // tone for both RE-GRIP and EASE OFF — the cue means "the clock stopped",
            // which is true either way, and only the screen has the words that tell the
            // two apart; pause/resume emit no cue at all). An explicit
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

    /// The ladder itself is `RunnerPromptWords`, shared with the watch — this only
    /// gathers the facts. `timerOnly` is one of them: a gauge-free session has nothing
    /// to connect to, so its first second says GET READY rather than CONNECTING, which
    /// is what the watch has always said and what this screen used not to.
    private func promptText(_ session: RunnerSession) -> String {
        RunnerPromptWords.word(phase: session.snapshot.phase,
                               side: session.snapshot.side,
                               isConnected: device.state.isConnected,
                               timerOnly: timerOnly,
                               isDropped: session.snapshot.isDropped,
                               isOverTarget: session.snapshot.isOverTarget,
                               isSetBreak: session.snapshot.isSetBreak)
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
    private func hero(_ session: RunnerSession, scale: CGFloat = 1) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            HStack(alignment: .lastTextBaseline, spacing: 18 * scale) {
                // No gauge, no kilogram. The clock takes the whole hero rather than sharing
                // it with a permanent 0.0 — an empty measurement reads as a fault.
                if !timerOnly {
                    LiveForceReadout(tint: forceTint(session),
                                     size: heroSize * scale, unitSize: unitSize * scale)
                }
                readout(value: "\(session.snapshot.secondsShown)",
                        unit: String(localized: "s"),
                        tint: isStalled(session) ? StatusTint.armed : Ink.primary,
                        rolls: clockRolls,
                        caption: nil,
                        scale: scale)
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
                         rolls: Bool, caption: String? = nil, scale: CGFloat = 1) -> some View {
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
            .font(.system(size: heroSize * scale, weight: .thin))
            .displayTracking(heroSize * scale)
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
                    .font(.system(size: unitSize * scale))
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
    ///
    /// **A FIXED set of chips — fixed for the whole SESSION, not for the current slot —
    /// and that is what stops the dial moving.**
    ///
    /// `Next` used to be a chip here, appearing only during rest — which meant the row
    /// could hold three chips resting and two working. At REST→WORK that can flip
    /// `ViewThatFits` from the stacked candidate back to the single row, or simply drop a
    /// row; either way the identity block changes height and the dial below it shifts
    /// underneath the climber's eye, every single rep. `Next` now lives on the grip line
    /// as the rest badge, RESERVED at its widest form for the whole session (see
    /// `nameRow`'s timer branch) — same constant-height property, achieved the same way
    /// as the target chip below.
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
    private func timerOnlyIdentity(_ session: RunnerSession, scale: CGFloat = 1) -> some View {
        // The island already owns the glyph on supported phones. Drawing it again in the
        // identity block would make the hand read as two instructions, not one.
        if hasIsland {
            gripLineText(session, timerOnly: true)
        } else {
            gripLine(session, timerOnly: true, scale: scale)
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
                    .contentTransition(clockRolls ? .numericText() : .identity)
                    // `.numericText()` needs an animation OBSERVING the value or it does
                    // nothing — the digits just cut. The outer animation watches `phase`,
                    // which changes once per phase, not once per second, so without this
                    // the hero numeral of a timer snapped instead of rolling.
                    .animation(clockRolls ? Motion.live : nil, value: session.snapshot.secondsShown)
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
        // **THE COLUMN MUST STILL FILL THE SCREEN.** In the measured layout the open
        // graph carries `maxHeight: .infinity`, and that is what made the whole VStack tall.
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
            Text(device.state.isConnected ? connectedButSilentHint : connectHint)
                .font(.system(.footnote))
                .foregroundStyle(Ink.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
        .accessibilityElement(children: .combine)
    }

    /// A connected gauge with nothing to show is usually a stalled stream — unless it is
    /// a Dyno still waiting on its calibration, in which case Wake would not help and the
    /// honest line is the one the calibration status carries.
    private var connectedButSilentHint: String {
        switch device.calibrationStatus {
        case .waitingForSerial, .resolving:
            String(localized: "Looking up this Dyno's calibration…")
        case .failed(_, let failure):
            failure.label
        case .notRequired, .ready:
            String(localized: "Connected, but no readings yet. Tap Wake to restart it.")
        }
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

    /// **The dock** — the five actions on ONE glass surface, the way iOS 26 draws a
    /// toolbar, instead of five separate glass capsules (Nuri, 2026-09-19). Inside it
    /// each action sits in a quiet ink well rather than its own glass: glass on glass
    /// is the one layering Liquid Glass asks you not to do, and five lozenges over a
    /// live curve read as five objects where there is one control surface. Hold to
    /// end keeps its red fill — the single tinted item, as a toolbar's one destructive
    /// action would be.
    private func controls(_ session: RunnerSession) -> some View {
        VStack(spacing: Self.dockSpacing) {
            AdaptiveActionRow(spacing: Self.dockSpacing) {
                pauseButton(session, docked: true)
                gaugeButton(session, docked: true)
            }
            AdaptiveActionRow(spacing: Self.dockSpacing) {
                skipButtons(session, docked: true)
                endButton(session)
            }
        }
        .padding(Self.dockSpacing)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        // `.contain`, explicitly: an identifier on a bare container makes SwiftUI
        // COMBINE its children into one element, and every button in the dock
        // vanished from the accessibility tree (16 UI tests could not find Pause).
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("runner.dock")
    }

    private static let dockSpacing: CGFloat = 8

    /// The buttons KEEP their identity while disabled: the visible reason the house
    /// rule demands is the prompt above them, which says PAUSED / CONNECTING at
    /// large-title weight — swapping the labels spent the two Skips' names on the same
    /// repeated word, and VoiceOver read "Paused, dimmed. Paused." twice with no way to
    /// tell them apart. The full sentence rides the hint instead.
    private func pauseButton(_ session: RunnerSession, docked: Bool = false) -> some View {
        let phase = session.snapshot.phase
        return dockButton(phase.isPaused ? String(localized: "Resume") : String(localized: "Pause"),
                          systemImage: phase.isPaused ? "play.fill" : "pause.fill",
                          enabled: RunnerControlPolicy.pauseEnabled(for: phase),
                          disabledReason: RunnerControlPolicy.pauseDisabledReason(for: phase),
                          docked: docked) {
            session.send(phase.isPaused ? .resume : .pause)
        }
        // An iPad on a bench with a keyboard case: space pauses, the same key every
        // video player uses. The runner has no text field to fight over it.
        .keyboardShortcut(.space, modifiers: [])
        .accessibilityIdentifier("runner.pause")
    }

    /// Neither Tare nor Connect belongs here without a gauge: one has nothing to zero
    /// and the other would offer to change the session you are in.
    @ViewBuilder
    private func gaugeButton(_ session: RunnerSession, docked: Bool = false) -> some View {
        if timerOnly {
            EmptyView()
        } else if device.state.isConnected {
            TareButton(session: session, docked: docked)
                .accessibilityIdentifier("runner.tare")
        } else if device.canCancelBroadcastSearch {
            dockButton(String(localized: "Cancel"), systemImage: "xmark", docked: docked) {
                device.disconnect()
            }
            .accessibilityLabel(String(localized: "Cancel"))
            .accessibilityValue(device.state.label)
            .accessibilityIdentifier("gauge.connectionAction")
        } else {
            dockButton(String(localized: "Connect"), systemImage: "dot.radiowaves.left.and.right",
                       docked: docked) {
                device.connect()
            }
        }
    }

    @ViewBuilder
    private func skipButtons(_ session: RunnerSession, docked: Bool = false) -> some View {
        let phase = session.snapshot.phase
        let skipEnabled = RunnerControlPolicy.skipEnabled(for: phase)
        let skipReason = RunnerControlPolicy.skipDisabledReason(for: phase)
        dockButton(String(localized: "Skip pull"), enabled: skipEnabled,
                   disabledReason: skipReason, docked: docked) { session.send(.skipRep) }
            .keyboardShortcut("s", modifiers: [])
            .accessibilityIdentifier("runner.skipPull")
        dockButton(String(localized: "Skip set"), enabled: skipEnabled,
                   disabledReason: skipReason, docked: docked) { session.send(.skipSet) }
            .accessibilityIdentifier("runner.skipSet")
    }

    private func endButton(_ session: RunnerSession) -> some View {
        HoldToEndButton(allowsScrolling: typeSize.isAccessibilitySize) { session.send(.abort) }
            .accessibilityIdentifier("runner.end")
    }

    /// A dock action: FULL-WIDTH in its slot, not iPad-wide — both layouts use it,
    /// and the name says which width it means. `SecondaryGlassButton` hugs its label,
    /// which is right on a sheet and wrong here: three hugging buttons in one row
    /// truncated "Pause" to "Pa…" on the pinned sim. Glass INSIDE the label, then
    /// the content shape, then the style outside.
    ///
    /// `enabled`/`disabledReason` dim AND disable, with the reason surfaced as the
    /// accessibility hint. The label is never swapped: sighted use reads the reason
    /// off the screen's own PAUSED / CONNECTING prompt, and the hint carries the full
    /// sentence for VoiceOver.
    private func dockButton(_ title: String, systemImage: String? = nil,
                            tint: Color = Ink.primary,
                            enabled: Bool = true, disabledReason: String? = nil,
                            docked: Bool = false,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(.subheadline, weight: .semibold))
            .foregroundStyle(enabled ? tint : Ink.tertiary.opacity(0.5))
            .actionLabelLayout(fullWidth: true, fillsRowHeight: true)
            .runnerActionSurface(docked: docked)
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
        // `pullPosition`, not `completed + 1`: this label was the one readout with no
        // clamp, so VoiceOver alone could say "pull 37 of 36".
        return String(localized: """
            Set \(set) of \(session.snapshot.setCount), \
            pull \(session.snapshot.pullPosition) of \(session.snapshot.plannedRepCount), \
            \(session.snapshot.side?.name ?? "") hand, \(grip.spoken)
            """)
    }
}

private struct GripCueKey: Equatable {
    var id: String?
    var resting: Bool
}
