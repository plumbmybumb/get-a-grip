// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.store.SettingsStore
import run.nuri.getagrip.ui.components.pressFeedback
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.Metrics

/// THE FIRST-RUN TOUR — a spotlight walked across the real screen, not a slideshow
/// (Nuri, 2026-08-09).
///
/// **It highlights the LIVE UI**, so the card described is the card you will always see
/// there. Each step names a `TourTarget`, every target registers its frame through
/// `tourAnchor`, and the scrim punches a hole at it.
///
/// **The scrim blocks touches**, unlike the app's rule about tips: a tip on its own control
/// makes the first tap only dismiss it. A tour has a Next button and never asks you to tap
/// what it points at — except on `interactive` steps, which punch the HIT REGION as well.
enum class TourTarget {
    BuildRoutine,
    RoutineCard,
    GripLadder,
    StartButton,
    StartWithoutGauge,
    Consistency,
    BuilderRhythm,
    BuilderSets,
    BuilderFinish,
    RunnerHand,
    RunnerClock,
    RunnerTrace,
    HistoryMonth,
    MaxesCurves,
    MaxesManage,
}

/// The three places the tour speaks, each seen or skipped on its own: they happen minutes or
/// days apart, and one Skip must not swallow the two you have not reached.
enum class TourAct(val rawValue: String) {
    Intro("intro"),
    Builder("builder"),
    Session("session"),
}

/// One beat of the tour. `target` is null for steps that are about the app rather than about
/// a control, and those draw a plain centred card with no hole in the scrim.
@Stable
data class TourStep(
    val target: TourTarget?,
    val title: String,
    val body: String,
    /// **The lit control stays live for this step**: the scrim's hit region is punched with the
    /// same even-odd hole as the paint, so the tap reaches the button. This is what separates
    /// describing a control from asking you to use one.
    val interactive: Boolean = false,
    /// The tab this step lives on. The tour SWITCHES to it: showing the calendar teaches more
    /// than pointing at a tab icon from Today.
    val tab: Int? = null,
)

// MARK: - The script

/// TRANSLATION NOTE: every list here is a `get()`, not a stored `val`: a top-level `val`
/// initialises once per process and would keep its language after the phone's changes.
object TourScript {
    /// **Act one, on a phone with no routine yet.** Every step about the card would light a
    /// rectangle that does not exist, so it hands you to the builder and resumes once a routine
    /// is saved — a tutorial that builds your first routine with you.
    val firstRun: List<TourStep>
        get() = listOf(
            TourStep(
                target = null,
                title = L10n.tr("Get a Grip runs your hangboard sessions"),
                // The builder opens blank on the name field, so there is no prefill to promise.
                body = L10n.tr("It counts you in, times every pull, and reads your force gauge so you know what you actually held. Start by making a routine — a name and one set is enough."),
            ),
            TourStep(
                target = TourTarget.BuildRoutine,
                title = L10n.tr("Build one now"),
                body = L10n.tr("Tap it and set the edge, which fingers, how many pulls and how long. Nothing is saved until you tap Save, and the tour carries on when you are back."),
                interactive = true,
            ),
        )

