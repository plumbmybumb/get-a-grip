// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.today

import run.nuri.getagrip.ui.l10n.LocalizedPattern
import run.nuri.getagrip.ui.components.LocalFloatingTabBarInset
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
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalTime
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
import run.nuri.getagrip.ui.builder.OptionalRoutineDraftSaver
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

/// The ritual front door — the screen Nuri sees twice a day, every day.
///
/// What you do today is first on screen and one tap from starting. The routine IS the thing:
/// its plan row previews the session, its menu opens the editor.
///
/// **TODAY IS ONE SCREEN.** A dashboard that does not scroll, but still a scroll CONTAINER:
/// at accessibility sizes it overflows, and scrolling beats clipping Start. Only the
/// rubber-band on fitting content is suppressed (iOS `.scrollBounceBehavior(.basedOnSize)`).
///
/// No footer disclaimer: nothing here is legally load-bearing. The Tindeq non-affiliation
/// line lives in Settings › About.
@Composable
fun TodayScreen(
    /// Start the routine; `timerOnly` is the gauge-free session. The host decides where the
    /// runner shows.
    onStart: (SessionTemplateEntity, Boolean) -> Unit = { _, _ -> },
    /// The builder, for a first routine and for a second one.
    onBuild: () -> Unit = {},
    onEdit: (SessionTemplateEntity) -> Unit = {},
    onShowHistory: () -> Unit = {},
    /// The log sheet, shared with History — a gym session or a hang done away from the gauge.
    onLogSession: () -> Unit = {},
    /// The live gauge, from the header. Some people use the gauge with no routine at all
    /// (2026-09-20), so it is one tap from Today, like History and Maxes.
    onOpenGauge: () -> Unit = {},
    /// Whether the HOST has nothing of its own on screen. Only the log sheet and max composer
    /// (in `RootTabView`, with this screen composed beneath) can collide with a scan; everything
    /// else replaces this screen, so its absence IS the guard.
    canPresentImport: Boolean = true,
) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val device = LocalDeviceStore.current
    val clock = LocalDayClock.current
    val feed = LocalHistoryFeed.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val routines = templates.routines

    /// FROZEN at tap time — see `RoutineShareRequest` — so a swipe, edit or delete cannot change
    /// the code under a pointed camera.
    var shareRequest by remember { mutableStateOf<RoutineShareRequest?>(null) }
    /// Fires when a routine cannot become a WORKING code (no pulls, or past the decoder's 50-set
    /// ceiling). Rare, which is why a silent no-op would read as a missed tap.
    var shareFailed by remember { mutableStateOf(false) }
    /// The scanned routine ON SCREEN, claimed from the store's inbox by `drainImportInbox`. Here,
    /// not in `MainActivity`, because only this screen can see whether anything else holds it.
    ///
    /// SAVED across rotation: claiming EMPTIED the inbox, so a lost preview was a shared routine
    /// gone for good.
    var importPreview by rememberSaveable(stateSaver = OptionalRoutineDraftSaver) { mutableStateOf<RoutineDraft?>(null) }
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    /// The scanner could not open at all (no Play services, module not downloaded). Separate
    /// from `importError`, which is about a code that WAS read.
    var scannerError by remember { mutableStateOf<String?>(null) }
    var overviewID by rememberSaveable { mutableStateOf<String?>(null) }
    val overview = routines.firstOrNull { it.id.toString() == overviewID }

    /// The single door from the store's inbox to the screen, called on arrival and whenever a
    /// presentation closes. Nothing here or in the host may be up.
    fun drainImportInbox() {
        if (!canPresentImport) return
        if (shareRequest != null || importPreview != null || importError != null || overviewID != null) return
        val message = templates.claimPendingImportError()
        if (message != null) {
            importError = message
            return
        }
        templates.claimPendingImport()?.let { importPreview = it }
    }

    // Arrival. Every presentation below drains again on dismissal, so a scan that landed
    // mid-sheet appears once the screen is free.
    LaunchedEffect(templates.pendingImport, templates.pendingImportError, canPresentImport) {
        // A cold-launch link lands before this screen exists; keying on the fields sweeps it on
        // the first pass, with no separate "on appear" door.
        drainImportInbox()
    }

    LaunchedEffect(routines, overviewID) {
        if (overviewID != null && overview == null) {
            overviewID = null
            drainImportInbox()
        }
    }

    val startScan = rememberRoutineScanner(
        // The same inbox a tapped link uses: ONE import path, one set of error words.
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

    /// Rung 1 of the selection rule: an explicit swipe made TODAY. Never persisted; "what meets
    /// you every time" is a promise about the primary, not yesterday's browsing.
    var chosenRoutineID by remember { mutableStateOf<UUID?>(null) }
    var chosenOnDay by remember { mutableStateOf<DayStamp?>(null) }

    // The day-scoped guard below IS the expiry: every read also checks `chosenOnDay == today`,
    // and clearing the pin in composition would write state during its own read.
    val today = clock.today

    /// Rungs 2–4: the routine the app would front WITH NO HAND ON IT — the home card, wearing the
    /// up-next border. Split from `selected` because the border ignores rung 1: it stays on the
    /// called card while you browse, which makes it information rather than decoration.
    val upNext = remember(routines, templates.completionsToday, templates.climbToday, templates.benchmarkedToday) {
        recentlyCalled(routines, templates)
            ?: routines.firstOrNull { it.id == templates.suggestedRoutineID }
            ?: routines.firstOrNull()
    }
    val selected = routines.firstOrNull { it.id == chosenRoutineID && chosenOnDay == today } ?: upNext

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // `RootTabView`'s Scaffold already inset this subtree; a second inset would pad twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            UndoSnackbar(snackbar)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // No bounce when content fits (see the header note), but it can still scroll at large sizes.
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(bottom = LocalFloatingTabBarInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(
                onOpenGauge = onOpenGauge,
                nextReminderText = selected?.let { nextReminderText(it) },
                dateLine = dateLine(today),
                modifier = Modifier.padding(horizontal = Metrics.hPadding),
            )

            if (routines.isEmpty()) {
                EmptyRoutineCard(
                    Modifier.padding(horizontal = Metrics.hPadding),
                    showsGaugeNote = !device.state.isConnected,
                    onBuild = onBuild,
                    // With no routine there is no card menu: this is the ONLY scanner door here.
                    onScan = startScan,
                )
            } else {
                // FULL-BLEED, so the neighbouring card peeks at the edge; the deck's own content padding
                // puts a settled card back on the house grid.
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
                    onOverview = { overviewID = it.id.toString() },
                    onEdit = onEdit,
                    onDuplicate = { routine -> scope.launch { templates.duplicate(routine) } },
                    onNew = onBuild,
                    onMakePrimary = { routine -> scope.launch { templates.makePrimary(routine) } },
                    onShare = ::share,
                    onScan = startScan,
                    onDelete = { routine ->
                        scope.launch {
                            // Leaves the deck only once the delete landed; the same guard raises the Undo.
                            if (templates.delete(routine)) feed.refresh()
                        }
                    },
                    onDemo = { device.useMockDevice(true) },
                )

                // With no routine there is nothing the strip could honestly describe.
                ConsistencyCard(
                    days = templates.consistency,
                    modifier = Modifier.padding(horizontal = Metrics.hPadding),
                    onShowHistory = onShowHistory,
                    onLogSession = onLogSession,
                )
            }

            // 6, not the house 22: a page that fits needs no scroll-off gutter.
            Spacer(Modifier.height(6.dp))
        }
    }

    // The bar names the sessions: a routine's history goes with it (`TemplateStore.delete`),
    // and "Routine deleted" alone describes the smaller half.
    val deletedSessions = templates.lastDeleted?.sessions?.size ?: 0
    UndoSnackbarEffect(
        hostState = snackbar,
        deleted = templates.lastDeleted,
        message = when (deletedSessions) {
            0 -> tr("Routine deleted")
            1 -> tr("Routine and its session deleted")
            else -> tr("Routine and its %d sessions deleted", deletedSessions)
        },
        onUndo = { scope.launch { templates.undoDelete(); feed.refresh() } },
        onExpired = { templates.dismissUndo() },
    )

    // Every presentation drains the inbox as it closes, so a code scanned during the share
    // sheet still arrives.
    overview?.let { routine ->
        RoutineOverviewSheet(
            routine = routine,
            summary = rememberRoutineSummary(templates, routine),
            onClose = { overviewID = null; drainImportInbox() },
            onEdit = {
                // Clear the sheet before opening the existing full-screen builder.
                // Pending imports remain queued until the builder returns to Today.
                overviewID = null
                onEdit(routine)
            },
        )
    }

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
        // The words come from `RoutineShareError.errorDescription`: one sentence, shared with iOS,
        // maintained in one place.
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
    onOpenGauge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGripPalette.current
    Column(modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The LARGE title stays: real platform chrome reads as native (Nuri: "I liked the bigger
        // today header"). Drawn here, not in a `LargeTopAppBar`, whose collapse would scroll the
        // title away on the one screen that must not move.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Today"),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkPrimary,
            )
            Spacer(Modifier.weight(1f))
            // The bar's one action (History's export, Maxes' add): the live gauge.
            IconButton(onClick = onOpenGauge) {
                Icon(
                    Icons.Outlined.Speed,
                    contentDescription = tr("Live gauge"),
                    tint = palette.graphite,
                )
            }
        }
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

