// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.today

import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.RoutineShare
import run.nuri.getagrip.store.LocalDayClock
import run.nuri.getagrip.store.LocalDeviceStore
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.DeviceChip
import run.nuri.getagrip.ui.components.UndoSnackbar
import run.nuri.getagrip.ui.components.UndoSnackbarEffect
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.share.RoutineImportSheet
import run.nuri.getagrip.ui.share.RoutineShareRequest
import run.nuri.getagrip.ui.share.RoutineShareSheet
import run.nuri.getagrip.ui.share.rememberRoutineScanner
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.tour.LocalTourController
import run.nuri.getagrip.ui.tour.TourAct

/// The ritual front door — the screen Nuri sees every single day, twice.
///
/// What you do today is the first thing on screen and one tap from starting. There is no
/// library to assemble and no session to pick apart from the routine: the routine IS the
/// thing, and the only door to changing it is the plan row on its own card.
///
/// **TODAY IS ONE SCREEN.** It is a dashboard, not a document, and it does not scroll — but
/// it is still a scroll CONTAINER, because at accessibility text sizes it overflows and
/// scrolling beats clipping a Start button nobody can reach. What is suppressed is the
/// rubber-band on content that already fits, which is what iOS's
/// `.scrollBounceBehavior(.basedOnSize)` means and what a null overscroll effect means here.
///
/// Deliberately no footer disclaimer. Schengen's dashboard carries one because every number
/// there is legally load-bearing; nothing here is. The Tindeq non-affiliation line lives in
/// Settings › About.
@Composable
fun TodayScreen(
    /// Start the routine; `timerOnly` is the gauge-free session. The host above decides where
    /// the runner is shown, so this screen never knows about navigation.
    onStart: (SessionTemplateEntity, Boolean) -> Unit = { _, _ -> },
    /// The builder, for a first routine and for a second one.
    onBuild: () -> Unit = {},
    onEdit: (SessionTemplateEntity) -> Unit = {},
    /// The log sheet, shared with History — a gym session or a hang done away from the gauge.
    onLogSession: () -> Unit = {},
    /// Whether the HOST has nothing of its own on screen. Today owns every presentation a
    /// scan can collide with except two — the log sheet and the max composer both live in
    /// `RootTabView` while this screen stays composed underneath them — so the host has to
    /// say. Everything else that could collide (the runner, the builder, the measure screen)
    /// replaces this screen outright, so its absence IS the guard.
    canPresentImport: Boolean = true,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val device = LocalDeviceStore.current
    val clock = LocalDayClock.current
    val feed = LocalHistoryFeed.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val tour = LocalTourController.current

    val routines = templates.routines

    // **THE TOUR IS STARTED FROM TODAY, not from `RootTabView`.**
    //
    // Two acts, chosen at runtime: on a real first launch there is no routine, so every step
    // about the card would light a rectangle that does not exist and the script hands you to
    // the builder instead; `routineCreated()` picks up with the Today script the moment one is
    // saved. This screen is the one that knows which — the routine list is its own read, and
    // "is there a routine" is not knowable on the first frame.
    //
    // Keyed on the COUNT, and it is both the start and the resume trigger, which is why the
    // effect runs on its first pass too.
    LaunchedEffect(routines.size) {
        if (routines.isEmpty()) {
            tour.beginIfUnseen(TourAct.Intro, hasRoutine = false)
        } else {
            // A routine now exists. If the tour handed you to the builder, this is where it
            // picks the thread back up.
            tour.routineCreated()
            tour.beginIfUnseen(TourAct.Intro, hasRoutine = true)
        }
    }

    /// FROZEN at tap time — see `RoutineShareRequest`. Holding the request rather than the
    /// routine is what stops a swipe, an edit or a delete changing the code on screen out
    /// from under whoever is pointing a camera at it.
    var shareRequest by remember { mutableStateOf<RoutineShareRequest?>(null) }
    /// The share alert fires when a routine cannot become a WORKING code — no pulls in it, or
    /// past the 50-set ceiling the decoder enforces at the other end. Rare, which is exactly
    /// why a silent no-op would be unreadable: a menu item that does nothing is
    /// indistinguishable from a tap that missed.
    var shareFailed by remember { mutableStateOf(false) }
    /// The scanned routine currently ON SCREEN, claimed from the store's inbox by
    /// `drainImportInbox`. It lives here — not in `MainActivity`, where the link actually
    /// arrives — because this is the one place that can see whether anything else holds the
    /// screen.
    var importPreview by remember { mutableStateOf<RoutineDraft?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    /// The scanner could not be opened at all — no Play services, or the module has never
    /// downloaded. Separate from `importError`, which is about a code that WAS read.
    var scannerError by remember { mutableStateOf<String?>(null) }

    /// The single door from the store's inbox to the screen, called on arrival and whenever
    /// a presentation closes. Nothing this view presents may be up, and the host must have
    /// nothing up either.
    fun drainImportInbox() {
        if (!canPresentImport) return
        if (shareRequest != null || importPreview != null || importError != null) return
        val message = templates.claimPendingImportError()
        if (message != null) {
            importError = message
            return
        }
        templates.claimPendingImport()?.let { importPreview = it }
    }

    // Arrival. Both outlets are watched, and every presentation below drains again on
    // dismissal, so a scan that landed mid-sheet appears the moment the screen is free
    // instead of being lost.
    LaunchedEffect(templates.pendingImport, templates.pendingImportError, canPresentImport) {
        // A link that COLD-LAUNCHED the app lands in the inbox before this screen exists;
        // keying the effect on the fields themselves means the first pass sweeps whatever is
        // already waiting, so there is no separate "on appear" door to keep in step.
        drainImportInbox()
    }

    val startScan = rememberRoutineScanner(
        // Straight into the same inbox a tapped link uses. There is ONE import path and one
        // set of error words, whichever door the code came through.
        onScanned = { raw -> templates.receiveShareLink(raw) },
        onUnavailable = { message -> scannerError = message },
    )

    // Freeze the identity alongside its routine, then encode without blocking the tap or
    // sheet animation. Rapid Share requests cancel publication of an older routine.
    var shareJob by remember { mutableStateOf<Job?>(null) }
    fun share(routine: SessionTemplateEntity) {
        val summary = templates.summary(routine)
        shareJob?.cancel()
        shareJob = scope.launch {
            val url = withContext(Dispatchers.Default) { RoutineShare.url(routine.draft) }
            if (url == null) {
                shareFailed = true
                return@launch
            }
            shareRequest = RoutineShareRequest(
                name = summary.name,
                metaLine = summary.metaLine,
                signatureFingers = summary.signatureFingers,
                peakIntensity = summary.peakIntensity,
                url = url,
            )
        }
    }

    /// Rung 1 of the selection rule: an explicit swipe made TODAY. Never persisted, and
    /// cleared by a day change — "this is what meets you every time you open the app" is a
    /// promise about the primary, not about yesterday's browsing.
    var chosenRoutineID by remember { mutableStateOf<UUID?>(null) }
    var chosenOnDay by remember { mutableStateOf<DayStamp?>(null) }

    // The day-scoped guard below IS the expiry — deliberately, rather than an effect that
    // clears the pin when the day rolls. Nothing reads `chosenRoutineID` without also
    // checking `chosenOnDay == today`, so a stale pin cannot be seen; clearing it in
    // composition would be a state write during a read of the same state.
    val today = clock.today

    /// Rungs 2–4: the routine the app would front WITH NO HAND ON IT — the deck's home card,
    /// and the one wearing the up-next border. Split from `selected` because the border must
    /// ignore rung 1: swipe away to browse and the border stays put on the called card, which
    /// is what makes it information ("this one is being asked of you") rather than decoration
    /// on whatever is in front.
    val upNext = remember(routines, templates.completionsToday, templates.climbToday, templates.benchmarkedToday) {
        recentlyCalled(routines, templates)
            ?: routines.firstOrNull { it.id == templates.suggestedRoutineID }
            ?: routines.firstOrNull()
    }
    val selected = routines.firstOrNull { it.id == chosenRoutineID && chosenOnDay == today } ?: upNext

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // `RootTabView`'s Scaffold has already inset this subtree; a nested Scaffold that
        // added its own would pad both twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            UndoSnackbar(snackbar)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // A scroll container that does not bounce when the content fits — see the
                // header note. The overscroll effect is dropped rather than the scroll: at
                // accessibility sizes this page genuinely does overflow.
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(bottom = LocalFloatingTabBarInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(
                nextReminderText = selected?.let { nextReminderText(it) },
                dateLine = dateLine(today),
                modifier = Modifier.padding(horizontal = Metrics.hPadding),
            )

            if (routines.isEmpty()) {
                EmptyRoutineCard(
                    Modifier.padding(horizontal = Metrics.hPadding),
                    showsGaugeNote = !device.state.isConnected,
                    onBuild = onBuild,
                    // With no routine there is no card and so no menu — this is the ONLY
                    // door to the scanner on this screen. See the note on the card.
                    onScan = startScan,
                )
            } else {
                // FULL-BLEED: the deck escapes the column's padding so the neighbouring card
                // peeks at the screen edge. Its own content padding puts a settled card back
                // on the house grid.
                RoutineDeck(
                    routines = routines,
                    templates = templates,
                    upNextID = upNext?.id,
                    selectedID = selected?.id,
                    deviceState = device.state,
                    battery = device.batteryFraction,
                    onSettled = { id ->
                        chosenRoutineID = id
                        chosenOnDay = today
                    },
                    onStart = { onStart(it, false) },
                    onStartTimerOnly = { onStart(it, true) },
                    onEdit = onEdit,
                    onDuplicate = { routine -> scope.launch { templates.duplicate(routine) } },
                    onNew = onBuild,
                    onMakePrimary = { routine -> scope.launch { templates.makePrimary(routine) } },
                    onShare = ::share,
                    onScan = startScan,
                    onDelete = { routine ->
                        scope.launch {
                            // The card only leaves the deck once the store says the delete
                            // landed; `lastDeleted` is what raises the Undo, and it is set by
                            // the same guard.
                            if (templates.delete(routine)) feed.refresh()
                        }
                    },
                    onDemo = { device.useMockDevice(true) },
                )

                // With no routine there is no ritual, and nothing the strip could honestly
                // describe.
                ConsistencyCard(
                    days = templates.consistency,
                    modifier = Modifier.padding(horizontal = Metrics.hPadding),
                    onLogSession = onLogSession,
                )
            }

            // The page has no scroll-off gutter when it fits, so this is 6 rather than the
            // house 22 — the last of the points that bought Today its single screen.
            Spacer(Modifier.height(6.dp))
        }
    }

    UndoSnackbarEffect(
        hostState = snackbar,
        deleted = templates.lastDeleted,
        message = tr("Routine deleted"),
        onUndo = { scope.launch { templates.undoDelete(); feed.refresh() } },
        onExpired = { templates.dismissUndo() },
    )

    // Every presentation drains the inbox as it closes — the deferral idiom the iOS screen
    // uses, and the reason a code scanned while the share sheet was open still arrives.
    shareRequest?.let { request ->
        RoutineShareSheet(request) {
            shareRequest = null
            drainImportInbox()
        }
    }

    importPreview?.let { draft ->
        RoutineImportSheet(draft) {
            importPreview = null
            drainImportInbox()
        }
    }

    if (importError != null) {
        // The words come from `RoutineShareError.errorDescription` — there is no second copy
        // of them in the UI, so what a damaged code says here and on the iPhone is one
        // sentence maintained in one place.
        AlertDialog(
            onDismissRequest = { importError = null; drainImportInbox() },
            title = { Text(tr("Couldn't import")) },
            text = { Text(importError.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { importError = null; drainImportInbox() }) { Text(tr("OK")) }
            },
            containerColor = palette.card,
        )
    }

    if (shareFailed) {
        AlertDialog(
            onDismissRequest = { shareFailed = false },
            title = { Text(tr("Couldn't share")) },
            text = { Text(tr("Couldn't build a share code for this routine.")) },
            confirmButton = { TextButton(onClick = { shareFailed = false }) { Text(tr("OK")) } },
            containerColor = palette.card,
        )
    }

    scannerError?.let { message ->
        AlertDialog(
            onDismissRequest = { scannerError = null },
            title = { Text(tr("Couldn't scan")) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { scannerError = null }) { Text(tr("OK")) } },
            containerColor = palette.card,
        )
    }
}

