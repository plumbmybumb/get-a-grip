// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// THE FIRST-RUN TOUR — a spotlight walked across the real screen, not a slideshow
/// (Nuri, 2026-08-09).
///
/// **It highlights the LIVE UI.** A carousel of screenshots would teach nothing: the card
/// being described is the card you are looking at, where it will always be. Each step
/// names a `TourTarget`, every target registers its frame through a preference, and the
/// scrim punches a hole at it.
///
/// **The scrim blocks touches** (except on an interactive step — see `TourStep.interactive`).
/// That does not break the app's rule against `.popoverTip` on the control it describes,
/// where the first tap only dismisses the tip: a tour has a Next button, nothing else is
/// live, and the described thing is lit rather than offered.
enum TourTarget: String, Hashable, CaseIterable {
    case buildRoutine
    case routineCard
    case gripLadder
    case startButton
    case startWithoutGauge
    case consistency
    case builderRhythm
    case builderSets
    case builderFinish
    case runnerHand
    case runnerClock
    case runnerTrace
    case historyMonth
    case maxesCurves
}

/// The three places the tour has something to say. Each is seen or skipped on its own:
/// they happen minutes or days apart, and one Skip must not swallow the two not reached.
enum TourAct: String, Hashable {
    case intro
    case builder
    case session
}

/// One beat of the tour. `target` is nil for steps about the app rather than a control;
/// those draw a plain centred card with no hole in the scrim.
struct TourStep: Identifiable, Hashable {
    var id: String { title }
    var target: TourTarget?
    var title: String
    var body: String
    /// **The lit control stays live for this step.** The scrim's hit shape is punched with
    /// the same hole, even-odd filled, so the tap lands on the button rather than on glass
    /// over it. This is the difference between describing a control and asking you to use
    /// one; getting it wrong is the tip-over-its-own-control failure, where the first tap
    /// only dismisses and the control looks broken.
    var interactive = false
    /// The tab this step lives on. The tour SWITCHES to it: showing the calendar teaches more
    /// than pointing at a tab icon (Nuri, 2026-08-09).
    var tab: Int?
}

// MARK: - The script

extension TourStep {
    /// **Act one, on a phone with no routine yet.** On a genuine first launch Today is an
    /// empty state, and every step about the card would light a rectangle that does not
    /// exist. So it hands you to the builder and picks up again once a routine is saved — a
    /// tutorial that builds your first routine with you.
    static let firstRun: [TourStep] = [
        TourStep(
            target: nil,
            title: String(localized: "Get a Grip runs your hangboard sessions"),
            // The builder opens blank on the name field (2026-08-19); no prefill to
            // promise.
            body: String(localized: "It counts you in, times every pull, and reads your force gauge so you know what you actually held. Start by making a routine — a name and one set is enough.")),
        TourStep(
            target: .buildRoutine,
            title: String(localized: "Build one now"),
            body: String(localized: "Tap it and set the edge, which fingers, how many pulls and how long. Nothing is saved until you tap Save, and the tour carries on when you are back."),
            interactive: true),
    ]

    /// Act two, once there is something to point at. The app's own voice: second person,
    /// present tense, concrete, no exclamation marks; each step says what the thing DOES.
    static let today: [TourStep] = [
        // No opening card: arriving from the builder, the routine lit up is more
        // useful than a paragraph over it.
        TourStep(
            target: .routineCard,
            title: String(localized: "This is your routine"),
            // Short on purpose: this step lights the whole (tall) card, so a long
            // callout has nowhere to sit that does not cover it. Copy length is layout.
            body: String(localized: "One card, one ritual. The dots are today's sessions, and a filled dot is one you have done.")),
        TourStep(
            target: .gripLadder,
            title: String(localized: "What you are pulling"),
            body: String(localized: "Tap the plan to see your grips, timing and hand order. You can edit the routine from its overview.")),
        TourStep(
            target: .startButton,
            title: String(localized: "Start here"),
            body: String(localized: "Keep the gauge unloaded while Get a Grip connects and tares it. If the gauge is asleep, the app waits.")),
        TourStep(
            target: .startWithoutGauge,
            title: String(localized: "When you have no gauge"),
            body: String(localized: "Flat battery, or you left it at home. The same routine runs on the clock and the day still counts. Nothing is measured.")),
        TourStep(
            target: .consistency,
            title: String(localized: "The last fortnight"),
            body: String(localized: "Tap the days to open History. Use Log a session to add climbing or hangs done away from the gauge.")),
        TourStep(
            target: .historyMonth,
            title: String(localized: "History"),
            body: String(localized: "Every session you have done, and five weeks of them at a glance. A fuller square is a day you did more of."),
            tab: 1),
        TourStep(
            target: .maxesCurves,
            title: String(localized: "Maxes"),
            body: String(localized: "Measure again opens the gauge for this grip. Edit changes your hand values and opens earlier records. Add a max is at the bottom."),
            tab: 2),
        TourStep(
            target: nil,
            title: String(localized: "That is the tour"),
            body: String(localized: "The routine on screen is a starting point rather than a prescription. Open it and change anything; nothing is saved until you tap Save."),
            tab: 0),
    ]