    /// Act two, once there is something to point at. Second person, present tense, concrete,
    /// no exclamation marks; every step says what the thing DOES.
    val today: List<TourStep>
        get() = listOf(
            // No opening card: arriving from the builder, the routine lit up is the useful first thing.
            TourStep(
                target = TourTarget.RoutineCard,
                title = L10n.tr("This is your routine"),
                // Short on purpose: this step lights a tall card, and a long callout would cover it.
                // COPY LENGTH IS LAYOUT here.
                body = L10n.tr("One card, one ritual. The dots are today's sessions, and a filled dot is one you have done."),
            ),
            TourStep(
                target = TourTarget.GripLadder,
                title = L10n.tr("What you are pulling"),
                body = L10n.tr("Tap the plan to see your grips, timing and hand order. You can edit the routine from its overview."),
            ),
            TourStep(
                target = TourTarget.StartButton,
                title = L10n.tr("Start here"),
                body = L10n.tr("Keep the gauge unloaded while Get a Grip connects and tares it. If the gauge is asleep, the app waits."),
            ),
            TourStep(
                target = TourTarget.StartWithoutGauge,
                title = L10n.tr("When you have no gauge"),
                body = L10n.tr("Flat battery, or you left it at home. The same routine runs on the clock and the day still counts. Nothing is measured."),
            ),
            TourStep(
                target = TourTarget.Consistency,
                title = L10n.tr("The last fortnight"),
                body = L10n.tr("Tap the days to open History. Use Log a session to add climbing or hangs done away from the gauge."),
            ),
            TourStep(
                target = TourTarget.HistoryMonth,
                title = L10n.tr("History"),
                body = L10n.tr("Every session you have done, and five weeks of them at a glance. A fuller square is a day you did more of."),
                tab = 1,
            ),
            TourStep(
                target = TourTarget.MaxesCurves,
                title = L10n.tr("Benchmarks"),
                body = L10n.tr("Each grip's max and critical force. Measure again opens the gauge for a max; Test critical force runs the four-minute test. Edit changes your hand values."),
                tab = 2,
            ),
            TourStep(
                target = TourTarget.MaxesManage,
                title = L10n.tr("Your numbers"),
                body = L10n.tr("Use + to add a max. Each grip has an Edit button for changing hand values and reviewing earlier records. Your charts and percentage targets use these same numbers."),
                tab = 2,
            ),
            TourStep(
                target = null,
                title = L10n.tr("That is the tour"),
                body = L10n.tr("The routine on screen is a starting point rather than a prescription. Open it and change anything; nothing is saved until you tap Save."),
                tab = 0,
            ),
        )

    /// The routine DOCUMENT, top to bottom — the skeleton first, then the sets that inherit it.
    /// The name field has no step: the first coach card sits on it inline.
    val builder: List<TourStep>
        get() = listOf(
            TourStep(
                target = TourTarget.BuilderRhythm,
                title = L10n.tr("What every set shares"),
                body = L10n.tr("The break between sets, how the hands split the work, and whether a rest waits for you to let go. Everything else lives on each set."),
            ),
            TourStep(
                target = TourTarget.BuilderSets,
                title = L10n.tr("The sets"),
                body = L10n.tr("Each set carries its own grip, pulls, hold, rest and target — drag the band to set a range; during a session the clock only runs inside it. Add a set copies the last one."),
            ),
            TourStep(
                target = TourTarget.BuilderFinish,
                title = L10n.tr("Save when you are happy"),
                body = L10n.tr("Nothing is written until you tap this — and saving never starts a session. Today is where you start."),
                interactive = true,
            ),
        )

    /// The session screen, on the first one you run. The runner is PAUSED while this shows
    /// (`TourController.sessionPausesRunner`).
    val session: List<TourStep>
        get() = listOf(
            TourStep(
                target = TourTarget.RunnerHand,
                title = L10n.tr("Which hand"),
                // TRANSLATION NOTE: the iOS copy names the Dynamic Island. Android draws its own palm on
                // every phone (`PalmHand`), so this sentence has no iOS twin and lives in
                // `android_extra.json`.
                body = L10n.tr("The big word is the hand that goes on the edge. Your fingers hang off the palm at the top of the screen, so the grip reads without a word."),
            ),
            TourStep(
                target = TourTarget.RunnerClock,
                title = L10n.tr("The clock"),
                body = L10n.tr("It counts your hold down and stops if you come off the edge. Coming off never ends a pull, however long you take."),
            ),
            TourStep(
                target = TourTarget.RunnerTrace,
                title = L10n.tr("The lane"),
                body = L10n.tr("The shaded band is the load you were asked for. Keep the line inside it and the clock runs; below says RE-GRIP and above says EASE OFF."),
            ),
        )

    fun steps(act: TourAct, hasRoutine: Boolean): List<TourStep> = when (act) {
        TourAct.Intro -> if (hasRoutine) today else firstRun
        TourAct.Builder -> builder
        TourAct.Session -> session
    }
}

// MARK: - Controller