// MARK: - Header

/// Title and date establish the daily ritual. Gauge status and the next reminder share
/// the supporting row below, with connection still one tap away.
@Composable
private fun Header(
    nextReminderText: String?,
    dateLine: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGripPalette.current
    Column(modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The LARGE title stays. It is the piece of real platform chrome that makes the
        // screen read as native rather than styled (Nuri: "I liked the bigger today header,
        // I think that respects the Apple design better"); the height Today needed came out
        // of the content instead. It is drawn here rather than in a `LargeTopAppBar` because
        // a collapsing bar would scroll the title away on the one screen that must not move.
        Text(
            tr("Today"),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
        )
        Text(dateLine, style = MaterialTheme.typography.bodyMedium, color = palette.inkSecondary)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DeviceChip()
            Spacer(Modifier.weight(1f))
            if (nextReminderText != null) {
                Row(
                    Modifier.semantics {
                        // "19:30" is fine to read and too terse to hear.
                        contentDescription = L10n.tr("Next reminder at %s", nextReminderText)
                    },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Notifications,
                        contentDescription = null,
                        tint = palette.inkTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        nextReminderText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = palette.inkTertiary,
                    )
                }
            }
        }

    }
}

/// "Monday 3 August". The M1 subtitle ("Gauge connected") duplicated the DeviceChip thirty
/// points above it; a daily-ritual screen says what day it is. Read off the app's own
/// `DayClock` rather than the wall clock so it re-renders when the day rolls under a phone
/// that was left open.
private fun dateLine(today: DayStamp): String = DATE_LINE.format(today.localDate())

