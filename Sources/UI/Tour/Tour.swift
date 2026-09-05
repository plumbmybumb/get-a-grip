// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

/// THE FIRST-RUN TOUR — a spotlight walked across the real screen, not a slideshow.
///
/// Nuri asked for this (2026-08-09): *"I want all the features to be explained thoroughly
/// in a tutorial on your first launch, where each thing gets highlighted, maybe even with
/// a spotlight on the thing you need to focus on."*
///
/// **It highlights the LIVE UI.** A carousel of screenshots would be easier and would
/// teach nothing: the point is that the card being described is the card you are looking
/// at, in the place it will always be. So each step names a `TourTarget`, every target
/// registers its own frame through a preference, and the scrim punches a hole at it.
///
/// **The scrim blocks touches, deliberately** — and that is the opposite of the app's rule
/// about tips. `.popoverTip` on a control the tip is telling you to use is a trap because
/// the first tap only dismisses it; the control looks pressed and does nothing. A tour has
/// no such ambiguity: there is a Next button, nothing else is live, and the thing being
/// described is lit rather than offered. The failure mode that rule exists to prevent
/// cannot happen here because the tour never asks you to tap what it is pointing at.
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
    case settingsMaxes
}

/// The three places the tour has something to say. Each is seen — or skipped — on its own,
/// because they happen minutes or days apart and one Skip should not silently swallow the
/// two you have not reached yet.
enum TourAct: String, Hashable {
    case intro
    case builder
    case session
}

/// One beat of the tour. `target` is nil for steps that are about the app rather than
/// about a control, and those draw a plain centred card with no hole in the scrim.
struct TourStep: Identifiable, Hashable {
    var id: String { title }
    var target: TourTarget?
    var title: String
    var body: String
    /// **The lit control stays live for this step.** The scrim's hit shape is punched with
    /// the same hole the scrim is, using an even-odd fill, so the tap lands on the button
    /// rather than on a sheet of glass over it.
    ///
    /// This is the difference between a step that describes a control and a step that asks
    /// you to use one, and getting it wrong is the exact failure the codebase already has
    /// a rule about: a tip over a control it is telling you to press, where the first tap
    /// only dismisses and the control looks broken.
    var interactive = false
    /// The tab this step lives on. The tour SWITCHES to it rather than describing it from
    /// Today — pointing at a tab bar icon and saying "History is over there" teaches less
    /// than showing the calendar it contains (Nuri, 2026-08-09).
    var tab: Int?
}

// MARK: - The script

extension TourStep {
    /// **Act one, on a phone with no routine yet.** The tour has to build one before it
    /// can point at anything: on a genuine first launch Today is an empty state, and every
    /// step about the card, the ladder and the Start button would be lighting a rectangle
    /// that does not exist. So it hands you over to the builder and picks up again the
    /// moment a routine is saved — which is also what Nuri asked for, a tutorial that
    /// builds your first routine with you rather than describing one.
    static let firstRun: [TourStep] = [
        TourStep(
            target: nil,
            title: String(localized: "Get a Grip runs your hangboard sessions"),
            // No prefill to promise any more: the builder opens blank on the name field
            // (2026-08-19), so a card saying "this is mostly saying yes" would be
            // describing a screen nobody gets.
            body: String(localized: "It counts you in, times every pull, and reads your force gauge so you know what you actually held. Start by making a routine — a name and one set is enough.")),
        TourStep(
            target: .buildRoutine,
            title: String(localized: "Build one now"),
            body: String(localized: "Tap it and set the edge, which fingers, how many pulls and how long. Nothing is saved until you tap Save, and the tour carries on when you are back."),
            interactive: true),
    ]

