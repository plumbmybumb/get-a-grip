// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import run.nuri.getagrip.ui.components.ClimbingIcon
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.screenArrival
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.builder.BuilderMode
import run.nuri.getagrip.ui.builder.RoutineBuilderHost
import run.nuri.getagrip.ui.history.HistoryScreen
import run.nuri.getagrip.ui.history.SessionLogSheet
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.maxes.MaxEntryDraft
import run.nuri.getagrip.ui.maxes.MaxEntrySheet
import run.nuri.getagrip.ui.maxes.MaxMeasureScreen
import run.nuri.getagrip.ui.maxes.MaxesTabScreen
import run.nuri.getagrip.ui.runner.RunnerHost
import run.nuri.getagrip.ui.settings.SettingsScreen
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import run.nuri.getagrip.ui.today.TodayScreen
import run.nuri.getagrip.ui.tour.LocalTourController
import run.nuri.getagrip.ui.tour.TourAct
import run.nuri.getagrip.ui.tour.TourHost
import run.nuri.getagrip.ui.tour.TourTarget
import run.nuri.getagrip.ui.tour.tourAnchor

/// Three tabs on iOS became four when Maxes earned its own; the anti-Frez tab count is
/// still "the fewest that can carry the ritual".
///
/// Material Symbols are not the SF Symbols
/// the iOS app names, so each one is a translation rather than a match, and the mapping is
/// recorded in `android/CLAUDE.md`:
///
/// - `figure.climbing` maps to our original `ClimbingIcon`: a climber on a rope.
///   The former Hiking substitute depicted the wrong activity.
/// - `chart.xyaxis.line` becomes `AutoMirrored.Outlined.ShowChart`. A line on axes, the same
///   picture. AutoMirrored because a chart reads the other way round in an RTL layout.
/// - `scalemass.fill` becomes `Outlined.Scale`. A weighing scale, the literal twin. It
///   replaces `FitnessCenter`, a dumbbell: this app MEASURES a load, it does not lift weights,
///   and the gym-equipment metaphor is the one Frez leans on.
/// - `gearshape.fill` becomes `Outlined.Settings`. The gear, unchanged.
///
/// Outlined throughout, including where iOS names a `.fill`: Material's own tab guidance is
/// an outlined rest state, and the selection is carried by the graphite indicator pill rather
/// than by a weight change nobody can read at 24 dp.
///
/// **The label is a `get()`, not a constructor argument.** Enum entries are built once when
/// the class loads, so a translated string baked in at construction would still be in the
/// old language after the phone's language changed under a running process. Every enum here
/// that carries a display name resolves it on read, for that reason.
enum class Tab(private val key: String, val icon: ImageVector) {
    Today("Today", ClimbingIcon),
    History("History", Icons.AutoMirrored.Outlined.ShowChart),
    Maxes("Maxes", Icons.Outlined.Scale),
    Settings("Settings", Icons.Outlined.Settings);

    val label: String get() = L10n.tr(key)
}

/// The SOFT NUDGE: once the newest measured max is four weeks stale the Maxes icon pulses —
/// the schedule-free version of a benchmark reminder (Nuri, 2026-08-10: "maybe after a while
/// the menu icon pulses"). No badge, no notification; someone who has never measured is never
/// nudged.
///
/// **Off under Reduce Motion**, exactly as iOS gates `symbolEffect(.pulse)`: the tab's own
/// staleness subtitle carries the same fact in words, so nothing is lost by holding still.
///
/// TRANSLATION NOTE: SF Symbols animates the GLYPH; Compose has no symbol effect, so the same
/// beat is a scale on the icon. `infiniteRepeatable` with `RepeatMode.Reverse` gives one
/// continuous breath rather than a sawtooth that snaps back every cycle. It is deliberately
/// NOT routed through `Motion`: the ladder is three TRANSITION curves between states, and a
/// heartbeat is neither a transition nor a state.
@Composable
private fun Modifier.benchmarkPulse(active: Boolean): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "benchmarkNudge")
    val pulse = transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "benchmarkNudgeScale",
    )
    return this.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }
}