private val DATE_LINE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

/// Wraps to tomorrow's first slot rather than going blank once the day's last reminder has
/// passed: at 22:00 the honest answer is still "next at 08:00", and a row that empties itself
/// in the evening reads as broken.
///
/// TRANSLATION NOTE: iOS reads this off `RoutineSummary.nextReminder`, which `TemplateStore`
/// recomputes per call for exactly this reason. The same fold is repeated here rather than
/// building a whole summary for one string on a row that redraws with the device chip.
private fun nextReminderText(template: SessionTemplateEntity): String? {
    if (!template.remindersEnabled) return null
    val slots = template.reminders.sorted()
    val first = slots.firstOrNull() ?: return null
    val now = LocalTime.now()
    val minutes = now.hour * 60 + now.minute
    return (slots.firstOrNull { it.minutesFromMidnight >= minutes } ?: first).displayText()
}

/// The routine whose reminder fired most recently today and whose day is still owed —
/// opening the app off the back of a notification should land on the routine that sent it
/// (Nuri, 2026-08-10).
///
/// Computed from the SCHEDULE, deliberately not from the delivered-notification list: the
/// schedule is synchronous (the deck must not jump a frame after appearing while an async
/// query lands), it still works with notifications off — at 13:05 it is Max o'clock whether
/// or not a banner said so — and the planner's suppression of already-trained days is
/// mirrored by the `isDoneForToday` filter: a routine you finished has been answered and
/// cannot be "calling".
private fun recentlyCalled(
    routines: List<SessionTemplateEntity>,
    templates: run.nuri.getagrip.store.TemplateStore,
): SessionTemplateEntity? {
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    var best: Pair<SessionTemplateEntity, Int>? = null
    for (routine in routines) {
        if (!routine.remindersEnabled || templates.isDoneForToday(routine)) continue
        val fired = routine.reminders.map { it.minutesFromMidnight }.filter { it <= now }.maxOrNull()
            ?: continue
        // Strictly greater, so a tie goes to the earlier routine in the store's order —
        // stable, and biased toward the primary.
        if (best == null || fired > best.second) best = routine to fired
    }
    return best?.first
}