    /// Act two, once there is something to point at. Written in the app's own voice:
    /// second person, present tense, concrete, no exclamation marks, and every step says
    /// what the thing DOES rather than how good it is.
    static let today: [TourStep] = [
        // No opening card. Arriving here from the builder, the first useful thing is the
        // routine lit up rather than a paragraph laid over the top of it — and a step with
        // no target draws centred, which put the words on the card they were about.
        TourStep(
            target: .routineCard,
            title: String(localized: "This is your routine"),
            // Short on purpose. This step lights the whole card, which is tall, so a
            // four-line callout has nowhere to sit that does not cover the thing it is
            // describing. Copy length is layout here.
            body: String(localized: "One card, one ritual. The dots are today's sessions, and a filled dot is one you have done.")),
        TourStep(
            target: .gripLadder,
            title: String(localized: "What you are pulling"),
            body: String(localized: "The plan in one line: edge, sets, pulls and how long it runs. Tap it to change any of that.")),
        TourStep(
            target: .startButton,
            title: String(localized: "Start here"),
            body: String(localized: "Get a Grip connects and tares your gauge for you, so you can tap this before you chalk up. If the gauge is still asleep it waits.")),
        TourStep(
            target: .startWithoutGauge,
            title: String(localized: "When you have no gauge"),
            body: String(localized: "Flat battery, or you left it at home. The same routine runs on the clock and the day still counts. Nothing is measured.")),
        TourStep(
            target: .consistency,
            title: String(localized: "The last fortnight"),
            body: String(localized: "One mark a day. A session at the climbing gym, or hangs done away from the gauge, count too: log them here and they join the day.")),
        TourStep(
            target: .historyMonth,
            title: String(localized: "History"),
            body: String(localized: "Every session you have done, and five weeks of them at a glance. A fuller square is a day you did more of."),
            tab: 1),
        TourStep(
            target: .maxesCurves,
            title: String(localized: "Maxes"),
            body: String(localized: "Every grip's ceiling, drawn over time. Measure one from here — a measured max marks the day as a benchmark, and your percent targets follow the newest number on their own."),
            tab: 2),
        TourStep(
            target: .settingsMaxes,
            title: String(localized: "Your numbers"),
            body: String(localized: "Record what you can hold on each grip, per hand if they differ, and measure one on the gauge from here. Percentages need this; kilograms do not."),
            tab: 3),
        TourStep(
            target: nil,
            title: String(localized: "That is the tour"),
            body: String(localized: "The routine on screen is a starting point rather than a prescription. Open it and change anything; nothing is saved until you tap Save."),
            tab: 0),
    ]

    /// The routine DOCUMENT, top to bottom — the skeleton first, then the sets that
    /// inherit it. (The old deck act walked one grip card control by control; the
    /// document act walks the page's own order instead.)
    ///
    /// It opened on a "Start from a plan" step until the prefill chooser was removed
    /// (2026-08-19). The name field above the rhythm block is deliberately not a step of
    /// its own: the guide's first coach card sits on it inline, and a spotlight over a
    /// text field says nothing the field does not already say.
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
/// The "seen" flag is VERSIONED rather than a Bool: when the tour gains an act, a bumped
/// version is what lets it run again for people who saw the old one, and a Bool would have
/// no way to say that.
@Observable
@MainActor
final class TourController {
    /// Bump when the script changes enough to be worth showing again.
    private static let version = 1
    private static func seenKey(_ act: TourAct) -> String { "tour.seen.\(act.rawValue)" }

    /// **"Take me to that tab."** Settings sits two tabs away from everything it can
    /// restart, so a reset there looked like nothing had happened at all (Nuri,
    /// 2026-08-09). The tab selection lives in `RootTabView`; this is how anything deeper
    /// in the tree asks for it. Cleared by the observer once honoured.
    var requestedTab: Int?

    private(set) var act: TourAct?
    private(set) var steps: [TourStep] = []
    private(set) var index = 0
    /// True while act one is finished and the tour is waiting for a routine to exist.
    /// Not persisted: a tour interrupted by quitting the app is a tour you skipped.
    private(set) var awaitingRoutine = false

    var current: TourStep? { index < steps.count ? steps[index] : nil }
    var isRunning: Bool { current != nil }
    var progress: String { String(localized: "\(index + 1) of \(steps.count)") }

    /// Start the tour unless it has already been finished or skipped once.
    /// Idempotent, because callers are `onChange`/`onAppear` hooks that can fire more than
    /// once — starting an act twice must not restart one already running.
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

