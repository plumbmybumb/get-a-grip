// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI
import WatchKit

/// The session on the wrist. Two pages, swiped vertically like the system Workout app:
/// the FACE — phase, countdown, hand, grip — and the CONTROLS. The face is for between
/// pulls; mid-hang the watch faces the ceiling and the haptics carry the beat (see
/// `WatchCuePlayer`). No force trace: a graph nobody can look at costs battery for
/// nothing, and the kilogram readout under the hand is enough to confirm the gauge is
/// alive.
///
/// **The whole face is the colour of the state** (`WatchFaceMood`): blue to pull, green
/// while the clock runs, red to re-grip, gray to rest, orange when the next grip is a
/// different one, amber for less. A fill is the one instrument that survives Always On —
/// one redraw a second and reduced luminance — where a rolling digit turns into a smear,
/// so under reduced luminance the fill goes to its dimmed shade, every ink goes white,
/// and the clock numeral cuts instead of rolling.
///
/// It owns no timing logic — the same `RunnerSession` the phone runs holds the state
/// machine, the stream watchdog and the tare rules, and this only draws what it says.
struct WatchRunnerView: View {
    let template: SessionTemplate
    var timerOnly: Bool

    @Environment(DeviceStore.self) private var device
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Query(sort: [SortDescriptor(\MaxRecord.recordedAt)]) private var maxRecords: [MaxRecord]

    @State private var session: RunnerSession?
    @State private var keeper = WorkoutKeeper()
    @State private var readout = WatchForceReadout()
    /// Two heroes share a row now — the load and the clock — so each is a size under
    /// the old lone numeral, and both a size over what a wrist could read before.
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 44
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Always On: with the wrist out of the raise pose — palm down on a block counts —
    /// watchOS dims the display and redraws it once a second, and no app can hold full
    /// brightness (Apple's own Workout app dims the same way). What an app CAN do is
    /// stay legible dimmed: the fill drops to its dimmed shade, the numbers and the hand
    /// word go white, the small print goes, and nothing animates.
    @Environment(\.isLuminanceReduced) private var luminanceReduced
    private var dimmed: Bool {
        #if DEBUG
        // Headless verification: the watch simulator has no wrist to lower, so
        // `-previewDimmed` stands in for reduced luminance. Read HERE rather than
        // overridden at the app root: a `transformEnvironment` on the root view never
        // reached this pushed destination (measured 2026-09-19 — the system writes the
        // value below it). Never in a release build.
        if ProcessInfo.processInfo.arguments.contains("-previewDimmed") { return true }
        #endif
        return luminanceReduced
    }
    /// The face turns upside down on the watch hand's pulls — see `FaceFlipPolicy`. Off
    /// for anyone whose block posture is different; device-local, like every preference.
    @AppStorage("watch.flipForWatchHand") private var flipsForWatchHand = true
    /// The mood the face is fading FROM — see `fillLayer`. The only state this view
    /// holds about its colour, and it models nothing but the last change.
    @State private var outgoingMood: WatchFaceMood = .rest

    /// Which wrist this watch is on, as the wearer told watchOS. `.both` is never a wrist.
    private var wrist: Side {
        WKInterfaceDevice.current().wristLocation == .right ? .right : .left
    }

    /// Low Power Mode takes the roll away from the clock too — see `NumeralRoll`.
    private var clockRolls: Bool {
        NumeralRoll.rolls(luminanceReduced: dimmed,
                          lowPower: PowerState.shared.isLowPowerModeEnabled)
    }