/// Owns which step is showing, and whether the tour has ever finished.
///
/// The "seen" flag is VERSIONED rather than a Bool, so a bumped version can re-run the tour
/// for people who saw the old one. The keys are `tour.seen.<act>` in `SettingsStore` — a
/// STORAGE FORMAT: renaming one silently replays a tour somebody has already seen.
///
/// TRANSLATION NOTE: iOS uses `UserDefaults` inline; here everything goes through an injected
/// `TourSeenStore`, which makes the controller a plain JVM test subject.
@Stable
class TourController(private val settings: TourSeenStore) {

    companion object {
        /// Bump when the script changes enough to be worth showing again.
        const val VERSION = 1
    }

    /// **"Take me to that tab."** Settings sits two tabs from everything it restarts, so a reset
    /// there looked like nothing happened. `RootTabView` honours this and clears it.
    var requestedTab: Int? by mutableStateOf(null)

    var act: TourAct? by mutableStateOf(null)
        private set
    var steps: List<TourStep> by mutableStateOf(emptyList())
        private set
    var index: Int by mutableStateOf(0)
        private set

    /// True while act one is finished and the tour is waiting for a routine to exist. Not
    /// persisted: a tour interrupted by quitting the app is a tour you skipped.
    var awaitingRoutine: Boolean by mutableStateOf(false)
        private set

    val current: TourStep?
        get() = steps.getOrNull(index)?.let { step ->
            // Intro steps without an explicit tab live on Today, including when going
            // back from History. Builder/session steps stay in their presented screen.
            if (act == TourAct.Intro && step.tab == null) step.copy(tab = 0) else step
        }
    val isRunning: Boolean get() = current != null
    val progress: String get() = L10n.tr("%d of %d", index + 1, steps.size)

    /// **The session act PAUSES the runner**: it sends Pause when this turns true and Resume
    /// when it turns false.
    val sessionPausesRunner: Boolean get() = act == TourAct.Session && isRunning

    /// Start the tour unless it was already finished or skipped. Idempotent: `LaunchedEffect`
    /// hooks can fire more than once, and must not restart a running act.
    fun beginIfUnseen(act: TourAct, hasRoutine: Boolean = true) {
        if (isRunning || awaitingRoutine) return
        if (settings.tourSeenVersion(act.rawValue) >= VERSION) return
        begin(act, hasRoutine)
    }

    /// Start it regardless — what the Settings row calls.
    fun begin(act: TourAct, hasRoutine: Boolean = true) {
        this.act = act
        steps = TourScript.steps(act, hasRoutine)
        awaitingRoutine = act == TourAct.Intro && !hasRoutine
        index = 0
    }

    /// Replay everything — the Settings row. Clearing the flags lets the builder and session
    /// acts fire again.
    fun replay(hasRoutine: Boolean) {
        settings.clearTourSeen(TourAct.entries.map { it.rawValue })
        begin(TourAct.Intro, hasRoutine)
        // The tour starts on Today, and it is started from Settings.
        requestedTab = 0
    }

    /// Teach the builder, but only while act one is waiting for a routine — not when it is
    /// opened a month later to add a set.
    fun builderOpened() {
        // Act one may still be RUNNING (its last step opened this document). Guarding on
        // `!isRunning` left "Build one now" sitting over the builder it had just opened.
        if (!awaitingRoutine) return
        if (act != TourAct.Intro && act != null) return
        if (settings.tourSeenVersion(TourAct.Builder.rawValue) >= VERSION) {
            // Seen already: stand down rather than leaving the intro card on top.
            steps = emptyList()
            index = 0
            return
        }
        act = TourAct.Builder
        steps = TourScript.builder
        index = 0
    }

    /// A routine now exists. If the tour handed you to the builder, pick it up again with the
    /// act that has something to point at.
    fun routineCreated() {
        if (!awaitingRoutine) return
        awaitingRoutine = false
        begin(TourAct.Intro, hasRoutine = true)
    }

    fun advance() {
        if (index + 1 < steps.size) {
            index += 1
            return
        }
        // The end of act one is not the end of the tour: wait for the builder rather than marking
        // it seen, or saving the first routine lands on a screen nobody has explained.
        if (act == TourAct.Intro && awaitingRoutine) {
            steps = emptyList()
            index = 0
            return
        }
        finish()
    }