    /// Replay everything from the beginning — the Settings row. Clearing the flags is what
    /// lets the builder and session acts fire again the next time you reach them.
    func replay(hasRoutine: Bool) {
        for act in [TourAct.intro, .builder, .session] {
            UserDefaults.standard.removeObject(forKey: Self.seenKey(act))
        }
        begin(.intro, hasRoutine: hasRoutine)
        // The tour starts on Today, and it is started from Settings.
        requestedTab = 0
    }

    /// The setup deck opened while act one was waiting for a routine, so teach it. Runs
    /// only in that window: opening the deck a month later to add a grip is not a moment
    /// for a tutorial.
    func builderOpened() {
        // Act one may still be RUNNING — its last step is the hand-off that opened this
        // deck, and that step has now done its job. Guarding on `!isRunning` left the
        // intro's "Build one now" card sitting over the builder it had just opened.
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
            // The end of act one is not the end of the tour: step aside and wait for the
            // builder rather than marking it seen, or saving your first routine would
            // drop you back onto a screen nobody has explained.
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

    /// Skipping counts as seen. Being asked twice whether you want the tour you already
    /// declined is worse than never offering it.
    func finish() {
        if let act { UserDefaults.standard.set(Self.version, forKey: Self.seenKey(act)) }
        // Only the intro act owns `awaitingRoutine`. Finishing the BUILDER act must leave
        // it standing, or saving the routine would not resume act one.
        if act == .intro { awaitingRoutine = false }
        act = nil
        steps = []
        index = 0
    }
}

// MARK: - Anchors

/// Every target's frame, collected up the view tree as an `Anchor<CGRect>` so the overlay
/// can convert it into its own coordinate space. `Anchor` rather than a `GeometryReader`
/// per target: a reader would need one wrapper view around every control, and would report
/// coordinates in whatever space its parent happened to be in.
struct TourAnchorKey: PreferenceKey {
    /// A LIST per target, not a single anchor — and that is the whole reason the
    /// spotlight worked on buttons and not on the builder's controls.
    ///
    /// The setup deck is an eager `HStack` of pages: every grip card is alive at once,
    /// including the off-screen spare. Each of them registered `.builderFingers`, the
    /// merge kept the last one, and the hole was punched somewhere off to the right of
    /// the screen. Unique targets — the Start button, the routine card — had exactly one
    /// registrant and so were never affected, which is exactly the pattern Nuri spotted.
    ///
    /// Keeping all of them and letting the host pick the visible one also fixes the
    /// general case: any list, pager or lazy stack can legitimately have two views
    /// claiming one target, and only one of them is on screen.
    static let defaultValue: [TourTarget: [Anchor<CGRect>]] = [:]
    static func reduce(value: inout [TourTarget: [Anchor<CGRect>]],
                       nextValue: () -> [TourTarget: [Anchor<CGRect>]]) {
        value.merge(nextValue()) { old, new in old + new }
    }
}

extension View {
    /// Register this view as something the tour can point at. Free when no tour is running.
    func tourAnchor(_ target: TourTarget) -> some View {
        anchorPreference(key: TourAnchorKey.self, value: .bounds) { [target: [$0]] }
            // Doubles as a SCROLL id, so a host inside a ScrollView can bring the control
            // into view before the spotlight tries to light it — half the builder's
            // controls are below the fold, and a hole punched over empty space below the
            // screen is a step that explains nothing (Nuri, 2026-08-09).
            //
            // Constant per control, so identity never changes and no `ValueRow` loses the
            // number you were typing into it.
            .id(target)
    }

    /// Draw the tour over this container. Attach it at the ROOT of a screen, above the
    /// content whose anchors it reads.
    ///
    /// `act` is a FILTER, and every host names one. Without it, a host draws whatever act
    /// happens to be running — so Today's act, resumed the moment a routine was saved,
    /// rendered its steps over the session that "Save and start training" had just opened.
    /// A host only ever shows the act it belongs to.
    func tourHost(_ tour: TourController, act: TourAct) -> some View {
        overlayPreferenceValue(TourAnchorKey.self) { anchors in
            GeometryReader { proxy in
                if tour.act == act, let step = tour.current {
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
            }
            .ignoresSafeArea()
        }
    }
}

/// The registrant that is actually ON SCREEN, out of however many claimed this target.
///
/// Scored by how much of it lands inside the host — a page waiting off to the right scores
/// zero and loses to the one you are looking at. Zero-sized rects are dropped outright:
/// a view that has not been laid out yet reports one, and it would beat nothing.
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