    var body: some View {
        Group {
            if let session {
                if session.isFinished {
                    WatchSummaryView(session: session, template: template) { dismiss() }
                } else {
                    TabView {
                        face(session)
                        controls(session)
                    }
                    .tabViewStyle(.verticalPage)
                }
            } else {
                ProgressView()
            }
        }
        .onAppear {
            guard session == nil else { return }
            // The maxes are read ONCE, here — a session's targets must not move under
            // the climber because a max was recorded on the phone mid-workout.
            let new = RunnerSession(template: template, device: device,
                                    maxes: maxRecords.maxTable(), timerOnly: timerOnly,
                                    cues: WatchCuePlayer())
            session = new
            new.begin()
            if !timerOnly { readout.begin(reading: device) }
            #if DEBUG
            // Headless verification: the watch simulator cannot answer the Health
            // permission sheet a workout session raises, so `-noWorkoutSession` runs
            // the session without one. Never in a release build.
            if ProcessInfo.processInfo.arguments.contains("-noWorkoutSession") { return }
            #endif
            keeper.begin()
        }
        .onDisappear {
            session?.end()
            keeper.end()
            readout.end()
        }
        // **The finish lets go of the wrist too**, not the screen's disappearance. The
        // summary can sit there with the watch on a bench for as long as it likes; the
        // workout session was keeping the app awake — and a workout running in Fitness —
        // for a session that was already over. `RunnerSession` quiesces the gauge and the
        // ticker at the same instant; see `RunnerSession.quiesce`.
        .onChange(of: session?.isFinished ?? false) { _, finished in
            guard finished else { return }
            keeper.end()
            readout.end()
        }
        .onChange(of: device.state.isConnected) { _, connected in
            session?.connectionChanged(isConnected: connected)
        }
        .onChange(of: scenePhase) { _, phase in
            // NO pause on leaving the foreground, unlike the phone: the workout session
            // keeps the process and the stream alive with the wrist down, so a rep keeps
            // counting. Coming back re-kicks the stream for the same reason the phone
            // does — the burst the radio buffered while the screen slept.
            if phase == .active { session?.startIfReady(cause: .foreground) }
        }
    }

    // MARK: - The face

    /// The face, turned a quarter toward the hand on the watch hand's pulls — see
    /// `FaceFlipPolicy`. Laid out for the canvas it will occupy AFTER the turn (the
    /// screen's height as its width), then rotated, so nothing is clipped or centred in
    /// the wrong frame. The turn SNAPS: animating a quarter-turn of the whole face
    /// stuttered on the wrist, and the haptic already marks the beat.
    ///
    /// The fill is a full-bleed background behind the content — it reaches the top edge
    /// under the clock and the bottom under the page dots, measured — and it is not
    /// turned with the face: a colour has no up. NOTHING ELSE on the face animates on a
    /// state change: the word, the hand and the counters cut on the beat, and an
    /// animation on the whole face cross-dissolved the prompt word into the next one.
    private func face(_ session: RunnerSession) -> some View {
        let snapshot = session.snapshot
        let mood = mood(session)
        let turned = FaceFlipPolicy.shouldFlip(phase: snapshot.phase, side: snapshot.side,
                                               wrist: wrist, enabled: flipsForWatchHand)
        return GeometryReader { geo in
            faceContent(session, mood: mood)
                .frame(width: turned ? geo.size.height : geo.size.width,
                       height: turned ? geo.size.width : geo.size.height)
                .rotationEffect(.degrees(turned ? FaceFlipPolicy.rotationDegrees(wrist: wrist) : 0))
                .frame(width: geo.size.width, height: geo.size.height)
        }
        .background { fillLayer(mood) }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("watch.face")
    }