/// "Monday 3 August": a daily-ritual screen says what day it is. Read off `DayClock`, not the
/// wall clock, so it re-renders when the day rolls under an open phone.
private fun dateLine(today: DayStamp): String = DATE_LINE.format(today.localDate())

private val DATE_LINE = LocalizedPattern("EEEE d MMMM")

/// Wraps to tomorrow's first slot after the day's last reminder: at 22:00 the honest answer
/// is "next at 08:00", and a row that empties in the evening reads as broken.
///
/// TRANSLATION NOTE: iOS reads `RoutineSummary.nextReminder`; the fold is repeated here
/// rather than building a whole summary for one string.
private fun nextReminderText(template: SessionTemplateEntity): String? {
    if (!template.remindersEnabled) return null
    val slots = template.reminders.sorted()
    val first = slots.firstOrNull() ?: return null
    val now = LocalTime.now()
    val minutes = now.hour * 60 + now.minute
    return (slots.firstOrNull { it.minutesFromMidnight >= minutes } ?: first).displayText()
}

/// The routine whose reminder fired most recently today and whose day is still owed, so
/// opening off a notification lands on the routine that sent it (Nuri, 2026-08-10).
///
/// From the SCHEDULE, not the delivered-notification list: it is synchronous (no deck jump
/// after an async query), works with notifications off, and `isDoneForToday` mirrors the
/// planner's suppression — a finished routine cannot be "calling".
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
        // Strictly greater: a tie goes to the earlier routine, stable and biased to the primary.
        if (best == null || fired > best.second) best = routine to fired
    }
    return best?.first
}