    fun back() {
        index = maxOf(0, index - 1)
    }

    /// Skipping counts as seen: being re-offered a declined tour is worse than never offering it.
    fun finish() {
        act?.let { settings.setTourSeenVersion(it.rawValue, VERSION) }
        // Only the intro act owns `awaitingRoutine`. Finishing the BUILDER act must leave it
        // standing, or saving the routine would not resume act one.
        if (act == TourAct.Intro) awaitingRoutine = false
        act = null
        steps = emptyList()
        index = 0
    }
}

/// The controller's three settings calls, as an interface (like `RoutineSettings`) so it
/// tests without a `Context` or a real preference file.
interface TourSeenStore {
    fun tourSeenVersion(act: String): Int
    fun setTourSeenVersion(act: String, version: Int)
    fun clearTourSeen(acts: List<String>)
}

/// A fresh one is exactly a fresh install.
class InMemoryTourSeenStore : TourSeenStore {
    private val seen = mutableMapOf<String, Int>()
    override fun tourSeenVersion(act: String): Int = seen[act] ?: 0
    override fun setTourSeenVersion(act: String, version: Int) { seen[act] = version }
    override fun clearTourSeen(acts: List<String>) { acts.forEach { seen.remove(it) } }
}

/// The real one, over the preference store that already owns the `tour.seen.<act>` keys.
class SettingsTourSeenStore(private val settings: SettingsStore) : TourSeenStore {
    override fun tourSeenVersion(act: String): Int = settings.tourSeenVersion(act)
    override fun setTourSeenVersion(act: String, version: Int) =
        settings.setTourSeenVersion(act, version)
    override fun clearTourSeen(acts: List<String>) = settings.clearTourSeen(acts)
}

/// `staticCompositionLocalOf`: see `LocalDeviceStore`. The in-memory default lets previews
/// render and never writes a seen flag.
val LocalTourController = staticCompositionLocalOf { TourController(InMemoryTourSeenStore()) }

// MARK: - Anchors

/// Every target's frame, in WINDOW coordinates, kept as a LIST per target.
///
/// **A list, because any pager, `LazyColumn` or eager stack can have two views claiming one
/// target** with only one on screen; a last-wins merge punched the hole off to the right.
/// Unique targets (Start, the routine card) never showed the bug.
///
/// TRANSLATION NOTE: SwiftUI collects `Anchor<CGRect>` through a `PreferenceKey`, which walks
/// UP the tree. Compose has none, so a registry is passed DOWN through a CompositionLocal and
/// each anchor writes its window rect from `onGloballyPositioned`. The host draws edge to edge
/// and subtracts its own origin, as `GeometryProxy[anchor]` does on iOS.
@Stable
class TourAnchors {
    private var frames: Map<TourTarget, List<Rect>> by mutableStateOf(emptyMap())
    private val registered = mutableMapOf<TourTarget, MutableMap<Any, Rect>>()

    fun register(target: TourTarget, id: Any, rect: Rect) {
        val keyed = registered.getOrPut(target) { mutableMapOf() }
        if (keyed[id] == rect) return
        keyed[id] = rect
        frames = frames + (target to keyed.values.toList())
    }

    fun unregister(target: TourTarget, id: Any) {
        val keyed = registered[target] ?: return
        if (keyed.remove(id) == null) return
        frames = frames + (target to keyed.values.toList())
    }

    /// The registrant actually ON SCREEN, scored by how much of it lands inside the host.
    /// Zero-sized rects (not yet laid out) are dropped outright.
    fun visibleRect(target: TourTarget, host: Rect): Rect? =
        frames[target]
            ?.filter { it.width > 1f && it.height > 1f }
            ?.maxByOrNull { area(intersect(it, host)) }
            ?.takeIf { area(intersect(it, host)) > 0f }
}

internal fun area(rect: Rect): Float =
    if (rect.width <= 0f || rect.height <= 0f) 0f else rect.width * rect.height