    private func faceContent(_ session: RunnerSession, mood: WatchFaceMood) -> some View {
        let snapshot = session.snapshot
        let ink = ink(mood)
        let quiet = ink.opacity(0.72)
        return VStack(spacing: 4) {
            Text(promptText(session))
                .font(.title3.weight(.heavy))
                .foregroundStyle(ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            // BOTH numbers, like the phone's hero: what you are pulling and how much
            // longer. The load was a caption before, and on an Ultra it was
            // unreadable from a bench (Nuri, 2026-09-19).
            HStack(alignment: .lastTextBaseline, spacing: 10) {
                if !timerOnly {
                    hero(WeightUnit.kg.number(readout.kg), unit: WeightUnit.kg.symbol,
                         ink: snapshot.hasSignal ? ink : quiet, quiet: quiet, rolls: false)
                }
                hero("\(snapshot.secondsShown)", unit: String(localized: "s"),
                     ink: ink, quiet: quiet, rolls: clockRolls)
            }
            if let grip = snapshot.grip {
                HStack(spacing: 8) {
                    HandMark(fingers: grip.fingers, position: grip.position,
                             side: snapshot.side ?? .both, barWidth: 8, tint: ink)
                    if !dimmed {
                        Text(grip.shortName)
                            .font(.footnote)
                            .foregroundStyle(quiet)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                }
            }
            if !dimmed {
                Text(positionLine(session))
                    .font(.caption2)
                    .foregroundStyle(quiet)
                    .monospacedDigit()
            }
            if !timerOnly, !snapshot.hasSignal {
                Text("waiting for the gauge")
                    .font(.caption2)
                    .foregroundStyle(quiet)
            }
            if keeper.state == .denied || keeper.state == .unavailable {
                // Said plainly: without a workout session the app sleeps with the
                // wrist, and a rep would stall in silence.
                Text("Keep the screen on — Health didn't allow a workout.")
                    .font(.caption2)
                    .foregroundStyle(ink)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 4)
    }

    /// A numeral and its unit. `rolls` is the difference between a CLOCK and a
    /// MEASUREMENT, the phone's rule: the countdown rolls, the load snaps — and a clock
    /// stops rolling too once the face is dimmed or the battery rationed (`NumeralRoll`).
    private func hero(_ value: String, unit: String, ink: Color, quiet: Color, rolls: Bool) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 2) {
            // Rolls without `.numericText()` — see `RollingNumeral`; on the wrist the
            // blur that transition renders on the CPU would be paid out of the battery.
            RollingNumeral(value: value, countsDown: true, rolls: rolls, shift: heroSize * 0.25) { value in
                Text(value)
                    .font(.system(size: heroSize, weight: .medium, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
                .foregroundStyle(ink)
            Text(unit)
                .font(.caption2)
                .foregroundStyle(quiet)
        }
    }

    /// The next hand replaces the word during a rest — the wrist has room for one
    /// line, and which hand comes next is what it is for. Everything else is the
    /// phone's ladder, shared: `RunnerPromptWords`.
    private func promptText(_ session: RunnerSession) -> String {
        let snapshot = session.snapshot
        if case .resting = snapshot.phase, let nextHand = snapshot.nextRestHandPrompt { return nextHand }
        return RunnerPromptWords.word(phase: snapshot.phase,
                                      side: snapshot.side,
                                      isConnected: device.state.isConnected,
                                      timerOnly: timerOnly,
                                      isDropped: snapshot.isDropped,
                                      isOverTarget: snapshot.isOverTarget,
                                      isSetBreak: snapshot.isSetBreak)
    }

    /// Which pull you are ON, not how many you have completed — the phone's rule, and
    /// the phone's arithmetic (`RunnerSnapshot.pullPosition`).
    private func positionLine(_ session: RunnerSession) -> String {
        let snapshot = session.snapshot
        let planned = snapshot.plannedRepCount
        let position = snapshot.pullPosition
        guard let set = snapshot.setNumber else { return String(localized: "Pull \(position) of \(planned)") }
        return String(localized: "Set \(set) of \(snapshot.setCount) · Pull \(position) of \(planned)")
    }

    // MARK: - Colour

    /// `WatchFaceMood` decides; this only gathers what it asks. A gauge-free session is
    /// never "disconnected": there is nothing to be connected to, and painting REST red
    /// because of a device nobody asked for is the app raising an alarm about its own
    /// choice — the phone's rule.
    private func mood(_ session: RunnerSession) -> WatchFaceMood {
        let snapshot = session.snapshot
        let linkIsDown = !timerOnly && (!device.state.isConnected || snapshot.linkIsDown)
        return WatchFaceMood.resolve(phase: snapshot.phase,
                                     isDropped: snapshot.isDropped,
                                     isOverTarget: snapshot.isOverTarget,
                                     linkIsDown: linkIsDown,
                                     gripChangesNext: snapshot.gripChangesNext,
                                     newGrip: snapshot.newGripID != nil)
    }

    private func fill(_ mood: WatchFaceMood) -> Color {
        Color(hex: WatchFacePalette.colours(for: mood, dimmed: dimmed).fillHex)
    }

    /// The fill, changing with the house state curve while the wrist is up and CUTTING
    /// when dimmed: at one redraw a second a 0.3 s fade is one frame of the wrong colour,
    /// and the whole point of the fill is that it is right at a glance.
    ///
    /// An explicit cross-fade rather than an animated colour, because a `Color` view does
    /// not interpolate here (measured on the watch simulator, 2026-09-19: the fill cut in
    /// one frame while an animated transaction was plainly running around it). Each
    /// mood's fill is its own view, so a change fades the new one in and the old one
    /// out — over a BASE painted the outgoing colour. The base is what makes the blend
    /// right: SwiftUI does not promise which of the two crossing layers is on top (the
    /// first try faded one change in three), and a symmetric fade over black would dip
    /// dark halfway. Over an opaque base of the old colour, either order blends
    /// monotonically from old to new.
    private func fillLayer(_ mood: WatchFaceMood) -> some View {
        ZStack {
            fill(outgoingMood)
                .ignoresSafeArea()
            fill(mood)
                .ignoresSafeArea()
                .id(mood)
                .transition(.opacity)
        }
        .animation(dimmed ? nil : Motion.state(reduceMotion), value: mood)
        .onChange(of: mood) { old, _ in outgoingMood = old }
    }

    private func ink(_ mood: WatchFaceMood) -> Color {
        WatchFacePalette.colours(for: mood, dimmed: dimmed).inkIsWhite ? .white : .black
    }

    // MARK: - Controls

    /// End is a plain button, the way the system Workout app's is: it sits a swipe away
    /// from the face, and the summary that follows still offers Discard, so a mis-tap
    /// costs the remaining pulls and nothing already done.
    private func controls(_ session: RunnerSession) -> some View {
        let phase = session.snapshot.phase
        let skipEnabled = RunnerControlPolicy.skipEnabled(for: phase)
        return ScrollView {
            VStack(spacing: 8) {
                Button {
                    session.send(phase.isPaused ? .resume : .pause)
                } label: {
                    Label(phase.isPaused ? String(localized: "Resume") : String(localized: "Pause"),
                          systemImage: phase.isPaused ? "play.fill" : "pause.fill")
                        .frame(maxWidth: .infinity)
                }
                .disabled(!RunnerControlPolicy.pauseEnabled(for: phase))
                .accessibilityIdentifier("watch.pause")
                Button {
                    session.send(.skipRep)
                } label: {
                    Text("Skip pull").frame(maxWidth: .infinity)
                }
                .disabled(!skipEnabled)
                Button {
                    session.send(.skipSet)
                } label: {
                    Text("Skip set").frame(maxWidth: .infinity)
                }
                .disabled(!skipEnabled)
                if !timerOnly, device.state.isConnected {
                    // Unloaded only. The phone confirms a loaded tare with the reading;
                    // on a wrist there is no room for that dialog, so a loaded gauge
                    // simply cannot be zeroed from here.
                    Button {
                        session.tare()
                    } label: {
                        Text("Tare").frame(maxWidth: .infinity)
                    }
                    .disabled(!TarePolicy.phaseAllowsTare(phase) || device.isLoadedForTare)
                }
                // See `FaceFlipPolicy`. On this page, read the normal way up, because
                // the switch is for the moment the flip turns out to be wrong.
                Toggle(isOn: $flipsForWatchHand) {
                    Text("Flip for the watch hand")
                }
                .font(.footnote)
                .accessibilityIdentifier("watch.flipToggle")
                Button(role: .destructive) {
                    session.send(.abort)
                } label: {
                    Text("End session").frame(maxWidth: .infinity)
                }
                .accessibilityIdentifier("watch.end")
            }
        }
    }
}
