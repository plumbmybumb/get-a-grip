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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import run.nuri.getagrip.runner.WorkoutViewModel
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.runner.CuePlayer
import run.nuri.getagrip.BuildConfig
import androidx.activity.compose.LocalActivity
import run.nuri.getagrip.ui.criticalforce.CriticalForceSession
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.runner.CriticalForceServiceController
import run.nuri.getagrip.ui.criticalforce.CriticalForceTestRequest
import run.nuri.getagrip.ui.criticalforce.CriticalForceTestScreen
import run.nuri.getagrip.runner.AndroidActivityPublisher
import run.nuri.getagrip.runner.AndroidSessionServiceController
import run.nuri.getagrip.store.LogIdentity
import run.nuri.getagrip.store.LocalDeviceStore
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
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
import run.nuri.getagrip.ui.maxes.NewMaxDraft
import run.nuri.getagrip.ui.maxes.NewMaxSheet
import run.nuri.getagrip.ui.maxes.MaxEditSheet
import run.nuri.getagrip.ui.maxes.SharedMaxSheet
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.maxes.MaxMeasureScreen
import run.nuri.getagrip.ui.maxes.newCriticalForceTest
import run.nuri.getagrip.ui.maxes.MaxesTabScreen
import run.nuri.getagrip.ui.runner.RunnerHost
import run.nuri.getagrip.ui.settings.SettingsScreen
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import run.nuri.getagrip.ui.gauge.LiveGaugeHost
import run.nuri.getagrip.ui.today.TodayScreen

/// Three tabs on iOS became four when Maxes earned its own; the anti-Frez tab count is still
/// "the fewest that can carry the ritual".
///
/// Material Symbols translate the iOS SF Symbols rather than match them:
///
/// - `figure.climbing` → our original `ClimbingIcon`, a climber on a rope (Hiking depicted the
///   wrong activity).
/// - `chart.xyaxis.line` → `AutoMirrored.Outlined.ShowChart`; AutoMirrored because a chart
///   reads the other way in RTL.
/// - `scalemass.fill` → `Outlined.Scale`, not `FitnessCenter`: this app MEASURES a load, and
///   the dumbbell is the gym metaphor Frez leans on.
/// - `gearshape.fill` → `Outlined.Settings`.
///
/// Outlined throughout, even for iOS `.fill`: Material's rest state is outlined, and the
/// graphite pill carries selection better than a weight change at 24 dp.
///
/// **The label is a `get()`, not a constructor argument.** Enum entries are built once per
/// process, so a translated string baked in would keep the old language after the phone's
/// changes. Every enum carrying a display name resolves it on read for this reason.
enum class Tab(private val key: String, val icon: ImageVector) {
    Today("Today", ClimbingIcon),
    History("History", Icons.AutoMirrored.Outlined.ShowChart),
    Maxes("Benchmarks", Icons.Outlined.Scale),
    Settings("Settings", Icons.Outlined.Settings);

    val label: String get() = L10n.tr(key)
}

/// The SOFT NUDGE: once the newest measured max is four weeks stale the Maxes icon pulses
/// (Nuri, 2026-08-10). No badge, no notification; someone who never measured is never nudged.
///
/// **Off under Reduce Motion**, as iOS gates `symbolEffect(.pulse)`; the tab's staleness
/// subtitle says it in words.
///
/// TRANSLATION NOTE: Compose has no symbol effect, so the beat is a scale out and back. Not
/// routed through `Motion`: those are TRANSITION curves, and a heartbeat is not a transition.
///
/// **A few beats each time the bar appears, then still.** Breathing forever kept the frame
/// clock at sixty frames a second on Today for weeks.
@Composable
internal fun Modifier.benchmarkPulse(active: Boolean): Modifier {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        repeat(BENCHMARK_PULSE_BEATS) {
            scale.animateTo(1.14f, tween(900))
            scale.animateTo(1f, tween(900))
        }
    }
    return this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/// Enough to catch an eye moving across the bar, few enough to stop before it is wallpaper.
internal const val BENCHMARK_PULSE_BEATS = 3