internal fun intersect(a: Rect, b: Rect): Rect {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    return if (right <= left || bottom <= top) Rect.Zero else Rect(left, top, right, bottom)
}

val LocalTourAnchors = staticCompositionLocalOf { TourAnchors() }

/// Register this view as something the tour can point at. Cheap when no tour runs: the
/// registry writes only when the rect moved.
@Composable
fun Modifier.tourAnchor(target: TourTarget): Modifier {
    val anchors = LocalTourAnchors.current
    val id = remember { Any() }
    DisposableEffect(anchors, target, id) {
        // A control that left the composition must stop claiming its target, or a step would
        // light where it used to be.
        onDispose { anchors.unregister(target, id) }
    }
    return this.onGloballyPositioned { anchors.register(target, id, it.boundsInWindow()) }
}

// MARK: - Host

/// Draw the tour over this container, at the ROOT of a screen above the anchors it reads.
///
/// `act` is a FILTER: without it, Today's act — resumed the moment a routine was saved —
/// drew over the session "Save and start training" had just opened.
///
/// **Every presented container needs its own host.** The builder and the runner replace the
/// root in `RootTabView`, so the root's overlay has nothing to draw over.
@Composable
fun TourHost(act: TourAct, content: @Composable () -> Unit) {
    val tour = LocalTourController.current
    val anchors = remember { TourAnchors() }
    val step = tour.current
    val showing = tour.act == act && step != null

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTourAnchors provides anchors) {
            Box(
                Modifier
                    .fillMaxSize()
                    // The screen-reader twin of the scrim: a DESCRIBING step hides everything beneath from
                    // TalkBack. Not on interactive steps, where the real control must stay reachable.
                    .then(
                        if (showing && step?.interactive == false) {
                            Modifier.clearAndSetSemantics {}
                        } else {
                            Modifier
                        },
                    ),
            ) {
                content()
            }
        }

        if (showing && step != null) {
            var host by remember { mutableStateOf(Rect.Zero) }
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        val origin = it.positionInWindow()
                        host = Rect(origin, Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }
                    // The card is the first thing TalkBack reaches, not the last.
                    .semantics { isTraversalGroup = true; traversalIndex = -1f },
            ) {
                if (host.width > 0f) {
                    // Anchors are in WINDOW space; this is the one place it is converted to the overlay's.
                    val lit = step.target
                        ?.let { anchors.visibleRect(it, host) }
                        ?.translate(-host.left, -host.top)
                    TourOverlay(
                        step = step,
                        spotlight = lit,
                        progress = tour.progress,
                        isLast = tour.index == tour.steps.size - 1,
                        canGoBack = tour.index > 0,
                        hostHeight = host.height,
                        onBack = tour::back,
                        onNext = tour::advance,
                        onSkip = tour::finish,
                    )
                }
            }
        }
    }
}

/// The whole screen minus the lit control, filled EVEN-ODD. It shapes the touch BLOCKER:
/// Compose hit-tests against the layer's clip outline, so a tap in the hole misses the
/// blocker and reaches the control.
private class ScrimHitShape(private val hole: Rect?, private val radius: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        path.addRect(Rect(Offset.Zero, size))
        hole?.let { path.addRoundRect(RoundRect(it, CornerRadius(radius, radius))) }
        return Outline.Generic(path)
    }
}