    /// The routine DOCUMENT, top to bottom — the skeleton first, then the sets that
    /// inherit it. The name field is not a step: the guide's first coach card sits on it
    /// inline, and a spotlight over a text field says nothing the field does not.
    static let builder: [TourStep] = [
        TourStep(
            target: .builderRhythm,
            title: String(localized: "What every set shares"),
            body: String(localized: "The break between sets, how the hands split the work, and whether a rest waits for you to let go. Everything else lives on each set.")),
        TourStep(
            target: .builderSets,
            title: String(localized: "The sets"),
            body: String(localized: "Each set carries its own grip, pulls, hold, rest and target — drag the band to set a range; during a session the clock only runs inside it. Add a set copies the last one.")),
        TourStep(
            target: .builderFinish,
            title: String(localized: "Save when you are happy"),
            body: String(localized: "Nothing is written until you tap this — and saving never starts a session. Today is where you start."),
            interactive: true),
    ]

    /// The session screen, on the first one you run. The runner is PAUSED while this shows
    /// — see `RunnerView` — because teaching over a running clock costs you the pull.
    static let session: [TourStep] = [
        TourStep(
            target: .runnerHand,
            title: String(localized: "Which hand"),
            body: String(localized: "The big word is the hand that goes on the edge. Your fingers hang off the Dynamic Island, so the grip reads without a word.")),
        TourStep(
            target: .runnerClock,
            title: String(localized: "The clock"),
            body: String(localized: "It counts your hold down and stops if you come off the edge. Coming off never ends a pull, however long you take.")),
        TourStep(
            target: .runnerTrace,
            title: String(localized: "The lane"),
            body: String(localized: "The shaded band is the load you were asked for. Keep the line inside it and the clock runs; below says RE-GRIP and above says EASE OFF.")),
    ]
}

// MARK: - Controller

/// Owns which step is showing, and whether the tour has ever finished.
///
/// The "seen" flag is VERSIONED, not a Bool, so a bumped version can run a grown tour
/// again for people who saw the old one.
@Observable
@MainActor
final class TourController {
    /// Bump when the script changes enough to be worth showing again.
    private static let version = 1
    private static func seenKey(_ act: TourAct) -> String { "tour.seen.\(act.rawValue)" }

    /// **"Take me to that tab."** Settings sits two tabs from everything it can restart, so
    /// a reset there looked like nothing happened (Nuri, 2026-08-09). Selection lives in
    /// `RootTabView`; this is how deeper views ask for it. Cleared once honoured.
    var requestedTab: Int?

    private(set) var act: TourAct?
    private(set) var steps: [TourStep] = []
    private(set) var index = 0
    /// True while act one is finished and the tour is waiting for a routine to exist.
    /// Not persisted: a tour interrupted by quitting the app is a tour you skipped.
    private(set) var awaitingRoutine = false

    var current: TourStep? {
        guard index < steps.count else { return nil }
        var step = steps[index]
        // Intro steps without a tab live on Today; coming back from History must
        // restore it before the spotlight asks for Today's anchors.
        if act == .intro, step.tab == nil { step.tab = 0 }
        return step
    }
    var isRunning: Bool { current != nil }
    var progress: String { String(localized: "\(index + 1) of \(steps.count)") }

    /// Start the tour unless already finished or skipped. Idempotent: `onChange`/`onAppear`
    /// hooks can fire more than once, and must not restart a running act.
    func beginIfUnseen(_ act: TourAct, hasRoutine: Bool = true) {
        guard !isRunning, !awaitingRoutine else { return }
        guard UserDefaults.standard.integer(forKey: Self.seenKey(act)) < Self.version else { return }
        begin(act, hasRoutine: hasRoutine)
    }