@Composable
fun RootTabView() {
    var current by rememberSaveable { mutableStateOf(Tab.Today) }
    val tabState = rememberSaveableStateHolder()
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val feed = LocalHistoryFeed.current
    val scope = rememberCoroutineScope()
    val tour = LocalTourController.current
    val reduceMotion = rememberReduceMotion()

    // A session covers EVERYTHING, tab bar included — the iOS full-screen cover. Plain
    // `remember`, not saveable: a session cannot survive process death, and pretending
    // otherwise would resurrect a runner with no gauge stream behind it.
    var running by remember { mutableStateOf<RunRequest?>(null) }

    // **A shared routine lands on Today, so the tab moves FIRST.** Answering an import while
    // Settings is on screen would leave you on a page with no trace of what happened.
    //
    // This is the whole of the URL handler's UI job, and it is placed ABOVE the early returns
    // on purpose: it PRESENTS nothing, it only says where the answer belongs. A link tapped
    // during a session sets the tab, the session goes on undisturbed, and the inbox is still
    // waiting when the runner closes — which is the iOS rule (`onOpenURL` never presents)
    // expressed in the shape Compose already has. Composables after an early return do not
    // run at all, so a tab switch written further down would silently never happen.
    LaunchedEffect(templates.pendingImport, templates.pendingImportError) {
        if (templates.pendingImport != null || templates.pendingImportError != null) {
            current = Tab.Today
        }
    }

    val request = running
    if (request != null) {
        // **Every presented container needs its own host.** The runner replaces the root
        // outright, so the intro act's overlay is gone by the time this draws; the session act
        // is hosted here, over the screen it describes.
        TrainingAgreementGate(onCancel = { running = null }) {
        LaunchedEffect(request) { templates.noteSessionStarted(request.template) }
        TourHost(TourAct.Session) {
        RunnerHost(
            plan = request.template.plan,
            routineName = request.template.name,
            sessionsPerDayTarget = request.template.sessionsPerDay,
            maxes = templates.maxTable,
            timerOnly = request.timerOnly,
            onFinished = { outcome, decision ->
                // Discard means NOTHING is written; a session nobody pulled in is not
                // worth logging either (both rules from the iOS runner).
                if (decision.save && outcome.didAnyWork) {
                    scope.launch {
                        templates.recordSession(
                            plan = outcome.plan,
                            template = request.template,
                            reps = outcome.results,
                            startedAt = outcome.startedAt,
                            finishedAt = outcome.finishedAt,
                            rpe = decision.rpe,
                        )
                        for (max in decision.newMaxes) {
                            templates.recordMax(
                                kg = max.kg, grip = max.grip, source = MaxSource.measured,
                                side = max.side, marksBenchmarkDay = false,
                            )
                        }
                        feed.refresh()
                    }
                }
                running = null
            },
            onExit = { running = null },
        )
        }
        }
        return
    }

    // THE BUILDER IS A FULL-SCREEN COVER, never a sheet and never a push (root CLAUDE.md):
    // nothing touches the store until Save, Cancel IS undo, and a back chevron would promise
    // save-as-you-go. Hosted here so it covers the tab bar like the runner does.
    var building by remember { mutableStateOf<BuilderMode?>(null) }
    val builderMode = building
    if (builderMode != null) {
        TourHost(TourAct.Builder) {
            RoutineBuilderHost(mode = builderMode, onDone = { building = null })
        }
        return
    }

    // **The max composer's draft lives HERE, above the measure host, and that placement is
    // the whole trick.** "Measure on the gauge" is a full-screen destination, so the sheet
    // leaves composition while it is up; a draft remembered inside the sheet would come back
    // blank — the grip you were building, the hand you picked and the number you had typed
    // all gone. Declared before the early return below, this `remember` keeps its slot.
    var maxEntry by remember { mutableStateOf<MaxEntryDraft?>(null) }

    // Measuring a max is full screen too: the phone is on a bench and you are on a
    // fingerboard with both hands. The screen never writes; the number lands here.
    var measuring by remember { mutableStateOf<MeasureRequest?>(null) }
    val measure = measuring
    if (measure != null) {
        TrainingAgreementGate(onCancel = { measuring = null }) {
        MaxMeasureScreen(
            grip = measure.grip,
            onMeasured = { kg ->
                val composer = maxEntry
                if (measure.intoComposer && composer != null) {
                    // Straight back into the field a typed number would land in — the sheet
                    // is what decides provenance, by comparing this to whatever the value is
                    // when Save is tapped. Nothing is written here.
                    composer.receiveMeasured(kg)
                } else {
                    // The Maxes tab's "Measure again", which skips the composer entirely: one
                    // grip, one hand, straight to the record.
                    scope.launch {
                        templates.recordMax(kg = kg, grip = measure.grip, source = MaxSource.measured, side = measure.side)
                        feed.refresh()
                    }
                }
                measuring = null
            },
            onCancel = { measuring = null },
        )
        }
        return
    }

    // The log sheet is one sheet with two doors — History's row and Today's consistency card
    // — because it writes one kind of row and a second copy would be a second set of rules
    // about what settles a day.
    var loggingSession by remember { mutableStateOf(false) }

    // **"Take me to that tab."** Settings sits two tabs away from everything it can restart,
    // and a step that lives on History has to BE on History. `requestedTab` is how anything
    // deeper in the tree asks, and it is cleared the moment it is honoured.
    val tourTab = tour.requestedTab
    LaunchedEffect(tourTab) {
        if (tourTab != null) {
            current = Tab.entries.getOrElse(tourTab) { Tab.Today }
            tour.requestedTab = null
        }
    }
    // The step itself names its tab; the tour never describes History from Today, because
    // pointing at a tab bar icon teaches less than showing the calendar it contains.
    val stepTab = if (tour.act == TourAct.Intro) tour.current?.tab else null
    LaunchedEffect(stepTab) {
        if (stepTab != null) current = Tab.entries.getOrElse(stepTab) { Tab.Today }
    }

    // The intro act's host, ABOVE the tab bar: its last steps light History, Maxes and the
    // Settings row, and a host inside the tab content could not reach the chrome around them.
    TourHost(TourAct.Intro) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center) {
            InstrumentSurface(shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp,
                modifier = Modifier.widthIn(max = 440.dp)) {
            // Two native rows keep every tab name readable at accessibility text sizes.
            Column {
            Tab.entries.chunked(if (LocalDensity.current.fontScale >= 1.6f) 2 else 4).forEach { tabs ->
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)) {
                // **The selection pill is GRAPHITE, not Material's lavender.** The default
                // `secondaryContainer` indicator leaks a hue the palette does not contain
                // onto the one piece of chrome that is on screen at all times — and the
                // house rule is that a colour can only mean something if it is not also
                // the baseline. Graphite is ink; a 12 % wash of it reads as selection
                // without spending either signal hue on navigation.
                val itemColours = NavigationBarItemDefaults.colors(
                    indicatorColor = palette.graphite.copy(alpha = 0.12f),
                    selectedIconColor = palette.inkPrimary,
                    selectedTextColor = palette.inkPrimary,
                    unselectedIconColor = palette.inkSecondary,
                    unselectedTextColor = palette.inkSecondary,
                )
                val nudge = templates.benchmarkNudge && !reduceMotion
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = {
                            Icon(
                                tab.icon,
                                // The LABEL beside it already says "Maxes"; a description
                                // here would make TalkBack read the tab's name twice.
                                contentDescription = null,
                                modifier = Modifier.benchmarkPulse(tab == Tab.Maxes && nudge),
                            )
                        },
                        label = { Text(tab.label, maxLines = 1) },
                        colors = itemColours,
                    )
                }
            }
            }
            }
            }
            }
        },
    ) { padding ->
        // Only the top/sides constrain the viewport. A bottom viewport inset cuts
        // scrolling cards off along a full-width rectangle above the floating bar.
        val direction = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize().padding(
            start = padding.calculateStartPadding(direction),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(direction),
        ).screenArrival(current)) {
            CompositionLocalProvider(LocalFloatingTabBarInset provides padding.calculateBottomPadding()) {
            tabState.SaveableStateProvider(current) {
            when (current) {
                Tab.Today -> TodayScreen(
                    onStart = { template, timerOnly ->
                        // Written on START, not on finish: the useful question at 19:00 is
                        // "which one am I in the middle of", not "which one did I complete".
                        running = RunRequest(template, timerOnly)
                    },
                    onBuild = {
                        building = if (templates.routines.isEmpty()) BuilderMode.FirstRun else BuilderMode.AddAnother
                        // The intro act's last step is the hand-off that opened this
                        // document, and that step has now done its job — `builderOpened`
                        // takes over from a RUNNING act rather than waiting for one to end,
                        // or the "Build one now" card sits on top of the builder it opened.
                        tour.builderOpened()
                    },
                    onEdit = { template -> building = BuilderMode.Edit(template.id) },
                    onLogSession = { loggingSession = true },
                    // The two presentations Today cannot see: both are hosted here and both
                    // leave this screen composed underneath them, so the guard has to be
                    // told. Everything else that could collide replaces Today outright.
                    canPresentImport = !loggingSession && maxEntry == null,
                )
                Tab.History -> HistoryScreen(
                    onLogSession = { loggingSession = true },
                    // The step SWITCHES to this tab rather than pointing at its icon from
                    // Today: the calendar is what History is, and a tab bar glyph teaches
                    // nothing (Nuri, 2026-08-09).
                    monthAnchor = Modifier.tourAnchor(TourTarget.HistoryMonth),
                )
                Tab.Maxes -> MaxesTabScreen(
                    onAddMax = { seed -> maxEntry = composerDraft(seed, templates.recentGrips.firstOrNull()) },
                    onMeasure = { grip, side -> measuring = MeasureRequest(grip, side) },
                    cardsAnchor = Modifier.tourAnchor(TourTarget.MaxesCurves),
                )
                Tab.Settings -> SettingsScreen(
                    onAddMax = { seed -> maxEntry = composerDraft(seed, templates.recentGrips.firstOrNull()) },
                    maxesRowAnchor = Modifier.tourAnchor(TourTarget.SettingsMaxes),
                )
            }
        }
        }
        }
    }

    }

    if (loggingSession) {
        SessionLogSheet(onClose = { loggingSession = false })
    }

    maxEntry?.let { draft ->
        MaxEntrySheet(
            draft = draft,
            onMeasure = { measuring = MeasureRequest(draft.grip, draft.side, intoComposer = true) },
            onClose = { maxEntry = null },
        )
    }
}

/// A composer seeded with the grip the caller had in mind, else the most recent grip the
/// routines train, else the app's default. Never empty: a blank edge and no fingers is a
/// grip nobody pulls, and the rail below is a starting point rather than a requirement.
private fun composerDraft(seed: GripSpec?, fallback: GripSpec?): MaxEntryDraft =
    MaxEntryDraft(seed ?: fallback ?: GripSpec())


/// What Today asked for: the routine to run and whether to run it on the clock alone.
private data class RunRequest(val template: SessionTemplateEntity, val timerOnly: Boolean)

/// What Maxes asked for: one grip, one hand, on the gauge.
///
/// `intoComposer` is which of the two doors asked. From the composer the number goes back
/// into its draft and the SHEET decides provenance and writes; from the tab's "Measure
/// again" there is no composer to return to and the record is written here.
private data class MeasureRequest(
    val grip: GripSpec,
    val side: Side,
    val intoComposer: Boolean = false,
)
