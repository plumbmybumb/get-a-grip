// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.store.LocalSettingsStore
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.store.TemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.rememberReduceMotion
import run.nuri.getagrip.ui.tour.TourTarget
import run.nuri.getagrip.ui.tour.tourAnchor

/// **THE ROUTINE BUILDER — one screen, one scrollable document, zero pushes.**
///
/// Document order is NAME → RHYTHM → SETS → EVERY DAY → FINE TUNING → finish/danger, and it
/// is load-bearing: RHYTHM sits ABOVE the set list because constants belong above variables,
/// expressed as vertical order instead of as screens. Both rival designs buried routine-wide
/// timing below six set rows, so changing one interval meant scrolling past the whole set
/// list every time.
///
/// **The wizard IS the editor**, literally: the first-run walkthrough and the 30th four-tap
/// edit are this same file in this same order, so there is no second surface to keep in
/// sync. `BuilderMode` changes only which draft seeds the document, whether the guide starts
/// at step 1, and whether the last block is Save or the delete row.
///
/// **Nothing here touches the store until Save.** The document is a DRAFT VALUE, so
/// reordering, removing and experimenting are free, Cancel IS undo, and a held stepper
/// cannot fire dozens of database writes.
///
/// This is the entry point the rest of the app calls. It reads the stores itself, so the
/// caller supplies only the mode and a way to close.
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun RoutineBuilderHost(
    mode: BuilderMode,
    modifier: Modifier = Modifier,
    /// Called after a successful Save with the routine's id, and on Cancel with null. The
    /// PRESENTER decides what closing means — a pop, a dismissed cover, a tab change.
    onDone: (UUID?) -> Unit,
) {
    val templates = LocalTemplateStore.current
    val settings = LocalSettingsStore.current
    val palette = LocalGripPalette.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    // **The seed is read ONCE, and deliberately without observing the store.**
    //
    // `templates.routines` is observable, and the store republishes it on every write
    // anywhere in the app — a session logged, a max recorded, a sync landing. Keyed on that
    // list, the seed would be recomputed mid-edit and the draft reset under the user's
    // fingers. `withoutReadObservation` takes the value without subscribing, so the document
    // opens on what existed the moment it opened and nothing behind it can replace it. That
    // is the same guarantee iOS gets from seeding a child view's `@State` through its init.
    val seed = remember(mode) {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
        when (mode) {
            // BLANK, always (Nuri, 2026-08-10: "when creating a routine, I think it should
            // start at blank"). Nothing is presumed and nothing is OFFERED either: the known
            // protocols are still seeds in `SessionPlan`, but no screen proposes one. A
            // chooser above the name field made the opening move "pick somebody's plan" on
            // the one app whose pitch is that the plan is yours.
            BuilderMode.FirstRun, BuilderMode.AddAnother -> RoutineDraft.blank()
            // Missing means it was deleted while Today still showed it. A blank draft is the
            // non-destructive answer: the store will CREATE rather than resurrect, and
            // nothing the user typed is thrown away.
            is BuilderMode.Edit ->
                templates.routines.firstOrNull { it.id == mode.id }?.draft ?: RoutineDraft.blank()
        }
        }
    }

    var draft by remember(seed) { mutableStateOf(seed) }
    /// The seed, kept only to answer "is this dirty".
    val initialDraft = remember(seed) { seed }

    /// At most ONE open set row. The accordion is not only a readability device: it is what
    /// guarantees exactly one dense control cluster can exist on screen at a time.
    var expanded by remember { mutableStateOf<UUID?>(null) }

    /// Which set's grip the panel is editing. It lives HERE, not on the token: the panel
    /// hangs off the top of the screen, and nothing inside a scrolling set row can reach it.
    var editingSet by remember { mutableStateOf<UUID?>(null) }

    var coachStep by remember { mutableStateOf(BuilderDraft.retiredCoachStep) }
    /// Held with its ORIGINAL id and original index, so Undo puts the same row back where it
    /// was rather than an equal-looking new one two places down.
    var removedSet by remember { mutableStateOf<RemovedSet?>(null) }
    var showDiscard by remember { mutableStateOf(false) }

    val reduceMotion = rememberReduceMotion()
    val scrollState = rememberScrollState()
    // Layout coordinates update as the page moves. They are deliberately not Compose
    // state: publishing the content's root position re-composed this entire eager form
    // on every scroll frame, even though none of its inputs changed.
    val anchors = remember { BuilderScrollAnchors() }
    val snackbarHostState = remember { SnackbarHostState() }

    fun scrollTo(key: Any) {
        val y = anchors.offset(key) ?: return
        // Same rule as the routine deck's page turn: under Reduce Motion the coach lands on
        // its block rather than flying down the document to it.
        scope.launch {
            if (reduceMotion) scrollState.scrollTo(y) else scrollState.animateScrollTo(y)
        }
    }

    // The guide's starting step, once. Reading it during the first composition rather than in
    // a remembered initializer keeps the store read out of the state's constructor.
    LaunchedEffect(mode) {
        coachStep = BuilderDraft.startingCoachStep(mode, settings.builderGuideDone)
        // The rescue copy only exists if a previous session died mid-build: Save and Cancel
        // both clear it. `initialDraft` deliberately stays at the seed, so a restored
        // document counts as DIRTY and Cancel still asks before discarding it.
        if (BuilderDraft.stashes(mode)) {
            val rescued = templates.restoreDraft()
            if (rescued != null && rescued != draft) {
                draft = rescued
                // A rescued draft means this build was already under way in a previous
                // session, so the walkthrough has been walked. Retiring it here also keeps
                // the restore's own value changes from deciding which card to show.
                coachStep = BuilderDraft.retiredCoachStep
            }
        }
    }

    // A single collector debounces persistence; edits themselves are applied immediately.
    // Keeping this separate avoids launching a new persistence coroutine in composition.
    if (BuilderDraft.stashes(mode)) {
        LaunchedEffect(Unit) {
            snapshotFlow { draft }
                .drop(1)
                .debounce(BuilderDraft.stashDebounceMillis)
                .collect { templates.stashDraft(it) }
        }
    }

    // The guide advances on a real VALUE EDIT and on nothing else — not on a scroll, not on
    // expanding a row — so it can never run away from someone still reading.
    LaunchedEffect(Unit) {
        snapshotFlow { draft.plan.name }.drop(1).distinctUntilChanged()
            .collect { coachStep = BuilderDraft.advancing(coachStep, 2) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { BuilderDraft.rhythmSignature(draft) }.drop(1).distinctUntilChanged()
            .collect { coachStep = BuilderDraft.advancing(coachStep, 3) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { BuilderDraft.gripSignature(draft) }.drop(1).distinctUntilChanged()
            .collect { coachStep = BuilderDraft.advancing(coachStep, 4) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { BuilderDraft.repsSignature(draft) }.drop(1).distinctUntilChanged()
            .collect { coachStep = BuilderDraft.advancing(coachStep, 5) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { BuilderDraft.everyDaySignature(draft) }.drop(1).distinctUntilChanged()
            .collect { coachStep = BuilderDraft.advancing(coachStep, 6) }
    }

    // The undo bar. `SnackbarDuration.Long` is Material's own ten seconds, which is the house
    // window, and its Undo action is what puts the row back.
    LaunchedEffect(removedSet) {
        val removed = removedSet ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = L10n.tr("Set removed"),
            actionLabel = L10n.tr("Undo"),
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            val sets = draft.plan.sets.toMutableList()
            sets.add(minOf(removed.index, sets.size), removed.set)
            draft = draft.copy(plan = draft.plan.copy(sets = sets))
        }
        removedSet = null
    }

    val isDirty = BuilderDraft.isDirty(draft, initialDraft)

    fun discard() {
        // A stash that outlives an explicit Cancel returns as a ghost the next time the
        // builder opens.
        templates.clearDraft()
        onDone(null)
    }

    fun cancel() {
        if (isDirty) showDiscard = true else discard()
    }

    fun save() {
        focus.clearFocus()
        if (!BuilderDraft.canSave(draft)) return
        scope.launch {
            // ONE entry point, so the builder never has to know whether it is creating or
            // editing — and the store's own `create`/`update` are what ask for notification
            // permission, once, on the first Save of a routine that wants reminders.
            val saved = templates.save(draft)
            // A rolled-back save leaves the document OPEN with the error inline, and the
            // rescue copy has to survive for the retry — the store only clears it on success.
            if (saved == null) return@launch
            // The guide has done its job the moment a routine exists.
            settings.setBuilderGuideDone(true)
            onDone(saved.id)
        }
    }

    // **Predictive back IS Cancel**, with the discard dialog only when there is something to
    // lose. Intercepting it means the system runs no back animation of its own, which is
    // right: a document that would ask "discard?" must not first animate itself away.
    PredictiveBackHandler(enabled = editingSet == null) { progress ->
        try {
            progress.collect { }
            cancel()
        } catch (_: CancellationException) {
            // The gesture was released short of the threshold: nothing to do.
        }
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (mode.editingID == null) tr("Your routine") else tr("Edit routine"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary,
                            )
                            // **The price of every edit, always visible, for zero document
                            // space — and the disabled Save's only NEARBY explanation.** It
                            // swaps to the validation issue the instant Save refuses, right
                            // next to the control that refused.
                            Text(
                                BuilderDraft.subtitle(draft),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (BuilderDraft.canSave(draft)) {
                                    palette.inkSecondary
                                } else {
                                    palette.armed
                                },
                                maxLines = 2,
                            )
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = { cancel() }) { Text(tr("Cancel")) }
                    },
                    actions = {
                        // The tour's last builder step lights THIS Save, not the one at the
                        // foot of the document: it is the one that is on screen wherever you
                        // have scrolled to, which is the only place a spotlight can find it.
                        TextButton(
                            onClick = { save() },
                            enabled = BuilderDraft.canSave(draft),
                            modifier = Modifier.tourAnchor(TourTarget.BuilderFinish),
                        ) {
                            Text(tr("Save"), fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.card,
                        titleContentColor = palette.inkPrimary,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { insets ->
            // **An eager Column in a verticalScroll, NOT a LazyColumn.** The coach's
            // scroll-to has to find an anchor that may be a screenful below the fold, and a
            // lazy list has not built it yet. A routine is a dozen rows, not a feed, so
            // eager layout is free — and it is what lets every anchor register its position.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(scrollState),
            ) {
              Column(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned(anchors::contentPlaced)
                    .padding(horizontal = Metrics.hPadding)
                    .padding(top = 12.dp, bottom = 28.dp)
                    .widthIn(max = Metrics.maxContentWidth),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // NAME — the document opens on it, in every mode, so creating and editing
                // share the same first screenful.
                Block(BuilderAnchor.Name, anchors) {
                    Coach(coachStep, 1, scope, settings, { coachStep = it }, ::scrollTo)
                    NameSection(draft.plan.name) {
                        draft = draft.copy(plan = draft.plan.copy(name = it))
                    }
                }

                Block(BuilderAnchor.Rhythm, anchors, Modifier.tourAnchor(TourTarget.BuilderRhythm)) {
                    Coach(coachStep, 2, scope, settings, { coachStep = it }, ::scrollTo)
                    RhythmSection(draft) { draft = it }
                }

                Block(BuilderAnchor.Sets, anchors, Modifier.tourAnchor(TourTarget.BuilderSets)) {
                    Coach(coachStep, 3, scope, settings, { coachStep = it }, ::scrollTo)
                    val percentBandsVary = BuilderDraft.percentBandsVary(draft)
                    // A plain row, never a pinned section header.
                    CapsLabel(tr("SETS"))
                    draft.plan.sets.forEachIndexed { index, set ->
                        key(set.id) {
                            Box(
                                Modifier.onGloballyPositioned { anchors.placed(set.id, it) },
                            ) {
                                SetRowView(
                                    plan = StablePlan(draft.plan),
                                    setID = set.id,
                                    isExpanded = expanded == set.id,
                                    maxes = templates.maxTable,
                                    percentBandsVary = percentBandsVary,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < draft.plan.sets.size - 1,
                                    onTap = { expanded = if (expanded == set.id) null else set.id },
                                    onEditGrip = { editingSet = set.id },
                                    onMoveUp = { draft = draft.movingSet(index, -1) },
                                    onMoveDown = { draft = draft.movingSet(index, 1) },
                                    onDuplicate = {
                                        val copy = set.copy(id = UUID.randomUUID())
                                        val sets = draft.plan.sets.toMutableList()
                                        sets.add(index + 1, copy)
                                        draft = draft.copy(plan = draft.plan.copy(sets = sets))
                                        expanded = copy.id
                                    },
                                    onRemove = {
                                        val sets = draft.plan.sets.toMutableList()
                                        sets.removeAt(index)
                                        draft = draft.copy(plan = draft.plan.copy(sets = sets))
                                        if (expanded == set.id) expanded = null
                                        removedSet = RemovedSet(index, set)
                                    },
                                    onSetChange = { updated ->
                                        // Writes back BY ID, so an edit in flight while the list
                                        // reorders lands on the set it came from.
                                        draft = draft.copy(
                                            plan = draft.plan.copy(
                                                sets = draft.plan.sets.map {
                                                    if (it.id == updated.id) updated else it
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    AddSetRow {
                        // Duplicates the previous set AND opens it, because editing the copy
                        // is unambiguously the next thing you will do — and a uniform routine
                        // then costs nothing extra to author.
                        val new = (draft.plan.sets.lastOrNull() ?: SetPlan())
                            .copy(id = UUID.randomUUID())
                        draft = draft.copy(plan = draft.plan.copy(sets = draft.plan.sets + new))
                        expanded = new.id
                        // NEXT frame, once the row exists to scroll to: the tap otherwise
                        // leaves you parked at the bottom of the list while the new set opens
                        // a screen above the button (Nuri, 2026-08-10).
                        scope.launch {
                            delay(16)
                            scrollTo(new.id)
                        }
                    }
                }

                Block(BuilderAnchor.Totals, anchors) {
                    Coach(coachStep, 4, scope, settings, { coachStep = it }, ::scrollTo)
                    TotalsBar(draft, maxes = templates.maxTable)
                }

                Block(BuilderAnchor.EveryDay, anchors) {
                    Coach(coachStep, 5, scope, settings, { coachStep = it }, ::scrollTo)
                    EveryDaySection(
                        draft,
                        notificationsRefused = settings.deniedNotifications,
                    ) { draft = it }
                }

                FineTuningSection(draft) { draft = it }

                Block(BuilderAnchor.Finish, anchors) {
                    FinishBlock(
                        draft = draft,
                        mode = mode,
                        templates = templates,
                        showsClosingCard = coachStep == BuilderDraft.closingCoachStep,
                        onSave = { save() },
                        onDeleted = { onDone(null) },
                    )
                }
              }
            }
        }

        // **An OVERLAY on the document, not another presentation.** The panel hangs off the
        // top of the screen, which nothing inside a scrolling set row can reach — and the
        // state that opens it lives at this root, not on the token.
        val editingID = editingSet
        if (editingID != null) {
            val index = draft.plan.sets.indexOfFirst { it.id == editingID }
            if (index >= 0) {
                GripPanel(
                    grip = draft.plan.sets[index].grip,
                    onChange = { grip ->
                        val sets = draft.plan.sets.toMutableList()
                        sets[index] = sets[index].copy(grip = grip)
                        draft = draft.copy(plan = draft.plan.copy(sets = sets))
                    },
                    onClose = { editingSet = null },
                )
            } else {
                // The set was removed under the panel; close rather than draw a stale grip.
                LaunchedEffect(editingID) { editingSet = null }
            }
        }
    }

    if (showDiscard) {
        // Only when there is something to lose: a back gesture that discards six sets of
        // authored intent has no undo, unlike everything else in this document.
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            containerColor = palette.card,
            title = { Text(tr("Discard this routine?"), color = palette.inkPrimary) },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; discard() }) {
                    Text(tr("Discard"), color = palette.alarm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text(tr("Keep editing")) }
            },
        )
    }
}

/// One document block, registering its own scroll anchor. Always built, never lazy — see the
/// note on `BuilderAnchor`.
@Composable
private fun Block(
    anchor: BuilderAnchor,
    anchors: BuilderScrollAnchors,
    /// The spotlight tour's anchor, when this block is one of the three it teaches. Two
    /// registries, deliberately kept apart: this one is a scroll offset inside the document
    /// (what the COACH needs to bring a block into view), the tour's is a window rect (what a
    /// hole in a scrim needs).
    tourAnchor: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        tourAnchor
            .fillMaxWidth()
            .onGloballyPositioned { anchors.placed(anchor, it) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

/// The coach card for one step, drawn inline at its anchor. `Next` scrolls to the next
/// section — that motion IS the step-by-step setup, with no modal sequence and nothing that
/// can trap a tap. A scroll-to misfire degrades to "no auto-scroll".
@Composable
private fun Coach(
    coachStep: Int,
    step: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    settings: run.nuri.getagrip.store.SettingsStore,
    onStep: (Int) -> Unit,
    scrollTo: (Any) -> Unit,
) {
    if (coachStep != step) return
    val script = coachScript.getOrNull(step - 1) ?: return
    CoachCard(
        step = step,
        total = BuilderDraft.coachTotal,
        title = script.title,
        message = script.message,
        onNext = {
            onStep(step + 1)
            scrollTo(BuilderDraft.anchorForStep(step + 1))
        },
        onSkip = {
            onStep(BuilderDraft.retiredCoachStep)
            // Skipping is "not now and not next time". It stays a PREFERENCE, not a one-way
            // door: Settings can put the guide back.
            settings.setBuilderGuideDone(true)
        },
    )
}

@Composable
private fun AddSetRow(onAdd: () -> Unit) {
    val palette = LocalGripPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .border(
                1.2.dp,
                palette.inkTertiary.copy(alpha = 0.45f),
                RoundedCornerShape(Metrics.radiusCard),
            )
            .clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, null, tint = palette.graphite, modifier = Modifier.size(18.dp))
        Text(
            tr("Add a set"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.graphite,
        )
    }
}

@Composable
private fun FinishBlock(
    draft: RoutineDraft,
    mode: BuilderMode,
    templates: TemplateStore,
    showsClosingCard: Boolean,
    onSave: () -> Unit,
    onDeleted: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        draft.validationIssue?.let { issue ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    null,
                    tint = palette.armed,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Text(
                    issue,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = palette.armed,
                )
            }
        }
        if (templates.saveError != null) {
            // The document STAYS OPEN on a rollback: closing on failure destroys the form and
            // the routine with it, and the reassurance is the sentence, not the exception.
            Text(
                tr("That change couldn't be saved — the routine is still here. Try again."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.alarm,
            )
        }

        val editingID = mode.editingID
        if (editingID != null) {
            // No confirmation dialog: Today arms a 10 s undo bar, and forgiveness beats a
            // dialog people learn to dismiss blindly.
            SecondaryButton(tr("Delete routine"), contentColor = palette.alarm) {
                scope.launch {
                    val template = templates.routines.firstOrNull { it.id == editingID }
                        ?: return@launch onDeleted()
                    // Rolled back → stay open with the error inline, exactly like Save.
                    if (templates.delete(template)) {
                        templates.clearDraft()
                        onDeleted()
                    }
                }
            }
            Text(
                tr("Past sessions keep the routine they were done with."),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        } else {
            if (showsClosingCard) CoachClosingCard()
            // Saves and STOPS. Building a routine and doing one are two decisions, and Start
            // lives on Today where you take it every other day (Nuri, 2026-08-09).
            PrimaryButton(
                title = tr("Save routine"),
                icon = Icons.Filled.Check,
                enabled = BuilderDraft.canSave(draft),
                onClick = onSave,
            )
        }
    }
}

/// The one set the undo bar can put back — held with its ORIGINAL index.
private data class RemovedSet(val index: Int, val set: SetPlan)

/// Reordering, as a value. Out of bounds is a no-op rather than a crash: the buttons that
/// call it are already disabled at the ends, and a belt is cheap.
private fun RoutineDraft.movingSet(index: Int, offset: Int): RoutineDraft {
    val target = index + offset
    if (index !in plan.sets.indices || target !in plan.sets.indices) return this
    val sets = plan.sets.toMutableList()
    val moved = sets.removeAt(index)
    sets.add(target, moved)
    return copy(plan = plan.copy(sets = sets))
}

@Preview(name = "Builder · first run", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun BuilderFirstRunPreview() {
    GetAGripTheme {
        // The document, drawn from a draft rather than from a store — the preview cannot
        // reach one, and every section below the host takes plain values for that reason.
        BuilderDocumentPreview(RoutineDraft.blank(), coachStep = 1)
    }
}

@Preview(name = "Builder · edit", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun BuilderEditPreview() {
    GetAGripTheme {
        BuilderDocumentPreview(RoutineDraft.starter, coachStep = BuilderDraft.retiredCoachStep)
    }
}

/// A store-free rehearsal of the document, so both previews render without a composition
/// local the tooling cannot provide.
@Composable
private fun BuilderDocumentPreview(seed: RoutineDraft, coachStep: Int) {
    val palette = LocalGripPalette.current
    var draft by remember { mutableStateOf(seed) }
    var expanded by remember { mutableStateOf<UUID?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Metrics.hPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            BuilderDraft.subtitle(draft),
            style = MaterialTheme.typography.bodySmall,
            color = if (BuilderDraft.canSave(draft)) palette.inkSecondary else palette.armed,
        )
        if (coachStep == 1) {
            CoachCard(1, 5, coachScript[0].title, coachScript[0].message, onNext = {}, onSkip = {})
        }
        NameSection(draft.plan.name) { draft = draft.copy(plan = draft.plan.copy(name = it)) }
        RhythmSection(draft) { draft = it }
        CapsLabel(tr("SETS"))
        draft.plan.sets.forEachIndexed { index, set ->
            SetRowView(
                plan = StablePlan(draft.plan),
                setID = set.id,
                isExpanded = expanded == set.id,
                maxes = run.nuri.getagrip.engine.MaxTable(),
                percentBandsVary = BuilderDraft.percentBandsVary(draft),
                canMoveUp = index > 0,
                canMoveDown = index < draft.plan.sets.size - 1,
                onTap = { expanded = if (expanded == set.id) null else set.id },
                onEditGrip = {},
                onMoveUp = {},
                onMoveDown = {},
                onDuplicate = {},
                onRemove = {},
                onSetChange = {},
            )
        }
        AddSetRow {}
        TotalsBar(draft)
        EveryDaySection(draft) { draft = it }
        FineTuningSection(draft) { draft = it }
    }
}