    /// Start it regardless — what the Settings row calls.
    func begin(_ act: TourAct, hasRoutine: Bool = true) {
        self.act = act
        switch act {
        case .intro:   steps = hasRoutine ? TourStep.today : TourStep.firstRun
        case .builder: steps = TourStep.builder
        case .session: steps = TourStep.session
        }
        awaitingRoutine = act == .intro && !hasRoutine
        index = 0
    }

    /// Replay everything — the Settings row. Clearing the flags lets the builder and
    /// session acts fire again when reached.
    func replay(hasRoutine: Bool) {
        for act in [TourAct.intro, .builder, .session] {
            UserDefaults.standard.removeObject(forKey: Self.seenKey(act))
        }
        begin(.intro, hasRoutine: hasRoutine)
        // The tour starts on Today, and it is started from Settings.
        requestedTab = 0
    }

    /// The builder opened while act one was waiting for a routine, so teach it — only in
    /// that window, not when adding a grip a month later.
    func builderOpened() {
        // Act one may still be RUNNING: its last step is the hand-off that opened
        // the builder. Guarding on `!isRunning` left "Build one now" over it.
        guard awaitingRoutine, act == .intro || act == nil else { return }
        guard UserDefaults.standard.integer(forKey: Self.seenKey(.builder)) < Self.version
        else {
            // Seen already: stand down rather than leaving the intro card on top.
            steps = []
            index = 0
            return
        }
        act = .builder
        steps = TourStep.builder
        index = 0
    }

    /// A routine now exists. If the tour handed you to the builder, pick it up again with
    /// the act that has something to point at.
    func routineCreated() {
        guard awaitingRoutine else { return }
        awaitingRoutine = false
        begin(.intro, hasRoutine: true)
    }

    func advance() {
        guard index + 1 < steps.count else {
            // Act one ending is not the tour ending: wait for the builder rather than
            // marking it seen, or saving your first routine lands on an unexplained screen.
            if act == .intro, awaitingRoutine {
                steps = []
                index = 0
                return
            }
            return finish()
        }
        index += 1
    }

    func back() {
        index = max(0, index - 1)
    }

    /// Skipping counts as seen: being re-offered a tour you declined is worse than never.
    func finish() {
        if let act { UserDefaults.standard.set(Self.version, forKey: Self.seenKey(act)) }
        // Only the intro act owns `awaitingRoutine`; finishing the BUILDER act must
        // leave it, or saving the routine would not resume act one.
        if act == .intro { awaitingRoutine = false }
        act = nil
        steps = []
        index = 0
    }
}

// MARK: - Anchors

/// Every target's frame, collected as an `Anchor<CGRect>` so the overlay can convert it
/// into its own space. Not a `GeometryReader` per target, which needs a wrapper around
/// every control and reports in whatever space its parent is in.
struct TourAnchorKey: PreferenceKey {
    /// A LIST per target, not a single anchor. With last-wins, the spotlight worked on
    /// buttons and failed on the builder: the eager deck's off-screen spare card also
    /// registered the target, won, and the hole was punched off the right edge. Unique
    /// targets had one registrant and never showed it.
    ///
    /// Any list, pager or lazy stack can have two views claiming one target; the host picks
    /// the visible one.
    static let defaultValue: [TourTarget: [Anchor<CGRect>]] = [:]
    static func reduce(value: inout [TourTarget: [Anchor<CGRect>]],
                       nextValue: () -> [TourTarget: [Anchor<CGRect>]]) {
        value.merge(nextValue()) { old, new in old + new }
    }
}

extension EnvironmentValues {
    /// Whether a tour is running over this subtree — published by `tourHost`, read by
    /// every `tourAnchor`. See `TourAnchorModifier` for why the anchors need to know.
    @Entry var tourAnchorsLive: Bool = false
}

/// Registers a frame only while a tour is running.
///
/// An `anchorPreference` recomputes on every frame the view MOVES (a scroll, a swipe, a
/// row expanding), and each value re-ran the host's overlay and `GeometryReader` on
/// every screen with no tour at all. The modifier is always applied, so the flag never
/// changes identity; only its VALUE goes quiet (an empty dictionary never changes).
private struct TourAnchorModifier: ViewModifier {
    let target: TourTarget
    @Environment(\.tourAnchorsLive) private var live

    func body(content: Content) -> some View {
        content.anchorPreference(key: TourAnchorKey.self, value: .bounds) { [live, target] anchor in
            live ? [target: [anchor]] : [:]
        }
    }
}