@Composable
private fun TourOverlay(
    step: TourStep,
    /// The lit rectangle, in the host's coordinate space. null for a step with no target.
    spotlight: Rect?,
    progress: String,
    isLast: Boolean,
    canGoBack: Boolean,
    hostHeight: Float,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val density = LocalDensity.current
    /// Breathing room so the hole reads as "this thing", not a crop of it.
    val padPx = with(density) { 8.dp.toPx() }
    val radiusPx = with(density) { Metrics.radiusCard.toPx() }

    /// An interactive step reaches its control through the hole, with tap-anywhere off so a
    /// stray tap cannot skip the instruction. But when `spotlight` is null (scrolled off, not
    /// laid out) there is no hole, and the WHOLE screen went dead except the small Next button.
    val tapAnywhereAdvances = !step.interactive || spotlight == null
    val hole = spotlight?.inflate(padPx)
    val interactiveHole = if (step.interactive) hole else null

    var cardHeightPx by remember(step) { mutableStateOf(0f) }

    Box(Modifier.fillMaxSize()) {
        // **`BlendMode.Clear` in an OFFSCREEN layer is the only real hole.** Four rectangles leave a
        // seam at every rounded corner (`Metrics.radiusCard` is 22 dp). Paint only; no input.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .clearAndSetSemantics {},
        ) {
            drawRect(Color.Black.copy(alpha = 0.62f))
            hole?.let {
                drawRoundRect(
                    color = Color.Black,
                    topLeft = it.topLeft,
                    size = it.size,
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    blendMode = BlendMode.Clear,
                )
            }
        }

        // The touch blocker eats everything except the lit control on an interactive step: a
        // half-live screen starts sessions by accident.
        Box(
            Modifier
                .fillMaxSize()
                .clip(ScrimHitShape(interactiveHole, radiusPx))
                .pointerInput(step, tapAnywhereAdvances) {
                    // Not while a step asks you to press something it can show: a stray tap would skip it.
                    detectTapGestures { if (tapAnywhereAdvances) onNext() }
                }
                .clearAndSetSemantics {},
        )

        val cardTop = with(density) {
            cardOffsetPx(
                spotlight = hole,
                cardHeight = if (cardHeightPx > 0f) cardHeightPx else 210.dp.toPx(),
                hostHeight = hostHeight,
                floor = 70.dp.toPx(),
                bottomInset = 120.dp.toPx(),
            ).toDp()
        }

        val cardScroll = rememberScrollState()
        LaunchedEffect(step) { cardScroll.scrollTo(0) }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, cardTop.roundToPx()) }
                .padding(horizontal = Metrics.hPadding)
                .widthIn(max = 380.dp)
                .heightIn(max = with(density) { hostHeight.toDp() - 190.dp }.coerceAtLeast(120.dp))
                .clip(RoundedCornerShape(Metrics.radiusCard))
                .background(Color(0xFF20252D))
                // MEASURED, not estimated: a fixed guess ran a four-line step's buttons off the screen,
                // and the step you cannot leave is where the tour stops.
                .onGloballyPositioned { cardHeightPx = it.size.height.toFloat() }
                .verticalScroll(cardScroll)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                progress,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.66f),
            )
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(step.title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(step.body, style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.86f))
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canGoBack) TourButton(tr("Back"), prominent = false, onClick = onBack)
                TourButton(if (isLast) tr("Done") else tr("Next"), prominent = true, onClick = onNext)
                TourButton(tr("Skip"), prominent = false, onClick = onSkip)
            }
        }
    }
}

/// BELOW the lit control when there is room, above when not, centred when nothing is lit —
/// the card must never cover what it describes.
///
/// From the PHYSICAL edges, because the host ignores the safe area so the scrim covers the
/// system bars: 70 dp clears a cutout, 120 dp clears the tab bar and gesture handle.
internal fun cardOffsetPx(
    spotlight: Rect?,
    cardHeight: Float,
    hostHeight: Float,
    floor: Float,
    bottomInset: Float,
): Float {
    val ceiling = maxOf(floor, hostHeight - cardHeight - bottomInset)
    if (spotlight == null) {
        return minOf(ceiling, maxOf(floor, (hostHeight - cardHeight) / 2f))
    }
    val below = spotlight.bottom + 16f
    if (below + cardHeight < hostHeight - bottomInset) return below
    return minOf(ceiling, maxOf(floor, spotlight.top - 16f - cardHeight))
}

@Composable
private fun TourButton(title: String, prominent: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            // House rule: padding and background hit-test only when the shape is declared. Clipping to
            // the capsule BEFORE the clickable declares it.
            .clip(CircleShape)
            .background(if (prominent) Color.White else Color.White.copy(alpha = 0.18f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .pressFeedback(interaction)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (prominent) Color.Black else Color.White.copy(alpha = 0.86f),
        )
    }
}