    /// Breathing room around the lit control, so the hole reads as "this thing" rather
    /// than as a crop of it.
    private static let padding: CGFloat = 8

    /// The hole to leave OUT of the scrim's hit area, in the host's coordinates.
    private var interactiveHole: CGRect? {
        guard step.interactive, let spotlight else { return nil }
        return spotlight.insetBy(dx: -Self.padding, dy: -Self.padding)
    }

    /// An interactive step normally reaches its control through the punched hole, and
    /// tap-anywhere is off so a stray tap cannot skip past the one instruction that
    /// mattered. But `spotlight` can resolve to nil — the target scrolled off-screen,
    /// not yet laid out — and without this the scrim still eats every touch with no
    /// hole to let one through: the WHOLE screen goes dead, reachable only by the small
    /// Next button in the card. A step describing something that is not actually lit
    /// cannot demand the interaction it can't show.
    private var tapAnywhereAdvances: Bool { !step.interactive || spotlight == nil }

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                scrim
                card(in: proxy.size)
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        // The scrim eats every touch on purpose — except the lit control on an interactive
        // step. A half-live screen under a tutorial is how people start a session by
        // accident; a dead control the tutorial just told you to press is worse.
        .contentShape(ScrimHitShape(hole: interactiveHole), eoFill: true)
        // Tap anywhere to advance, but NOT while a step is asking you to press something
        // it can actually show you: there, a stray tap would carry you past the one
        // instruction that mattered. See `tapAnywhereAdvances` for the unresolved-target
        // escape hatch.
        .onTapGesture { if tapAnywhereAdvances { onNext() } }
        .transition(.opacity)
        .animation(reduceMotion ? Motion.reduced : Motion.state(false), value: step)
        .accessibilityElement(children: .contain)
        // Scoped to non-interactive steps only: `.isModal` hides every element outside
        // this subtree from VoiceOver, including the real control an interactive step
        // punches a touch-hole for — which lives in the tab content behind the overlay,
        // not inside this card. On exactly the steps that ask you to press something, a
        // VoiceOver user needs to reach past the "modal" to the thing being taught.
        .accessibilityAddTraits(step.interactive ? [] : .isModal)
    }

    /// A dimmed sheet with the control punched out of it. `destinationOut` + a compositing
    /// group is the only way to get a real hole; drawing four rectangles around the
    /// control leaves seams at every corner as soon as the corner radius is not zero.
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
            Text(step.title)
                .font(.system(.title3, weight: .semibold))
                .foregroundStyle(.white)
            Text(step.body)
                .font(.system(.subheadline))
                .foregroundStyle(.white.opacity(0.82))
                .fixedSize(horizontal: false, vertical: true)

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
        // MEASURED, not estimated. A fixed guess was fine for a two-line step and ran the
        // buttons off the bottom of the screen on a four-line one — and the step whose
        // buttons you cannot reach is the step the tour stops at.
        .background {
            GeometryReader { proxy in
                Color.clear.onAppear { cardHeight = proxy.size.height }
                    .onChange(of: proxy.size.height) { _, new in cardHeight = new }
            }
        }
        .frame(width: size.width, alignment: .center)
        .offset(y: cardY(in: size))
    }

    /// BELOW the lit control when there is room under it, above it when there is not, and
    /// centred when nothing is lit. The card must never cover the thing it is describing,
    /// which is the one job this arithmetic has.
    private func cardY(in size: CGSize) -> CGFloat {
        let height = cardHeight > 0 ? cardHeight : 210
        // Measured from the PHYSICAL edges, because the host ignores the safe area — it
        // has to, so the scrim covers the status bar and the tab bar. 70 clears the
        // Dynamic Island, 120 clears the tab bar and the home indicator. A card tucked
        // under either is a card with unreachable buttons.
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
                // The house rule: a label with padding and a background still hit-tests
                // only its opaque content unless the shape is declared.
                .contentShape(.capsule)
        }
        .buttonStyle(PressFeedbackButtonStyle())
    }
}
