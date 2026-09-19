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
    @ScaledMetric(relativeTo: .largeTitle) private var heroSize: CGFloat = 54
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The face turns upside down on the watch hand's pulls — see `FaceFlipPolicy`. Off
    /// for anyone whose block posture is different; device-local, like every preference.
    @AppStorage("watch.flipForWatchHand") private var flipsForWatchHand = true

    /// Which wrist this watch is on, as the wearer told watchOS. `.both` is never a wrist.
    private var wrist: Side {
        WKInterfaceDevice.current().wristLocation == .right ? .right : .left
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

    private func face(_ session: RunnerSession) -> some View {
        let snapshot = session.snapshot
        let tint = tint(session)
        let flipped = FaceFlipPolicy.shouldFlip(phase: snapshot.phase, side: snapshot.side,
                                                wrist: wrist, enabled: flipsForWatchHand)
        return VStack(spacing: 2) {
            Text(promptText(session))
                .font(.headline.weight(.heavy))
                .foregroundStyle(tint)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text("\(snapshot.secondsShown)")
                .font(.system(size: heroSize, weight: .thin, design: .rounded))
                .monospacedDigit()
                .contentTransition(.numericText())
                .animation(Motion.live, value: snapshot.secondsShown)
            if let grip = snapshot.grip {
                HStack(spacing: 8) {
                    HandMark(fingers: grip.fingers, position: grip.position,
                             side: snapshot.side ?? .both, barWidth: 7, tint: tint)
                    Text(grip.shortName)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            Text(positionLine(session))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .monospacedDigit()
            if !timerOnly {
                WatchLiveForce(tint: forceTint(session), hasSignal: snapshot.hasSignal)
            }
            if keeper.state == .denied || keeper.state == .unavailable {
                // Said plainly: without a workout session the app sleeps with the
                // wrist, and a rep would stall in silence.
                Text("Keep the screen on — Health didn't allow a workout.")
                    .font(.caption2)
                    .foregroundStyle(StatusTint.armed)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 4)
        // UPSIDE DOWN for the watch hand's pulls: palm down on a block in front of you,
        // the wrist is under your eyes with 12 o'clock at your elbow. The turn itself is
        // the cue that this pull is the watch hand's; the house curve, so it is one
        // motion and not a spin.
        .rotationEffect(.degrees(flipped ? 180 : 0))
        .animation(Motion.state(reduceMotion), value: flipped)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("watch.face")
    }

    private func promptText(_ session: RunnerSession) -> String {
        let snapshot = session.snapshot
        if case .resting = snapshot.phase, let nextHand = snapshot.nextRestHandPrompt { return nextHand }
        switch snapshot.phase {
        case .idle: return device.state.isConnected || timerOnly ? String(localized: "GET READY") : String(localized: "CONNECTING")
        case .leadIn: return String(localized: "GET READY")
        case .armed: return String(localized: "\(snapshot.side?.prompt ?? "") — PULL")
        case .working:
            if snapshot.isDropped { return String(localized: "RE-GRIP") }
            if snapshot.isOverTarget { return String(localized: "EASE OFF") }
            return snapshot.side?.prompt ?? ""
        case .releasing: return String(localized: "LET GO")
        case .resting: return snapshot.isSetBreak ? String(localized: "SET BREAK") : String(localized: "REST")
        case .paused: return String(localized: "PAUSED")
        case .finished: return String(localized: "DONE")
        }
    }

    /// Which pull you are ON, not how many you have completed — the phone's rule.
    private func positionLine(_ session: RunnerSession) -> String {
        let snapshot = session.snapshot
        let planned = snapshot.plannedRepCount
        let position = min(snapshot.completedRepCount + 1, planned)
        guard let set = snapshot.setNumber else { return String(localized: "Pull \(position) of \(planned)") }
        return String(localized: "Set \(set) of \(snapshot.setCount) · Pull \(position) of \(planned)")
    }

    /// The phone's ladder: blue while the clock runs, amber while it waits on you, red
    /// when something needs attention, steel while resting.
    private func tint(_ session: RunnerSession) -> Color {
        if !timerOnly, !device.state.isConnected || session.snapshot.linkIsDown {
            return StatusTint.alarm
        }
        switch session.snapshot.phase {
        case .working: return isStalled(session) ? StatusTint.armed : StatusTint.engaged
        case .armed, .releasing, .paused: return StatusTint.armed
        case .resting, .leadIn, .idle, .finished: return StatusTint.calm
        }
    }

    private func forceTint(_ session: RunnerSession) -> Color {
        guard case .working = session.snapshot.phase else { return .primary }
        return isStalled(session) ? StatusTint.armed : StatusTint.engaged
    }

    private func isStalled(_ session: RunnerSession) -> Bool {
        session.snapshot.isDropped || session.snapshot.isOverTarget
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

/// The live reading, in its own leaf: `currentKg` moves at sample rate, and read from
/// the face it would rebuild the whole page to move one number.
private struct WatchLiveForce: View {
    @Environment(DeviceStore.self) private var device
    var tint: Color
    var hasSignal: Bool

    var body: some View {
        Text(hasSignal ? WeightUnit.kg.text(device.currentKg) : String(localized: "waiting for the gauge"))
            .font(.caption)
            .monospacedDigit()
            .foregroundStyle(hasSignal ? tint : Color.secondary)
            // A measurement snaps; only clocks roll.
            .contentTransition(.identity)
    }
}