@Composable
fun RootTabView() {
    var current by rememberSaveable { mutableStateOf(Tab.Today) }
    val tabState = rememberSaveableStateHolder()
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val feed = LocalHistoryFeed.current
    val reduceMotion = rememberReduceMotion()

    // Retain the route AND runner across Activity recreation — not process death, where the
    // gauge timeline no longer exists.
    val workouts: WorkoutViewModel = viewModel()
    val device = LocalDeviceStore.current
    val appContext = LocalContext.current.applicationContext

    // **A shared routine lands on Today, so the tab moves FIRST**; answering while Settings is
    // up leaves no trace of what happened.
    //
    // ABOVE the early returns on purpose: composables after one never run. It PRESENTS nothing
    // (iOS: `onOpenURL` never presents) — a link tapped mid-session sets the tab, the session
    // continues, and the inbox waits for the runner to close.
    LaunchedEffect(templates.pendingImport, templates.pendingImportError) {
        if (templates.pendingImport != null || templates.pendingImportError != null) {
            current = Tab.Today
        }
    }

    val request = workouts.active
    if (request != null) {
        RunnerHost(
            workout = request,
            submissionScope = workouts.viewModelScope,
            onFinished = { outcome, decision ->
                // Discard writes NOTHING; a session nobody pulled in is not worth logging either.
                if (decision.save && outcome.didAnyWork) {
                    val saved = templates.recordSession(
                        plan = outcome.plan,
                        // The finished-session draft's id: a Save and a launch recovery are one row.
                        identity = LogIdentity.of(request.template, outcome.plan, outcome.id),
                        reps = outcome.results,
                        startedAt = outcome.startedAt,
                        finishedAt = outcome.finishedAt,
                        rpe = decision.rpe,
                        newMaxes = decision.newMaxes.map {
                            run.nuri.getagrip.data.MaxRecordEntity.from(
                                grip = it.grip, kg = it.kg, source = MaxSource.measured, side = it.side)
                        },
                    )
                    if (saved != null) feed.refresh()
                    saved != null
                } else true
            },
            onExit = { workouts.finish(request) },
        )
        return
    }

    // THE BUILDER IS A FULL-SCREEN COVER, never a sheet or push: nothing touches the store until
    // Save, Cancel IS undo, and a back chevron would promise save-as-you-go.
    // The live gauge replaces the root like the runner: the graph wants the whole screen.
    var liveGauge by rememberSaveable { mutableStateOf(false) }
    if (liveGauge) {
        LiveGaugeHost(onClose = { liveGauge = false })
        return
    }

    // Which screen the root is presenting survives a rotation — see `RootPresentation`.
    val presentation: RootPresentation = viewModel { RootPresentation() }

    // THE CRITICAL FORCE TEST replaces the root like the max test: the phone is on a bench
    // and both hands are on the edge. Its session lives in `RootPresentation`, so a
    // recreation mid-test keeps the test.
    fun openCriticalForce(grip: GripSpec, hands: CriticalForceHands) {
        if (presentation.criticalForce != null) return
        // One cue player for the visit; each hand gets a fresh test that plays through it.
        val cues = CuePlayer(appContext, diagnostic = device::recordAudio)
        val scope = presentation.viewModelScope
        presentation.criticalForce = CriticalForceTestRequest(
            grip = grip,
            hands = hands,
            newSession = { CriticalForceSession(scope = scope, cues = cues) },
            service = CriticalForceServiceController(appContext),
        )
    }
    val launchIntent = LocalActivity.current?.intent
    LaunchedEffect(Unit) {
        // DEBUG `--ez previewCriticalForce true`: open the test for a headless screenshot, from
        // the Benchmarks tab and by its rule — the one door the test has.
        if (BuildConfig.DEBUG && !presentation.previewedCriticalForce &&
            launchIntent?.getBooleanExtra("previewCriticalForce", false) == true) {
            presentation.previewedCriticalForce = true
            current = Tab.Maxes
            val (grip, hands) = newCriticalForceTest(templates.criticalForceRecords, templates.recentGrips)
            openCriticalForce(grip, hands)
        }
        // DEBUG `--ez previewMaxMeasure true` (`--ez previewMaxBoth true` for both hands): the
        // max visit, opened bare. It connects and reads on its own — the thing being shown.
        if (BuildConfig.DEBUG && !presentation.previewedMaxMeasure &&
            launchIntent?.getBooleanExtra("previewMaxMeasure", false) == true) {
            presentation.previewedMaxMeasure = true
            current = Tab.Maxes
            presentation.measuring = MeasureRequest(
                templates.recentGrips.firstOrNull() ?: GripSpec(),
                if (launchIntent.getBooleanExtra("previewMaxBoth", false)) Side.both else Side.left,
            )
        }
    }
    val criticalForce = presentation.criticalForce
    if (criticalForce != null) {
        CriticalForceTestScreen(criticalForce, onClose = {
            presentation.criticalForce = null
            feed.refresh()
        })
        return
    }
    var building by presentation::building
    val builderMode = building
    if (builderMode != null) {
        RoutineBuilderHost(mode = builderMode, onDone = { building = null })
        return
    }

    // Keep the grip composer above each child destination: Cancel returns to the same
    // grip, while a successful save closes the whole creation flow after its receipt.
    var newMax by presentation::newMax
    var editingMax by presentation::editingMax
    var editingSharedMax by presentation::editingSharedMax
    var measuring by presentation::measuring
    var measurementSaved by presentation::measurementSaved
    val measure = measuring
    if (measure != null) {
        MaxMeasureScreen(
            grip = measure.grip,
            initialSide = measure.side,
            session = presentation.liveMaxSession(measure),
            onSave = { values ->
                val receipt = templates.recordMaxesWithReceipt(values.map {
                    TemplateStore.MaxSave(measure.grip, it.side, it.kg, it.source)
                })
                if (receipt != null) {
                    measurementSaved = true
                    feed.refresh()
                }
                receipt
            },
            onClose = {
                if (measurementSaved && measure.fromNew) newMax = null
                measuring = null
            },
        )
        return
    }
    editingSharedMax?.let { request ->
        SharedMaxSheet(request.grip,
            onSaved = { if (request.fromNew) newMax = null },
            onClose = { editingSharedMax = null })
        return
    }
    editingMax?.let { request ->
        MaxEditSheet(request.grip,
            onSaved = { if (request.fromNew) newMax = null },
            onClose = { editingMax = null })
        return
    }
    newMax?.let { draft ->
        NewMaxSheet(draft,
            onMeasure = { grip, side -> measuring = MeasureRequest(grip, side, fromNew = true) },
            onEnter = { grip -> editingMax = MaxEditRequest(grip, fromNew = true) },
            onShared = { grip -> editingSharedMax = MaxEditRequest(grip, fromNew = true) },
            onClose = { newMax = null })
        return
    }

    // One log sheet with two doors (History, Today's consistency card): one kind of row, one
    // set of rules about what settles a day.
    var loggingSession by presentation::loggingSession

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
                // **The selection pill is GRAPHITE, not Material's lavender**: `secondaryContainer` leaks a
                // hue the palette lacks onto always-visible chrome. A 12 % graphite wash reads as selection
                // without spending a signal hue on navigation.
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
                                // The LABEL already says "Maxes"; a description would be read twice.
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
        // Only top/sides constrain the viewport: a bottom inset clipped scrolling cards along a
        // rectangle above the floating bar.
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
                        // Written on START: at 19:00 the question is which one you are in the middle of.
                        val started = workouts.start(template) { sessionScope ->
                            RunnerSession(
                                plan = template.plan,
                                routineName = template.name,
                                device = device,
                                maxes = templates.maxTable,
                                timerOnly = timerOnly,
                                scope = sessionScope,
                                cues = CuePlayer(appContext, diagnostic = device::recordAudio),
                                activity = AndroidActivityPublisher(appContext),
                                service = AndroidSessionServiceController(appContext),
                            )
                        }
                        if (started) templates.noteSessionStarted(template)
                    },
                    onBuild = {
                        building = if (templates.routines.isEmpty()) BuilderMode.FirstRun else BuilderMode.AddAnother
                    },
                    onEdit = { template -> building = BuilderMode.Edit(template.id) },
                    onShowHistory = { current = Tab.History },
                    onLogSession = { loggingSession = true },
                    onOpenGauge = { liveGauge = true },
                    // The two presentations Today cannot see (both hosted here, Today composed beneath), so the
                    // guard has to be told.
                    canPresentImport = !loggingSession,
                )
                Tab.History -> HistoryScreen(onLogSession = { loggingSession = true })
                Tab.Maxes -> MaxesTabScreen(
                    onAddMax = { seed -> newMax = NewMaxDraft(seed ?: templates.recentGrips.firstOrNull() ?: GripSpec()) },
                    onEdit = { grip -> editingMax = MaxEditRequest(grip) },
                    onMeasure = { grip, side -> measuring = MeasureRequest(grip, side) },
                    onCriticalForce = ::openCriticalForce,
                )
                Tab.Settings -> SettingsScreen()
            }
        }
        }
        }
    }

    if (loggingSession) {
        SessionLogSheet(onClose = { loggingSession = false })
    }

    // Below the runner's early return, so it never appears over a workout still going.
    UnsavedSessionPrompt()

}