/// The overlay half of `tourHost`, its own view so only IT observes the controller;
/// read from the caller, `tour.current` would re-evaluate the whole screen per step.
private struct TourHostModifier: ViewModifier {
    let tour: TourController
    let act: TourAct

    func body(content: Content) -> some View {
        content
            .environment(\.tourAnchorsLive, tour.isRunning)
            .overlayPreferenceValue(TourAnchorKey.self) { anchors in
                // Out before the GeometryReader: a reader built to draw nothing still lays out.
                if tour.act == act, let step = tour.current {
                    GeometryReader { proxy in
                        TourOverlay(
                            step: step,
                            spotlight: step.target.flatMap { visibleRect(for: $0, in: anchors, proxy: proxy) },
                            progress: tour.progress,
                            isLast: tour.index == tour.steps.count - 1,
                            canGoBack: tour.index > 0,
                            onBack: tour.back,
                            onNext: tour.advance,
                            onSkip: tour.finish)
                    }
                    .ignoresSafeArea()
                }
            }
    }
}

extension View {
    /// Register this view as something the tour can point at. Free when no tour is
    /// running — see `TourAnchorModifier`.
    func tourAnchor(_ target: TourTarget) -> some View {
        modifier(TourAnchorModifier(target: target))
    }

    /// Draw the tour over this container. Attach it at the ROOT of a screen, above the
    /// content whose anchors it reads.
    ///
    /// `act` is a FILTER: without it Today's act, resumed when a routine was saved, drew
    /// over the session "Save and start training" had just opened.
    func tourHost(_ tour: TourController, act: TourAct) -> some View {
        modifier(TourHostModifier(tour: tour, act: act))
    }
}

/// The registrant actually ON SCREEN, out of however many claimed this target.
///
/// Scored by how much lands inside the host, so an off-screen page scores zero.
/// Zero-sized rects (not laid out yet) are dropped outright.
@MainActor
private func visibleRect(for target: TourTarget,
                         in anchors: [TourTarget: [Anchor<CGRect>]],
                         proxy: GeometryProxy) -> CGRect? {
    let bounds = CGRect(origin: .zero, size: proxy.size)
    return anchors[target]?
        .map { proxy[$0] }
        .filter { $0.width > 1 && $0.height > 1 }
        .max { a, b in area(a.intersection(bounds)) < area(b.intersection(bounds)) }
        .flatMap { area($0.intersection(bounds)) > 0 ? $0 : nil }
}

private func area(_ rect: CGRect) -> CGFloat {
    rect.isNull || rect.isEmpty ? 0 : rect.width * rect.height
}

// MARK: - Overlay

/// The whole screen, with the lit control subtracted. Filled even-odd, so the hole is a
/// hole: `contentShape` then hit-tests everything except it.
private struct ScrimHitShape: Shape {
    var hole: CGRect?

    func path(in rect: CGRect) -> Path {
        var path = Path(rect)
        if let hole {
            path.addRoundedRect(in: hole,
                                cornerSize: CGSize(width: Metrics.radiusCard,
                                                   height: Metrics.radiusCard),
                                style: .continuous)
        }
        return path
    }
}

private struct TourOverlay: View {
    let step: TourStep
    /// The lit rectangle, in the host's coordinate space. nil for a step with no target.
    let spotlight: CGRect?
    let progress: String
    let isLast: Bool
    let canGoBack: Bool
    var onBack: () -> Void
    var onNext: () -> Void
    var onSkip: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var cardHeight: CGFloat = 0
    @AccessibilityFocusState private var focusCallout: Bool

    /// Breathing room, so the hole reads as "this thing" rather than a crop of it.
    private static let padding: CGFloat = 8

    /// The hole to leave OUT of the scrim's hit area, in the host's coordinates.
    private var interactiveHole: CGRect? {
        guard step.interactive, let spotlight else { return nil }
        return spotlight.insetBy(dx: -Self.padding, dy: -Self.padding)
    }

