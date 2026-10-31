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
    /// Compact HEIGHT is a phone on its side — the far view (`farContent`).
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    /// This runner's claim on landscape — see `RunnerOrientation`.
    @State private var orientationToken = UUID()

    /// The wide layout's glass column — the panel and the dock in the two left
    /// corners at the phone's measured width (`Metrics.maxContentWidth`, the same token
    /// `liveContent` caps the stacked column with), the graph filling the rest.
    private static let wideColumnWidth: CGFloat = Metrics.maxContentWidth
    /// How much larger the identity block draws in the wide layout — the numbers,
    /// the hand word and the grip picture, read from a bench. 1.4 is what that
    /// column's width holds with the two numerals side by side.
    private static let wideScale: CGFloat = 1.4

    @State private var session: RunnerSession?
    /// Whether the grip hangs off the Dynamic Island — a fact about the DEVICE, resolved
    /// once the view is in a window (`IslandHand.isSupported` has nothing to read before
    /// that). Answered once because both the overlay and the layout depend on it.
    @State private var hasIsland = false
    /// The hand hangs off the island only while the island is at the TOP of the screen:
    /// on its side (compact height) the cutout is beside the content, and the far view
    /// draws its own glyph instead.
    private var islandHandShown: Bool { hasIsland && verticalSizeClass != .compact }
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
    /// The timer-only SET / PULL figures — read from the floor, between pulls.
    @ScaledMetric(relativeTo: .largeTitle) private var figureSize: CGFloat = 44
    /// The prompt's and the grip name's base sizes — scaled up with the rest of the
    /// identity block in the wide layout, where the room is real.
    @ScaledMetric(relativeTo: .largeTitle) private var promptSize: CGFloat = 34
    @ScaledMetric(relativeTo: .subheadline) private var nameSize: CGFloat = 15
    /// The ambient countdown's size — see `ambientCountdown`.
    @ScaledMetric(relativeTo: .largeTitle) private var ambientSize: CGFloat = 176
    /// The far view's one numeral, BEFORE the height clamps it — see `farNumeral`. Big
    /// enough that the face's height, not this, is what sets the size on every phone.
    @ScaledMetric(relativeTo: .largeTitle) private var farHeroSize: CGFloat = 340

    var body: some View {
        Group {
            if let session {
                if session.isFinished {
                    SessionSummaryView(template: template,
                                       plan: session.plan,
                                       reps: session.runner.results,
                                       startedAt: session.startedAt,
                                       finishedAt: session.finishedAt ?? session.startedAt,
                                       didAnyWork: session.runner.didAnyWork) {
                        // Saved or discarded (a failed save never gets here): nothing
                        // left to offer back at the next launch.
                        session.clearDraft()
                        dismiss()
                    }
                        .transition(.opacity)
                } else {
                    live(session)
                }
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .background { AppBackground() }
        // At the ROOT, so `ignoresSafeArea` reaches the top of the screen — inside
        // `live` it was clipped to the safe-area frame and drew nothing.
        .islandHand(grip: session?.isFinished == true ? nil : session?.snapshot.grip,
                    side: session?.snapshot.side,
                    isActive: !(session.map { isResting($0) } ?? true),
                    enabled: islandHandShown, emphasized: gripEmphasis,
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
            // The maxes are read ONCE, here — a session's targets must not move because
            // a max was recorded on another device mid-workout. `.standard` drafts keep a
            // finished session on disk until saved or discarded, surviving process death.
            let new = RunnerSession(template: template, device: device,
                                    maxes: templates.maxTable, timerOnly: timerOnly,
                                    draftStore: .standard)
            new.weightUnit = weightUnit
            session = new
            new.begin()
        }
        // Landscape while the LIVE runner is up, and given back the moment the session
        // finishes (the summary is portrait) or the view goes, by any route — see
        // `RunnerOrientation`.
        .onChange(of: session.map { !$0.isFinished } ?? false, initial: true) { _, live in
            RunnerOrientation.setLandscapeAllowed(live, by: orientationToken)
        }
        // The island is a portrait fact: re-read it when the phone comes back upright,
        // in case the runner first appeared on its side (top inset 0 there).
        .onChange(of: verticalSizeClass) { _, size in
            if size != .compact, !hasIsland { hasIsland = IslandHand.isSupported }
        }
        .onDisappear {
            RunnerOrientation.setLandscapeAllowed(false, by: orientationToken)
            session?.end()
        }
        .onChange(of: weightUnit) { _, unit in session?.weightUnit = unit }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                // Bin whatever the radio buffered while we were away — see
                // `DeviceStore.dropStaleTrace`.
                device.dropStaleTrace()
                // **AND KICK THE STREAM.** The gauge stops sending while the app is
                // suspended, and otherwise only the silence watchdog (500 ms checks,
                // 0.8 s of Progressor silence) revives it — seen as "a little dot, then
                // after a while the stream continues" (Nuri, 2026-08-10). Re-sending start
                // to a live stream is harmless; not sending it is dead seconds mid-rep.
                session?.startIfReady(cause: .foreground)
            } else if phase == .background, !timerOnly {
                // Clear the device-time anchor BEFORE suspension. Backgrounding already
                // forfeits any unobserved work; this prevents queued old-epoch samples
                // from inheriting a high-water mark across the foreground re-kick.
                session?.send(.streamRestarted)
            }
            // **Backgrounding does not pause a CONNECTED session**: `bluetooth-central`
            // keeps the app alive while the Progressor notifies, so the Live Activity
            // carries the workout (Nuri, 2026-08-09). Disconnected — or on a broadcast
            // gauge, whose advertisement scan does not survive backgrounding — iOS
            // suspends us and a rep would silently stall, so it pauses. The rule lives
            // in `BackgroundPausePolicy`.
            //
            // **`timerOnly` always pauses, whatever is connected.** A gauge-free session
            // never streams, so nothing keeps the process alive; the ticker stops and
            // `holdTick` would freeze with no PAUSED state to explain it.
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
        .sensoryFeedback(.impact(weight: .heavy, intensity: 0.8), trigger: session?.repTick ?? 0)
        .sensoryFeedback(.selection, trigger: session?.phaseTick ?? 0)
    }

    // MARK: - The session screen

    @ViewBuilder
    private func live(_ session: RunnerSession) -> some View {
        if verticalSizeClass == .compact {
            // ON ITS SIDE — a phone in landscape, or an iPhone Duo stood up like a tent
            // on the floor under the board: the far view. Checked FIRST, at every text
            // size: the far view keeps every action reachable by scrolling its own
            // trailing column, where the scrolling stack below would put the whole
            // runner of a ~440 pt tall screen behind a scroll.
            GeometryReader { geometry in
                let insets = geometry.safeAreaInsets
                // A side that carries a system column (the Duo's bars, a Pro Max's island)
                // already has its margin: our own 20 pt beside it was 105 pt of nothing
                // on Nuri's Duo (2026-10-31). A bare side keeps the house margin.
                let far = RunnerLayout.far(RunnerLayout.Far(
                    columnWidth: farColumnWidth(for: geometry.size.width),
                    columnLeading: insets.leading > insets.trailing + 1,
                    leadingPadding: insets.leading > 0 ? 0 : Metrics.hPadding,
                    trailingPadding: insets.trailing > 0 ? 0 : Metrics.hPadding))
                liveContent(session, layout: far)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .background {
                        backgroundTrace(session, layout: far)
                    }
            }
        } else if typeSize.isAccessibilitySize {
            // At large accessibility sizes a small phone cannot hold five readable
            // actions and the measurements at once. Keep every action reachable by
            // scrolling instead of clipping labels or reducing their chosen type.
            GeometryReader { geometry in
                ScrollView {
                    liveContent(session, layout: .stacked, narrowDock: Self.isNarrow(geometry.size.width))
                        .frame(minHeight: max(0, geometry.size.height - (islandHandShown ? 46 : 0)), alignment: .top)
                }
                .scrollBounceBehavior(.basedOnSize)
                .accessibilityIdentifier("runner.content")
                // The camera hand is fixed at the root. Keep the scrolling viewport
                // below it, so the top content cannot slide through the fingers.
                .padding(.top, islandHandShown ? 46 + handPush : 0)
                .background {
                    backgroundTrace(session, layout: .stacked)
                }
            }
        } else {
            // WIDE when the window is regular-width AND wider than tall. Size class and
            // aspect, never the idiom — an iPad in portrait keeps the stacked column, and
            // a Slide Over column is a phone.
            GeometryReader { geometry in
                let layout: RunnerLayout = sizeClass == .regular && geometry.size.width > geometry.size.height
                    ? .wide : .stacked
                liveContent(session, layout: layout, narrowDock: Self.isNarrow(geometry.size.width))
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    // The graph is the SCREEN in both layouts — see `backgroundTrace`.
                    .background {
                        backgroundTrace(session, layout: layout)
                    }
            }
        }
    }

    private func liveContent(_ session: RunnerSession, layout: RunnerLayout,
                             narrowDock: Bool = false) -> some View {
        Group {
            switch layout {
            case .wide: wideContent(session)
            case .stacked: stackedContent(session, narrowDock: narrowDock)
            case .far(let far):
                farContent(session, columnWidth: far.columnWidth, columnLeading: far.columnLeading)
            }
        }
        .padding(.leading, layout.far?.leadingPadding ?? Metrics.hPadding)
        .padding(.trailing, layout.far?.trailingPadding ?? Metrics.hPadding)
        // The fingers reach ~92 pt down the screen and content starts at 59, so the hand
        // needs the gap bought for it — otherwise the grip name lands under the knuckles.
        .padding(.top, islandHandShown ? (typeSize.isAccessibilitySize ? 0 : 46 + handPush) : 8)
        .padding(.bottom, Metrics.spacing)
        // The stacked column keeps the PHONE's width on a phone — hero, ring and buttons
        // were all measured at 440 — and takes the regular-width token on a regular-width
        // portrait window (an unfolded Duo, an iPad upright), where 440 read as a phone in
        // a frame. The wide and far layouts span the room.
        .frame(maxWidth: layout == .stacked ? stackedColumnWidth : .infinity)
        .frame(maxWidth: .infinity)
    }

    /// The stacked column on a REGULAR-width portrait window is a different object from
    /// the phone's: wider, with the identity block at the wide scale, because it is read
    /// from further away on a bigger screen. Not at accessibility sizes, where the
    /// person's own type size already is the scale.
    private var stackedColumnWidth: CGFloat {
        sizeClass == .regular ? Metrics.maxContentWidthRegular : Metrics.maxContentWidth
    }
    /// A window narrower than any iPhone — Split View's half of an unfolded Duo, 313 pt —
    /// gives Hold to end its own dock row, as the far view's column does (`controls`).
    /// The narrowest iPhone is 375, so every phone keeps the measured two-row dock.
    static func isNarrow(_ width: CGFloat) -> Bool { width < narrowDockWidth }
    private static let narrowDockWidth: CGFloat = 360

    private var stackedScale: CGFloat {
        sizeClass == .regular && !typeSize.isAccessibilitySize ? Self.wideScale : 1
    }

    /// **The enlarged grip-change hand pushes the panel down** by the distance its
    /// fingertips grow (`IslandHand.tipDrop`), in the same transaction, so the fingers
    /// never reach into the numbers. The ordinary long-rest enlargement does NOT push:
    /// that would move the graph at every REST→PULL boundary, and at a fifth larger the
    /// tips still clear the panel. Reduce Motion draws no enlarged hand.
    private var handPush: CGFloat {
        gripEmphasis && !reduceMotion ? IslandHand.tipDrop(scale: IslandHand.emphasisScale) : 0
    }

    /// The phone's layout: identity, hero, graph, controls, top to bottom.
    ///
    /// One `GlassEffectContainer` for the panel and the dock, so Liquid Glass renders
    /// them in a single pass rather than blurring the live canvas twice. Nothing changes
    /// shape between phases, so no morphing identities are needed.
    private func stackedContent(_ session: RunnerSession, narrowDock: Bool = false) -> some View {
        GlassEffectContainer(spacing: 24) {
            VStack(spacing: 12) {
                if timerOnly {
                    timerOnlyIdentity(session, scale: stackedScale)
                    timerDial(session)
                    timerProgress(session)
                } else {
                    infoPanel(session, scale: stackedScale)
                    graphRegion(session)
                }
                controls(session, narrow: narrowDock)
            }
        }
    }

    /// The wide window (an iPad in landscape, a foldable opened flat): the graph fills
    /// the whole screen and the panel and dock sit in the two left corners at the
    /// phone's width, so the curve's history slides under the glass and its newest
    /// seconds run in the clear on the right (Nuri, 2026-09-19).
    ///
    /// No third NEXT card between them: over a live graph every glass surface hides
    /// the curve, and the rest panel already names the next hand and grip.
    private func wideContent(_ session: RunnerSession) -> some View {
        GlassEffectContainer(spacing: 24) {
            HStack(alignment: .top, spacing: Metrics.spacing) {
                VStack(spacing: 16) {
                    if timerOnly {
                        VStack(spacing: 14) {
                            timerOnlyIdentity(session, scale: Self.wideScale)
                            timerProgress(session)
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

    // MARK: - The far view: a phone on its side, read from the floor

    /// **Nightstand mode** (docs/IPHONE_DUO_NIGHTSTAND.md) — compact HEIGHT, which is a
    /// phone in landscape and, the case it was built for, an iPhone Duo stood up like a
    /// tent under the hangboard with its outer display facing the climber.
    ///
    /// Read from one to two metres with chalk on your hands, so the FACE carries one
    /// number and one word: the phase word, the countdown as large as the height allows,
    /// the grip small above them, the time bar under them, and the trace and target lane
    /// running behind it all. Everything you touch — the kilograms, where you are, and
    /// the dock — moves to a column on the trailing edge, beside the system's vertical
    /// bars. Nothing you would touch mid-hang lives in the far view.
    ///
    /// Built from the runner's own parts (`nameRow`, `prompt`, `RunnerTimeBar`,
    /// `counters`, `controls`), never a lookalike of them.
    private func farContent(_ session: RunnerSession, columnWidth: CGFloat,
                            columnLeading: Bool) -> some View {
        // Blend distance UNDER the 12 pt gap between panel and dock: at the stacked
        // layout's 24 the two surfaces, stacked this close, melted into one blob with a
        // waist (measured, Duo outer display).
        GlassEffectContainer(spacing: 6) {
            HStack(alignment: .top, spacing: Metrics.spacing) {
                // The column sits BESIDE THE SYSTEM'S VERTICAL BARS, whichever edge they
                // are on: on the Duo's outer display they are trailing in one landscape
                // and LEADING in the other (measured: insets 0/84, then 84/0). Where the
                // bars are is where the system puts what you touch; the face takes the
                // clear side. Symmetric insets (a Pro Max on its side) keep it trailing.
                if columnLeading { farColumnSlot(session, width: columnWidth) }
                farFace(session)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if !columnLeading { farColumnSlot(session, width: columnWidth) }
            }
        }
    }

    private func farColumnSlot(_ session: RunnerSession, width: CGFloat) -> some View {
        farColumn(session)
            .frame(width: width)
            .frame(maxHeight: .infinity, alignment: .top)
    }

    /// The column's width: 45 % of the safe width, so the glass grows into the room the
    /// screen has instead of leaving it empty beside the system's bars (Nuri's Duo,
    /// 2026-10-31: a fixed ~250 left 105 pt of nothing on the right). 250 is the floor
    /// because at 220 the Tare button's "Pulling" state wrapped to "Pullin / g"
    /// (measured, Duo outer display); the Duo's 594 pt landscape safe width still leaves
    /// the face ~300 pt for a two-digit numeral.
    ///
    /// At accessibility sizes the column takes HALF the width: it carries every word and
    /// action at the person's own size (and scrolls), while the face's numeral is
    /// display-size whatever the setting.
    private func farColumnWidth(for width: CGFloat) -> CGFloat {
        max(Self.farColumnMin, width * (typeSize.isAccessibilitySize ? 0.5 : 0.45))
    }
    private static let farColumnMin: CGFloat = 250

    /// The face: grip, word, numeral, bar — top to bottom, no glass. The numeral takes
    /// every point the others leave.
    ///
    /// **The face stops growing at xxxLarge** (the column does not): its word and numeral
    /// are display-size at every setting, and at AX3 the grip row and phase word grew
    /// until the numeral had a third of the face (measured, Duo outer display). Every
    /// word and action stays at the person's own size in the column, which scrolls.
    /// The two sizes RunnerView scales itself (`promptSize`, `nameSize`) are clamped by
    /// hand — a `dynamicTypeSize` modifier reaches only the views below it.
    private func farFace(_ session: RunnerSession) -> some View {
        VStack(spacing: 6) {
            farIdentity(session)
            prompt(session, scale: Self.farPromptScale * min(1, Self.farPromptCap / promptSize),
                   singleLine: true)
            farNumeral(session)
            RunnerTimeBar(session: session, mode: RunnerTimeBar.Mode(session.snapshot.phase),
                          identity: session.snapshot.phase.slotIndex ?? -1)
                .padding(.horizontal, 8)
            if showsSignalNotice(session) {
                noSignalNotice(session)
                    .accessibilityIdentifier("runner.signalWarning")
            }
        }
        .padding(.bottom, 4)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("runner.graph")
        .runnerLayoutProbe("graph")
    }

    /// `promptSize` and `nameSize` at xxxLarge (34 → 40, 15 → 21 on the system's
    /// largeTitle and subheadline ramps): the far face's ceiling, see `farFace`.
    private static let farPromptCap: CGFloat = 40
    private static let farNameCap: CGFloat = 21

    /// The prompt draws a step larger than the wide layout's: it is the one word on the
    /// face, and the room beside the numeral is real.
    private static let farPromptScale: CGFloat = 1.6

    /// The grip, small: its glyph beside the name row (badge, name, target chip). The
    /// island hand is off on its side, so the picture has to be here.
    private func farIdentity(_ session: RunnerSession) -> some View {
        HStack(spacing: 10) {
            if let grip = session.snapshot.grip {
                RunnerGripGlyph(grip: grip, emphasized: gripEmphasis, scale: 0.7)
                    .accessibilityHidden(true)
            }
            nameRow(session, timerOnly: timerOnly, scale: min(1, Self.farNameCap / nameSize))
        }
        .frame(maxWidth: .infinity)
    }

    /// **One numeral, as large as the face's height allows.** The size is the smaller of
    /// a scaled metric and what the measured height holds — the line box of SF digits is
    /// ~1.2× the point size — so it grows with Dynamic Type until the screen, not the
    /// setting, is the limit, and `minimumScaleFactor` covers a narrow face.
    ///
    /// Medium weight, not the hero's thin: this one is read across a room, the watch
    /// face's reason for the same choice. It rolls like every clock (`RollingNumeral`,
    /// the house replacement for `.numericText()` on a countdown).
    private func farNumeral(_ session: RunnerSession) -> some View {
        GeometryReader { proxy in
            // Sized to the DIGITS, not the line box: a digit is ~0.72 em tall, so at
            // height / 0.8 it fills ~90 % of the region, and the line box's empty
            // ascender and descender space is allowed to overhang it (the text frame is
            // taller than the region, centred). Sizing by the line box (height / 1.2)
            // left the numeral at ~60 % of the room it was given (measured).
            let size = max(1, min(farHeroSize, proxy.size.height / 0.8))
            RollingNumeral(value: "\(session.snapshot.secondsShown)", countsDown: true,
                           rolls: clockRolls && !reduceMotion, shift: size * 0.25) { value in
                Text(value)
                    .lineLimit(1)
                    .minimumScaleFactor(0.3)
            }
            .font(.system(size: size, weight: .medium))
            .displayTracking(size)
            .monospacedDigit()
            .foregroundStyle(isStalled(session) ? StatusTint.armed : Ink.primary)
            .frame(width: proxy.size.width, height: size * 1.3)
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .frame(minHeight: 0, maxHeight: .infinity)
        // The numeral's band is where the trace PLOTS (`farPlot`): the curve itself is
        // the screen's, drawn full-bleed by `backgroundTrace`.
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
            traceGeometry.region = $0
        }
        // A numeral changing every second is not a VoiceOver channel; the counters and
        // the cues are — the hero's own rule.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(countdownCaption(session) ?? "")
        .accessibilityIdentifier("runner.hero")
        .runnerLayoutProbe("hero")
        .accessibilityHidden(countdownCaption(session) == nil)
    }

    /// The trailing column: what you are pulling and where you are, on one glass panel,
    /// then the dock. It SCROLLS when it has to — at accessibility sizes, or on a short
    /// screen — so every action stays reachable without shrinking anybody's type.
    private func farColumn(_ session: RunnerSession) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                farStatus(session)
                controls(session, narrow: true)
            }
            .padding(.bottom, 4)
        }
        .scrollBounceBehavior(.basedOnSize)
        // The panel's shadow is part of the surface; a scroll view clips it otherwise.
        .scrollClipDisabled()
        .accessibilityIdentifier("runner.farColumn")
        .runnerLayoutProbe("farColumn")
    }

    private func farStatus(_ session: RunnerSession) -> some View {
        VStack(spacing: 10) {
            if timerOnly {
                timerProgress(session)
            } else {
                LiveForceReadout(tint: forceTint(session),
                                 size: heroSize * 0.75, unitSize: unitSize * 0.8)
                RoutinePills(model: routineModel(session), currentTint: tint(session))
                    .accessibilityHidden(true)
                counters(session)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .accessibleGlass(nil, in: RunnerGlass.surfaceShape)
        .runnerFloatingShadow()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("runner.panel")
        .runnerLayoutProbe("panel")
    }

    /// Only while the rep is actually live. A lane drawn during the rest would ask you
    /// to hold a load you are not holding.
    private func liveTargetBand(_ session: RunnerSession) -> ClosedRange<Double>? {
        isWorking(session) || isArmed(session) ? session.snapshot.targetBand : nil
    }

    /// A connected gauge that is not sending would read "0.0 kg" — a device measuring
    /// nothing rather than an app receiving nothing. Say it, and say what to do; the
    /// notice itself is drawn by `graphRegion`.
    private func showsSignalNotice(_ session: RunnerSession) -> Bool {
        !session.snapshot.hasSignal
            || (showsRestFocus(session) && !restSignalIsAvailable(session))
    }

    /// **The countdown you can read from the wall.** While the clock is the only thing
    /// happening — the count-in, a rest, a paused rest — the seconds fill the open
    /// graph, huge and thin. It is THE rest countdown: the panel does not repeat it
    /// (see `RunnerRestFocusSummary`), so it carries `runner.restFocus.countdown` and
    /// stays accessible.
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
        // Rolls per `clockRolls`, WITHOUT `.numericText()` — see `RollingNumeral`.
        // Secondary ink at 0.75 measured above 3:1 on the light field at this size.
        RollingNumeral(value: session.snapshot.secondsShown, countsDown: true,
                       rolls: clockRolls && !reduceMotion, shift: ambientSize * 0.25) { seconds in
            Text("\(seconds)")
                .font(.system(size: ambientSize, weight: .thin))
                .displayTracking(ambientSize)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.35)
        }
            .foregroundStyle(Ink.secondary.opacity(0.75))
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
            .accessibilityIdentifier("runner.restFocus.countdown")
    }

    // MARK: - The stacked layout: the graph is the screen, the numbers are glass

    /// **The information panel** — the identity block (`measuredTop`) on one Liquid
    /// Glass surface floating over the graph (Nuri, 2026-09-19).
    ///
    /// `accessibleGlass`, never raw `.glassEffect`: under Reduce Transparency the panel
    /// becomes an opaque card, the only way the numbers stay legible over a live curve.
    /// The grip-change outline lives here because the panel is where the grip is NAMED.
    private func infoPanel(_ session: RunnerSession, scale: CGFloat = 1) -> some View {
        measuredTop(session, scale: scale)
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 12)
            .frame(maxWidth: .infinity)
            // The rim is applied BEFORE the glass: inside a `GlassEffectContainer` the
            // glass composites above anything applied after `.glassEffect`, and an
            // overlay there vanished under the material (measured in pixels).
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
            .runnerLayoutProbe("panel")
    }

    /// **The open graph** — the stretch of screen between the panel and the controls
    /// where the curve runs in the clear. It draws nothing itself: the trace is the
    /// screen's background (`backgroundTrace`), and this is the frame that PLACES the
    /// plot inside it, plus what still belongs on the graph — the no-signal notice
    /// and the `runner.graph` element the UI tests measure the layout by.
    ///
    /// NOT the grip-change chip: on an open graph it floated loose over the trace,
    /// repeating what the panel's amber rim, NEW GRIP badge and the hand already say.
    private func graphRegion(_ session: RunnerSession, wide: Bool = false) -> some View {
        let notice = showsSignalNotice(session)
        let ambient = showsAmbientCountdown(session)
        let core = ZStack {
            Color.clear
            // Both at once when the gauge goes quiet mid-rest: the countdown is the
            // rest's own clock and does not depend on the gauge, so losing the link
            // must not hide it — it moves up and the notice takes the room below.
            VStack(spacing: 8) {
                if ambient {
                    // A whisper of scale with the fade; the cross-fade alone under
                    // Reduce Motion.
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
        return core
            .frame(minHeight: typeSize.isAccessibilitySize ? 240 : nil, maxHeight: .infinity)
            .background { regionTrace(session, wide: wide) }
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
                traceGeometry.region = $0
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("runner.graph")
            .runnerLayoutProbe("graph")
    }

    /// On the phone the trace is this region's background, stretched sideways to the
    /// screen edges but never under the panel or dock — see `backgroundTrace`. The wide
    /// layout's trace is the whole screen's background instead.
    @ViewBuilder
    private func regionTrace(_ session: RunnerSession, wide: Bool) -> some View {
        if !timerOnly, !wide {
            liveTrace(session, plot: ForceTraceView.PlotInsets(top: 12, bottom: 6, trailing: 8))
                .padding(.horizontal, -Metrics.hPadding)
        }
    }

    /// The far view's plot inside its full-bleed canvas: the numeral's vertical band
    /// (the clearances the numeral-sized trace used to have), its head stepped in to the
    /// face's trailing edge. Until both are measured, the wide layout's insets.
    private var farPlot: ForceTraceView.PlotInsets {
        let canvas = traceGeometry.canvas, band = traceGeometry.region
        guard canvas.width > 0, band.width > 0, band.height > 0 else {
            return ForceTraceView.PlotInsets(top: 44, bottom: 36, trailing: 8)
        }
        return ForceTraceView.PlotInsets(top: max(0, band.minY - canvas.minY) + 12,
                                         bottom: max(0, canvas.maxY - band.maxY) + 6,
                                         trailing: max(8, canvas.maxX - band.maxX + 8))
    }

    /// The graph's canvas with its phase-tint blend, placed by the caller: the open
    /// region's background on the phone, the whole screen's on the iPad.
    private func liveTrace(_ session: RunnerSession, plot: ForceTraceView.PlotInsets) -> some View {
        LiveTrace(thresholdKg: session.plan.thresholdKg,
                  targetBand: liveTargetBand(session),
                  tint: tint(session),
                  plot: plot,
                  lit: true)
            // The wash cross-fades between phases, so the trace blends on the same house
            // curve rather than cutting colour — on an object this size a cut is a jolt.
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

    /// **The trace as the SCREEN, not a card** — the stacked layout's graph.
    ///
    /// The canvas runs edge to edge, under the status bar, the panel and the controls;
    /// the PLOT inside it is placed by the glass it runs beneath, see
    /// `BackgroundTraceGeometry`. Still the `LiveTrace` leaf, reading the store one
    /// level down so a sample invalidates nothing but the canvas.
    @ViewBuilder
    private func backgroundTrace(_ session: RunnerSession, layout: RunnerLayout) -> some View {
        if case .far = layout {
            // THE FAR VIEW: the phase colour washes the WHOLE face — from two metres the
            // colour is read before the word — and the trace is the SCREEN, as on the
            // iPad (Nuri's Duo, 2026-10-31: confined to the numeral's width it left the
            // room beside it empty). Full-bleed sideways, into the system columns and
            // under the glass; vertically it plots in the numeral's band, so the line
            // never crosses the phase word or the time bar, and its head stops at the
            // face's trailing edge, newest seconds in the clear (`farPlot`). Timer-only
            // keeps the wash: the colour is the far view's first signal, gauge or not.
            ZStack {
                PhaseWash(tint: tint(session), edge: .top, length: traceGeometry.canvas.height)
                if !timerOnly {
                    liveTrace(session, plot: farPlot)
                }
            }
            // Measured INSIDE `ignoresSafeArea`, as below.
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: {
                traceGeometry.canvas = $0
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
        } else if !timerOnly {
            let wide = layout == .wide
            // On the PHONE only the wash lives under the glass: the trace draws in the
            // open region (`graphRegion`), and the panel and dock sit on a fill that
            // changes once per phase. On the iPad the trace IS the screen and runs under
            // the glass column (Nuri, 2026-09-19). iOS re-blurs a glass backdrop every
            // frame the layer beneath changes; the iPad pays that and is measured for it.
            ZStack {
                PhaseWash(tint: tint(session),
                          edge: wide ? .leading : .top,
                          length: wide ? traceGeometry.regionLeadingInCanvas
                                       : traceGeometry.regionTopInCanvas)
                if wide {
                    liveTrace(session, plot: ForceTraceView.PlotInsets(top: 44, bottom: 36, trailing: 8))
                }
            }
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
    /// The time bar, the routine and the counters stay where they are through a long
    /// rest — that is when the whole routine is worth reading — and only the block above
    /// them hands over to the rest summary.
    private func measuredTop(_ session: RunnerSession, scale: CGFloat = 1) -> some View {
        let focused = showsRestFocus(session)
        return Group {
            if !(focused && typeSize.isAccessibilitySize) {
                measuredTopContents(session, scale: scale, focused: focused)
            } else {
                // Accessibility sizes reflow the rest into the summary alone. The routine
                // rides above the summary's own compact counts — re-stacking the full
                // labels row here cost 55 pt at AX3 (measured).
                RunnerRestFocusSummary(snapshot: session.snapshot, showsGlyph: !islandHandShown,
                                       showsCounts: true,
                                       progressRow: AnyView(routineAtRest(session)))
            }
        }
        .animation(Motion.state(reduceMotion), value: focused)
    }

    /// The panel's rhythm, grouping by proximity: the time bar sits CLOSE under the hero
    /// — it belongs to the seconds — then a clear gap, then the pills sitting TIGHT on
    /// the labels, which read as one group.
    static let heroToTimeBar: CGFloat = 8
    static let timeBarToPills: CGFloat = 11
    static let pillsToLabels: CGFloat = 4

    /// One bar, always there: the hold while pulling, the rest's countdown while
    /// resting — see `RunnerTimeBar`. Then the whole routine as pills. Same rows in every
    /// phase, so the pills and labels never move between pull and rest.
    private func routineLine(_ session: RunnerSession) -> some View {
        let phase = session.snapshot.phase
        return VStack(spacing: Self.timeBarToPills) {
            RunnerTimeBar(session: session, mode: RunnerTimeBar.Mode(phase),
                          identity: phase.slotIndex ?? -1)
            RoutinePills(model: routineModel(session), currentTint: tint(session))
        }
        // The panel stack's 12 pt either side is replaced by the rhythm above.
        .padding(.top, Self.heroToTimeBar - 12)
        .padding(.bottom, Self.pillsToLabels - 12)
        .accessibilityHidden(true)
    }

    /// The routine alone, as it reads during a rest — for the accessibility-size summary.
    private func routineAtRest(_ session: RunnerSession) -> some View {
        RoutinePills(model: routineModel(session), currentTint: tint(session))
            .accessibilityHidden(true)
    }

    private func routineModel(_ session: RunnerSession) -> SessionProgressModel {
        SessionProgressModel(slots: session.runner.slots, results: session.runner.results)
    }

    private func measuredTopContents(_ session: RunnerSession, scale: CGFloat = 1,
                                     focused: Bool = false) -> some View {
        VStack(spacing: 12 * scale) {
            // The identity block is its own stack, at the SAME spacing, so the rest
            // summary can cover exactly it and leave the rows below in place.
            // `.hidden()`, not opacity plus `accessibilityHidden`: that pair left the
            // live weight and the pull prompt in the accessibility tree under the rest
            // summary. Hidden keeps the geometry and removes both.
            Group {
                if focused {
                    identityBlock(session, scale: scale).hidden()
                } else {
                    identityBlock(session, scale: scale)
                }
            }
            .overlay {
                if focused {
                    RunnerRestFocusSummary(snapshot: session.snapshot, showsGlyph: !islandHandShown,
                                           scale: scale, showsCounts: false)
                        .transition(.opacity)
                }
            }
            .animation(Motion.state(reduceMotion), value: focused)
            routineLine(session)
            // The summary's badge already says REST; the row keeps its height.
            counters(session, showsPhaseWord: !focused)
        }
    }

    /// The grip, the prompt and the live weight — what the rest summary stands in for.
    private func identityBlock(_ session: RunnerSession, scale: CGFloat) -> some View {
        VStack(spacing: 12 * scale) {
            if islandHandShown {
                gripLineText(session)
            } else {
                gripLine(session, scale: scale)
            }
            prompt(session, scale: scale)
            hero(session, scale: scale)
        }
    }

    /// Set and pull as NUMERALS you can read with the phone on the floor (tester,
    /// 2026-09-30: "very hard to see and keep track of set progress"). The 14 pt small-caps
    /// sentences became a small label beside a `.title` number — the row grows by about 16 pt,
    /// taken from the trace's headroom, which is mostly empty above the line.
    ///
    /// The middle slot is the rest countdown during a rest, as before. PULL keeps its
    /// session-wide meaning ("Pull 17 of 36").
    private func counters(_ session: RunnerSession, showsPhaseWord: Bool = true) -> some View {
        let middle = counterMiddle(session, showsPhaseWord: showsPhaseWord)
        return ViewThatFits(in: .horizontal) {
            HStack(alignment: .bottom, spacing: 8) {
                countBlock(label: String(localized: "Set"), value: setValue(session), alignment: .leading, labelAfter: true)
                Spacer(minLength: 0)
                middle
                Spacer(minLength: 0)
                countBlock(label: String(localized: "Pull"), value: pullValue(session), alignment: .trailing)
            }
            VStack(spacing: 6) {
                middle
                HStack(alignment: .bottom, spacing: 8) {
                    countBlock(label: String(localized: "Set"), value: setValue(session), alignment: .leading, labelAfter: true)
                    Spacer(minLength: 0)
                    countBlock(label: String(localized: "Pull"), value: pullValue(session), alignment: .trailing)
                }
            }
            // The last resort, for a column narrower than both numerals side by side —
            // the far view's at accessibility sizes, where the pair overflowed the glass
            // (measured, Duo outer display at AX3). Only reached where nothing above fits.
            VStack(spacing: 6) {
                middle
                countBlock(label: String(localized: "Set"), value: setValue(session), alignment: .leading, labelAfter: true)
                countBlock(label: String(localized: "Pull"), value: pullValue(session), alignment: .trailing)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(spokenState(session))\(routineLeftSpoken(session)), \(countdownCaption(session) ?? "")")
        .accessibilityIdentifier("runner.counters")
    }

    /// A small caps label BESIDE a numeral, on its baseline. Beside, not above: stacked, the
    /// labels cost a line of their own and the graph lost 28 pt; side by side it is the
    /// numeral's height alone (Nuri, 2026-09-30). The numeral is `.title` — a semantic style,
    /// so it follows Dynamic Type — with the monospaced digits every changing number uses.
    ///
    /// `labelAfter` mirrors SET: its label follows the numeral, so both edge numerals sit
    /// flush against the panel, under the ends of the progress bar, with the labels facing
    /// the centre (Nuri, 2026-09-30). From the floor the eye finds them in the same place.
    private func countBlock(label: String, value: String, alignment: HorizontalAlignment,
                            labelAfter: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            if !labelAfter { CapsLabel(label, size: 12) }
            Text(value)
                .font(.system(.title, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(Ink.primary)
                .contentTransition(.numericText())
            if labelAfter { CapsLabel(label, size: 12) }
        }
        .fixedSize()
    }

    /// The rest countdown while resting; otherwise the same slot, reserved and hidden, so
    /// nothing moves the graph when a rest begins. (A per-hand "RIGHT HAND 2/3" count lived
    /// here for one build and was cut, 2026-09-30: it made the row busy for one hand mode.)
    @ViewBuilder
    private func counterMiddle(_ session: RunnerSession, showsPhaseWord: Bool) -> some View {
        if case .resting = session.snapshot.phase {
            restPhaseLabel(session).opacity(showsPhaseWord ? 1 : 0)
        } else {
            restPhaseLabel(session).opacity(0)
        }
    }

    private func setValue(_ session: RunnerSession) -> String {
        guard let set = session.snapshot.setNumber else { return "–" }
        return "\(set)/\(session.snapshot.setCount)"
    }

    private func pullValue(_ session: RunnerSession) -> String {
        "\(session.snapshot.pullPosition)/\(session.snapshot.plannedRepCount)"
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

    /// The pills draw the whole routine, so the counters' spoken line gains what they
    /// show and the words do not: how much is left.
    private func routineLeftSpoken(_ session: RunnerSession) -> String {
        let left = session.snapshot.plannedRepCount - session.snapshot.completedRepCount
        return left == 1 ? ", 1 pull left" : ", \(max(0, left)) pulls left"
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
    /// Which fingers go on the edge is the one instruction you act on with chalk on
    /// your hands (Nuri, 2026-08-08); the words are the confirmation. So the picture
    /// gets its own centred line and the sentence sits under it. The height comes out
    /// of the force trace — a graph is worth less than knowing which hand shape to make.
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
    /// The snapshot looks forward while resting (`SessionRunner.displaySlot`); "Next"
    /// makes that change of meaning legible.
    @ViewBuilder
    private func nameRow(_ session: RunnerSession, timerOnly: Bool = false,
                         scale: CGFloat = 1) -> some View {
        if let grip = session.snapshot.grip {
            if timerOnly {
                VStack(spacing: 6) {
                    // The SAME badge as the measured layout (see `restBadge`). Its HEIGHT
                    // is reserved, not conditional: a badge that exists only during rest
                    // changes this line's height at every REST→WORK boundary and shifts
                    // the dial under the climber's eye. Only the height — reserving its
                    // WIDTH too pushed the grip name off centre whenever no badge showed
                    // (Nuri, 2026-09-28), so the measured layout's ZStack is used here.
                    ZStack {
                        badgeCapsule(String(localized: "New grip"), changing: true)
                            .hidden().accessibilityHidden(true)
                        HStack(spacing: 8) {
                            if session.snapshot.newGripID != nil {
                                badgeCapsule(String(localized: "New grip"), changing: true)
                            } else if isResting(session) {
                                restBadge(session)
                            }
                            Text(gripDisplayName(session, grip: grip))
                                .font(.system(size: nameSize * scale, weight: .medium))
                                .foregroundStyle(Ink.secondary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                    }
                    // `ViewThatFits`, not a plain `HStack`: at accessibility sizes an
                    // HStack squeezes the TEXT INSIDE each capsule rather than wrapping
                    // whole chips to the next row.
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
                    // Reserve the rest badge's height in every phase, or its padding
                    // moves the graph a few points when REST becomes PULL.
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
    /// grip, the whole cue that it is (a grip change between sets is easy to miss while
    /// you shake out). Amber means "waiting on you"; the WORD changes with the colour,
    /// so the cue survives greyscale and colourblindness.
    ///
    /// A SOLID amber capsule with fixed dark ink, not amber TEXT: `StatusTint.armed`
    /// glyphs on the light field measured 1.72:1 against a 4.5:1 floor. The filled
    /// capsule is ~7:1, and both colours are fixed literals so it cannot move with the
    /// scheme.
    private func restBadge(_ session: RunnerSession) -> some View {
        let changing = session.snapshot.gripChangesNext
        // Its OWN key rather than the generic "Next" button word: this is a runner STATE
        // word, and a language may need a shorter one here (fr "Suite", not "Suivant").
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
    /// `singleLine` is the far view's: on a landscape face every line the word wraps onto
    /// comes out of the numeral, so it scales down instead, whatever the text size.
    private func prompt(_ session: RunnerSession, scale: CGFloat = 1,
                        singleLine: Bool = false) -> some View {
        let wraps = typeSize.isAccessibilitySize && !singleLine
        return ZStack {
            // Keep the phase label's line height when a longer translated instruction
            // scales to fit, so the graph cannot move at REST→PULL or REST→PAUSED.
            Text("REST").hidden().accessibilityHidden(true)
            Text(measuredPromptText(session))
        }
            .font(.system(size: promptSize * scale, weight: .heavy))
            .foregroundStyle(tint(session))
            .lineLimit(wraps ? nil : 1)
            .minimumScaleFactor(wraps ? 1 : (singleLine ? 0.35 : 0.6))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: wraps)
            .frame(maxWidth: .infinity)
            // The one decision-critical word — PULL, RE-GRIP, EASE OFF, LET GO, PAUSED —
            // must reach VoiceOver: `spokenState` never speaks the phase, and the cues
            // cannot stand in (`.dropoutWarning` is the same tone for RE-GRIP and EASE
            // OFF; pause/resume emit none).
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
    /// gathers the facts. `timerOnly` makes the first second GET READY, not CONNECTING.
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
        // A gauge-free session is never "disconnected" — painting REST in alarm red for
        // a device nobody asked for would be an alarm about the app's own choice.
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
    /// The force tells you whether to pull harder or ease off, the clock whether to hang
    /// on, and you need both at the same moment.
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
            }
        }
        .frame(maxWidth: .infinity)
        // A numeral changing 80×/second is unusable under VoiceOver; the cue sounds and
        // the counters row are the accessible channel.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(countdownCaption(session) ?? "")
        .accessibilityIdentifier("runner.hero")
        .runnerLayoutProbe("hero")
        .accessibilityHidden(showsRestFocus(session) || countdownCaption(session) == nil)
    }

    private func countdownCaption(_ session: RunnerSession) -> String? {
        guard session.snapshot.nextRestHand != nil else { return nil }
        if session.snapshot.phase.isPaused { return nil }
        return session.snapshot.isSetBreak ? String(localized: "SET BREAK") : String(localized: "REST")
    }

    /// `rolls` is the difference between a CLOCK and a MEASUREMENT.
    ///
    /// A countdown rolls because it is counting. The force readout is REPORTING at ~10
    /// updates a second, where the same animation is a permanent blur, so it snaps.
    private func readout(value: String, unit: String, tint: Color,
                         rolls: Bool, caption: String? = nil, scale: CGFloat = 1) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 4) {
            ZStack(alignment: Alignment(horizontal: .center, vertical: .lastTextBaseline)) {
                // Reserve the numeral's line height when horizontal pressure scales it
                // down, so a translated caption cannot move the graph on pause.
                Text("0").hidden().accessibilityHidden(true)
                // A clock rolls without `.numericText()` — see `RollingNumeral`.
                RollingNumeral(value: value, countsDown: true, rolls: rolls,
                               shift: heroSize * scale * 0.25) { value in
                    Text(value)
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                }
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
    /// **A FIXED set of chips for the whole SESSION, not the current slot — that is
    /// what stops the dial moving.** A chip count that changes at a phase boundary can
    /// flip `ViewThatFits` between candidates, changing the identity block's height and
    /// shifting the dial under the climber's eye every rep. So `Next` lives on the grip
    /// line as a reserved badge (see `nameRow`), and if any slot has a target the chip
    /// is always laid out, merely invisible where this slot has none. A plan with no
    /// targets reserves nothing.
    @ViewBuilder
    private func timerChips(_ session: RunnerSession) -> some View {
        let bands = sessionTargetBands(session)
        if !bands.isEmpty {
            // **Every slot's chip occupies the widest width this session can produce**,
            // or different-width bands could still pick different `ViewThatFits`
            // candidates. Every band is laid out HIDDEN and the ZStack takes the largest —
            // no guessing by `upperBound`, which gets it wrong ("0.0–120.0 kg" is narrower
            // than "100.0–110.0 kg").
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
    /// Resolved, not `plan.sets`: the raw plan misses the plan-level percent band the
    /// engine resolves onto untargeted sets. These are the slots the snapshot publishes,
    /// so the reservation cannot disagree with what is drawn.
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
        if islandHandShown {
            gripLineText(session, timerOnly: true)
        } else {
            gripLine(session, timerOnly: true, scale: scale)
        }
    }

    /// **Where you are, sized for the room the missing graph left** (Nuri, 2026-09-28).
    /// The connected runner's own vocabulary — the whole routine as pills, then SET and
    /// PULL — scaled up: thicker pills and figures you can read from the floor. No time
    /// bar: the dial above already is one.
    private func timerProgress(_ session: RunnerSession) -> some View {
        let snapshot = session.snapshot
        return VStack(spacing: 14) {
            RoutinePills(model: routineModel(session), currentTint: tint(session),
                         thickness: Self.timerPillThickness)
            HStack(alignment: .lastTextBaseline, spacing: 12) {
                positionFigure(String(localized: "Set"), value: snapshot.setNumber ?? snapshot.setCount,
                               of: snapshot.setCount, alignment: .leading)
                Spacer(minLength: 0)
                positionFigure(String(localized: "Pull"), value: snapshot.pullPosition,
                               of: snapshot.plannedRepCount, alignment: .trailing)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(setLine(session)), \(pullLine(session))\(routineLeftSpoken(session))")
        .accessibilityIdentifier("runner.counters")
    }

    static let timerPillThickness: CGFloat = 8

    /// "SET" over "2 / 4": the count you are on, large, and the total beside it, quieter.
    private func positionFigure(_ title: String, value: Int, of total: Int,
                                alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 2) {
            CapsLabel(title, size: 14, tint: Ink.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(verbatim: "\(value)")
                    .font(.system(size: figureSize, weight: .semibold))
                    .displayTracking(figureSize)
                    .foregroundStyle(Ink.primary)
                    // A position counts, so it rolls.
                    .contentTransition(.numericText())
                Text(verbatim: "/ \(total)")
                    .font(.system(size: figureSize * 0.5, weight: .medium))
                    .foregroundStyle(Ink.secondary)
            }
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .animation(Motion.state(reduceMotion), value: value)
        }
    }

    /// The gauge-free hero: the countdown numeral and the phase's remaining time are one
    /// object, because proximity is the mapping that makes a timer readable at a glance.
    ///
    /// The ring depletes per PHASE, not per rep (`repProgress` is hold-only and zero
    /// through lead-in and rest), on the same countdown clock as the numeral, so the two
    /// cannot drift.
    private func timerDial(_ session: RunnerSession) -> some View {
        let lineWidth: CGFloat = isTimerWorking(session) ? 12 : 7

        return ZStack {
            LiveTimerRing(session: session, lineWidth: lineWidth, tint: tint(session))
            VStack(spacing: 2) {
                // A clock rolls without `.numericText()` — see `RollingNumeral`.
                RollingNumeral(value: session.snapshot.secondsShown, countsDown: true,
                               rolls: clockRolls, shift: heroSize * 0.25) { seconds in
                    Text("\(seconds)")
                        .font(.system(size: heroSize, weight: .thin))
                        .displayTracking(heroSize)
                        .monospacedDigit()
                        .minimumScaleFactor(0.55)
                        .lineLimit(1)
                }
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
        // accessibility sizes 240 becomes ~600 — wider than the screen. `aspectRatio(.fit)`
        // keeps it circular inside whatever it is offered, so it shrinks rather than
        // clipping off both edges.
        .frame(maxWidth: dialDiameter, maxHeight: dialDiameter)
        .aspectRatio(1, contentMode: .fit)
        // **THE COLUMN MUST STILL FILL THE SCREEN**, as the open graph's `maxHeight:
        // .infinity` does in the measured layout. Otherwise the column hugs its content
        // and centres, leaving a dead band on top and the island hand (pinned to the top
        // of the CONTENT) landing on the grip line.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(Motion.state(reduceMotion), value: session.snapshot.phase)
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
            String(localized: "Connected, but no readings yet. Tap Wake.")
        }
    }

    /// Names the gauge actually selected, and never promises a pairing to a broadcast
    /// scale — the app only listens for its advertisements.
    private var connectHint: String {
        if device.canCancelBroadcastSearch { return device.state.label }
        return device.gaugeCapabilities.isBroadcast
            ? String(localized: "Tap Connect to listen for your \(device.gaugeKind.displayName).")
            : String(localized: "Tap Connect to pair with your \(device.gaugeKind.displayName).")
    }

    // MARK: - Controls

    /// **The dock** — the five actions on ONE glass surface, the way iOS 26 draws a
    /// toolbar. Each action sits in a quiet ink well rather than its own glass: glass
    /// on glass is the layering Liquid Glass asks you not to do. Hold to end keeps its
    /// red fill, as a toolbar's one destructive action would.
    ///
    /// `narrow` is the far view's column and a Split View half: Hold to end takes a row
    /// of its own, because three-up at ~250–270 pt wrapped "Skip pull" and truncated
    /// "Hold to end" to "Hold…" (measured: the Duo's outer display on its side, and the
    /// 313 pt half of the inner one) — and the one destructive action is the last thing
    /// that may lose its words.
    ///
    /// **Every capsule label stays on ONE line** (`oneLinePerCell`, 2026-10-31): a row
    /// whose labels would wrap or truncate drops to two columns — Hold to end then takes
    /// its own row — and then one. At XXXL the three-up row had truncated "Hold t…"
    /// beside a two-line "Skip / pull" (13 mini and 17 Pro). An icon steps aside before
    /// any of that (`DockActionLabel`).
    private func controls(_ session: RunnerSession, narrow: Bool = false) -> some View {
        VStack(spacing: Self.dockSpacing) {
            AdaptiveActionRow(spacing: Self.dockSpacing, oneLinePerCell: true) {
                pauseButton(session, docked: true)
                gaugeButton(session, docked: true)
            }
            if narrow {
                AdaptiveActionRow(spacing: Self.dockSpacing, oneLinePerCell: true) {
                    skipButtons(session, docked: true)
                }
                AdaptiveActionRow(spacing: Self.dockSpacing, oneLinePerCell: true) {
                    endButton(session)
                }
            } else {
                AdaptiveActionRow(spacing: Self.dockSpacing, oneLinePerCell: true) {
                    skipButtons(session, docked: true)
                    endButton(session)
                }
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
        .runnerLayoutProbe("dock")
    }

    private static let dockSpacing: CGFloat = 8

    /// The buttons KEEP their labels while disabled: the visible reason is the prompt
    /// above (PAUSED / CONNECTING), and swapping labels left VoiceOver reading "Paused,
    /// dimmed" twice with no way to tell the Skips apart. The sentence rides the hint.
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

    /// A dock action: FULL-WIDTH in its slot. `SecondaryGlassButton` hugs its label,
    /// and three hugging buttons in one row truncated "Pause" to "Pa…". Glass INSIDE
    /// the label, then the content shape, then the style outside.
    ///
    /// `enabled`/`disabledReason` dim AND disable, with the reason as the accessibility
    /// hint; the label is never swapped (see `pauseButton`).
    private func dockButton(_ title: String, systemImage: String? = nil,
                            tint: Color = Ink.primary,
                            enabled: Bool = true, disabledReason: String? = nil,
                            docked: Bool = false,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            DockActionLabel(title: title, systemImage: systemImage)
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

/// Which of the runner's three layouts is on screen — chosen by size class and aspect
/// in `RunnerView.live(_:)`, never by the idiom.
private enum RunnerLayout: Equatable {
    /// The phone's column: identity, graph, controls, top to bottom.
    case stacked
    /// Regular width and wider than tall: the glass column left, the graph the screen.
    case wide
    /// Compact HEIGHT — a phone on its side: the far view, the controls in a column of
    /// this width beside the system's vertical bars (`columnLeading` when they are on the
    /// leading edge).
    case far(Far)

    struct Far: Equatable {
        var columnWidth: CGFloat
        var columnLeading: Bool
        /// Zero on a side the system already margins with its own column.
        var leadingPadding: CGFloat
        var trailingPadding: CGFloat
    }

    var far: Far? {
        if case .far(let far) = self { far } else { nil }
    }
}