    /// An interactive step reaches its control through the hole, with tap-anywhere off so
    /// a stray tap cannot skip the one instruction that mattered. But `spotlight` can be
    /// nil (target off-screen or not laid out), and then the scrim would eat every touch
    /// with no hole — the whole screen dead but for the small Next button. A step that
    /// cannot show its control cannot demand the interaction.
    private var tapAnywhereAdvances: Bool { !step.interactive || spotlight == nil }

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                scrim
                card(in: proxy.size)
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        // Eats every touch except the lit control on an interactive step: a half-live
        // screen under a tutorial starts sessions by accident.
        .contentShape(ScrimHitShape(hole: interactiveHole), eoFill: true)
        // Tap anywhere to advance — see `tapAnywhereAdvances`.
        .onTapGesture { if tapAnywhereAdvances { onNext() } }
        .transition(.opacity)
        .animation(Motion.state(reduceMotion), value: step)
        .task(id: step) {
            focusCallout = false
            await Task.yield()
            guard !Task.isCancelled else { return }
            focusCallout = true
        }
        .accessibilityElement(children: .contain)
        // Non-interactive steps only: `.isModal` hides everything outside this
        // subtree from VoiceOver, including the real control an interactive step
        // asks you to press, which lives in the content behind the overlay.
        .accessibilityAddTraits(step.interactive ? [] : .isModal)
    }

    /// A dimmed sheet with the control punched out. `destinationOut` + a compositing group
    /// makes a real hole; four rectangles leave seams at rounded corners.
    private var scrim: some View {
        Rectangle()
            .fill(Color.black.opacity(0.62))
            .overlay(alignment: .topLeading) {
                if let spotlight {
                    RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                        .frame(width: spotlight.width + Self.padding * 2,
                               height: spotlight.height + Self.padding * 2)
                        .offset(x: spotlight.minX - Self.padding,
                                y: spotlight.minY - Self.padding)
                        .blendMode(.destinationOut)
                }
            }
            .compositingGroup()
            .ignoresSafeArea()
    }

    private func card(in size: CGSize) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(progress)
                .font(Font.labelCaps())
                .tracking(0.8)
                .foregroundStyle(.white.opacity(0.55))
            VStack(alignment: .leading, spacing: 10) {
                Text(step.title)
                    .font(.system(.title3, weight: .semibold))
                    .foregroundStyle(.white)
                Text(step.body)
                    .font(.system(.subheadline))
                    .foregroundStyle(.white.opacity(0.82))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityFocused($focusCallout)

            HStack(spacing: 10) {
                if canGoBack {
                    tourButton(String(localized: "Back"), prominent: false, action: onBack)
                }
                tourButton(isLast ? String(localized: "Done") : String(localized: "Next"), prominent: true, action: onNext)
                Spacer(minLength: 8)
                tourButton(String(localized: "Skip"), prominent: false, action: onSkip)
            }
            .padding(.top, 2)
        }
        .padding(18)
        .frame(maxWidth: 380, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                .fill(Color.black.opacity(0.55))
                .overlay {
                    RoundedRectangle(cornerRadius: Metrics.radiusCard, style: .continuous)
                        .strokeBorder(.white.opacity(0.14), lineWidth: 1)
                }
        }
        .padding(.horizontal, Metrics.hPadding)
        // MEASURED, not estimated: a fixed guess ran a four-line step's buttons off
        // the screen, and the step you cannot leave is where the tour stops.
        .background {
            GeometryReader { proxy in
                Color.clear.onAppear { cardHeight = proxy.size.height }
                    .onChange(of: proxy.size.height) { _, new in cardHeight = new }
            }
        }
        .frame(width: size.width, alignment: .center)
        .offset(y: cardY(in: size))
    }

    /// BELOW the lit control when there is room, above it when not, centred when nothing is
    /// lit. The card must never cover the thing it describes.
    private func cardY(in size: CGSize) -> CGFloat {
        let height = cardHeight > 0 ? cardHeight : 210
        // From the PHYSICAL edges: the host ignores the safe area so the scrim
        // covers the status and tab bars. 70 clears the Dynamic Island, 120 the tab
        // bar and home indicator.
        let floor: CGFloat = 70
        let ceiling = max(floor, size.height - height - 120)
        guard let spotlight else { return min(ceiling, max(floor, (size.height - height) / 2)) }
        let below = spotlight.maxY + Self.padding + 16
        if below + height < size.height - 120 { return below }
        return min(ceiling, max(floor, spotlight.minY - Self.padding - 16 - height))
    }

    private func tourButton(_ title: String, prominent: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(prominent ? Color.black : .white.opacity(0.8))
                .padding(.horizontal, 18)
                .frame(height: 44)
                .background {
                    Capsule().fill(prominent ? Color.white : Color.white.opacity(0.14))
                }
                // A padded label hit-tests only its opaque content unless the shape is declared.
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}
