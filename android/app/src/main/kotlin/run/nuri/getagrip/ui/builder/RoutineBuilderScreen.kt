// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

/// **THE ROUTINE BUILDER — one screen, one scrollable document, zero pushes.**
///
/// Document order is NAME → RHYTHM → SETS → EVERY DAY → FINE TUNING → finish/danger, and it
/// is load-bearing: RHYTHM sits ABOVE the sets because constants belong above variables.
/// Rival designs buried routine-wide timing below six set rows, so changing one interval
/// meant scrolling past the whole list every time.
///
/// **The wizard IS the editor**: first run and the 30th edit are this same file, so there is
/// no second surface to keep in sync. `BuilderMode` changes only the seed draft and whether the
/// last block is Save or the delete row.
///
/// **Nothing touches the store until Save.** The document is a DRAFT VALUE: Cancel IS undo,
/// and a held stepper cannot fire dozens of writes. It reads the stores itself, so the caller
/// supplies only the mode and a way to close.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineBuilderHost(
    mode: BuilderMode,
    modifier: Modifier = Modifier,
    /// Called after a successful Save with the routine's id, on Cancel with null. The PRESENTER
    /// decides what closing means.
    onDone: (UUID?) -> Unit,
) {
    val templates = LocalTemplateStore.current
    val settings = LocalSettingsStore.current
    val palette = LocalGripPalette.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    // **The seed is read ONCE, without observing the store.** `templates.routines` republishes
    // on every write anywhere (a session logged, a sync landing); keyed on it, the draft would
    // reset mid-edit. `withoutReadObservation` gives the guarantee iOS gets from seeding a child
    // view's `@State` in its init.
    val seed = remember(mode) {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
        when (mode) {
            // BLANK, always (Nuri, 2026-08-10). Nothing presumed or OFFERED: the known protocols stay
            // seeds in `SessionPlan`, but a chooser made the opening move "pick somebody's plan" in the
            // app whose pitch is that the plan is yours.
            BuilderMode.FirstRun, BuilderMode.AddAnother -> RoutineDraft.blank()
            // Missing means deleted while Today still showed it. A blank draft is non-destructive:
            // the store CREATES rather than resurrects, and nothing typed is lost.
            is BuilderMode.Edit ->
                templates.routines.firstOrNull { it.id == mode.id }?.draft ?: RoutineDraft.blank()
        }
        }
    }

    val editableSeed = remember(seed) { BuilderDraft.editable(seed) }
    // **SAVED, not remembered — for creating AND editing.** Rotation destroys every `remember`,
    // and the rescue stash lags up to half a second and does not exist for edits. The draft
    // round-trips through the stash's frozen JSON.
    val draftState = rememberSaveable(seed, stateSaver = RoutineDraftSaver) { mutableStateOf(editableSeed) }
    var draft by draftState
    /// The seed, kept only to answer "is this dirty".
    val initialDraft = editableSeed
    /// Every section writes through this ONE remembered door — see `DraftUpdate`.
    val update: DraftUpdate = remember(draftState) { { transform -> draftState.value = transform(draftState.value) } }

    /// At most ONE open set row, which guarantees one dense control cluster on screen at a time.
    var expanded by rememberSaveable { mutableStateOf<UUID?>(null) }

    /// Which set's grip the panel is editing. HERE, not on the token: the panel hangs off the top
    /// of the screen, out of any scrolling row's reach.
    var editingSet by rememberSaveable { mutableStateOf<UUID?>(null) }

    /// Whether this document has been OPENED (stash swept). Saved, so a rotation does not swap
    /// in an older stash.
    var opened by rememberSaveable { mutableStateOf(false) }
    /// Held with its ORIGINAL id and index, so Undo restores the same row in the same place.
    var removedSet by remember { mutableStateOf<RemovedSet?>(null) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    /// A Save in flight: both Saves dim and a second tap is refused. The write is not instant and
    /// a new draft has no id yet, so two taps used to create the routine TWICE.
    var saving by remember { mutableStateOf(false) }

    val reduceMotion = rememberReduceMotion()
    val scrollState = rememberScrollState()
    // Not Compose state: publishing the root position re-composed this eager form on every
    // scroll frame.
    val anchors = remember { BuilderScrollAnchors() }
    val snackbarHostState = remember { SnackbarHostState() }

    fun scrollTo(key: Any) {
        val y = anchors.offset(key) ?: return
        // Reduce Motion: the document lands on the row instead of flying to it.
        scope.launch {
            if (reduceMotion) scrollState.scrollTo(y) else scrollState.animateScrollTo(y)
        }
    }

    LaunchedEffect(mode) {
        if (opened) return@LaunchedEffect
        opened = true
        // A rescue copy exists only if a previous session died mid-build (Save and Cancel clear it).
        // `initialDraft` stays at the seed, so a restored document is DIRTY and Cancel still asks.
        if (BuilderDraft.stashes(mode)) {
            val rescued = templates.restoreDraft()
            if (rescued != null && rescued != draft) draft = BuilderDraft.editable(rescued)
        }
    }

    // One pending write reads the current draft: a held slider cannot defer the rescue forever
    // or reallocate a timer per detent.
    if (BuilderDraft.stashes(mode)) {
        LaunchedEffect(Unit) {
            val stash = DraftStashCoalescer(this) { templates.stashDraft(draft) }
            snapshotFlow { draft }.drop(1).collect { stash.changed() }
        }
    }

    // The undo bar: `SnackbarDuration.Long` is Material's ten seconds, the house window.
    LaunchedEffect(removedSet) {
        val removed = removedSet ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = L10n.tr("Set removed"),
            actionLabel = L10n.tr("Undo"),
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            update { current ->
                current.withSets { sets ->
                    sets.toMutableList().apply { add(minOf(removed.index, size), removed.set) }
                }
            }
        }
        removedSet = null
    }

    val isDirty = BuilderDraft.isDirty(draft, initialDraft)
    // Folded ONCE per draft: title, top-bar Save and the foot all ask, and each walks the sets.
    val validationIssue = draft.validationIssue
    val canSave = validationIssue == null
    val subtitle = validationIssue ?: PlanMath.subtitleLine(draft.plan)

    fun discard() {
        // A stash outliving an explicit Cancel would return as a ghost next time.
        templates.clearDraft()
        onDone(null)
    }

    fun cancel() {
        if (isDirty) showDiscard = true else discard()
    }

    fun save() {
        focus.clearFocus()
        if (!canSave || saving) return
        saving = true
        scope.launch {
            try {
                // ONE entry point for create and edit; the store's own `create`/`update` ask for notification
                // permission once, on the first Save of a routine with reminders.
                val saved = templates.save(draft)
                // A rolled-back save leaves the document OPEN with the error inline; the stash survives for
                // the retry.
                if (saved == null) return@launch
                onDone(saved.id)
            } finally {
                saving = false
            }
        }
    }

    // **Predictive back IS Cancel**, asking only when there is something to lose. No system back
    // animation: a document that may ask "discard?" must not first animate itself away.
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
                            // **The price of every edit, always visible, and the disabled Save's only NEARBY
                            // explanation**: it swaps to the validation issue the instant Save refuses.
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (canSave) {
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
                        TextButton(
                            onClick = { save() },
                            enabled = canSave && !saving,
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
            // **An eager Column, NOT a LazyColumn**: adding a set scrolls to its row, which may be below
            // the fold, where a lazy list has not built it. A dozen rows cost nothing to lay out eagerly.
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
                // NAME opens the document in every mode, so creating and editing share a first screenful.
                Block {
                    NameSection(draft.plan.name) { name ->
                        update { it.copy(plan = it.plan.copy(name = name)) }
                    }
                }

                Block {
                    RhythmSection(RhythmValues.of(draft.plan), update = update)
                }

                Block {
                    val percentBandsVary = BuilderDraft.percentBandsVary(draft)
                    // Folded once and compared by VALUE: a name keystroke redraws no row, and a set edit
                    // redraws only its own.
                    val rowContext = SetRowContext.of(draft.plan)
                    val sets = draft.plan.sets
                    // A plain row, never a pinned section header.
                    CapsLabel(tr("SETS"))
                    sets.forEachIndexed { index, set ->
                        // Keyed by id, so a reorder MOVES a row's state instead of handing the open accordion to
                        // whichever set lands in its slot.
                        key(set.id) {
                            val id = set.id
                            Box(
                                Modifier.onGloballyPositioned { anchors.placed(id, it) },
                            ) {
                                SetRowView(
                                    set = set,
                                    context = rowContext,
                                    isExpanded = expanded == id,
                                    maxes = templates.maxTable,
                                    percentBandsVary = percentBandsVary,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < sets.size - 1,
                                    onTap = { expanded = if (expanded == id) null else id },
                                    onEditGrip = { editingSet = id },
                                    onMoveUp = { update { it.movingSet(id, -1) } },
                                    onMoveDown = { update { it.movingSet(id, 1) } },
                                    onDuplicate = {
                                        val copyID = UUID.randomUUID()
                                        update { it.duplicatingSet(id, copyID) }
                                        expanded = copyID
                                    },
                                    onRemove = {
                                        val current = draftState.value.plan.sets
                                        val at = current.indexOfFirst { it.id == id }
                                        if (at >= 0) {
                                            update { draft -> draft.withSets { all -> all.filterNot { it.id == id } } }
                                            if (expanded == id) expanded = null
                                            removedSet = RemovedSet(at, current[at])
                                        }
                                    },
                                    // BY ID, so an edit in flight during a reorder lands on its own set.
                                    onSetChange = { updated -> update { it.replacingSet(updated) } },
                                )
                            }
                        }
                    }
                    AddSetRow {
                        // Duplicates the previous set AND opens it: editing the copy is the next thing you do, and
                        // uniform routines cost nothing extra.
                        val new = (draftState.value.plan.sets.lastOrNull() ?: SetPlan())
                            .copy(id = UUID.randomUUID())
                        update { draft -> draft.withSets { it + new } }
                        expanded = new.id
                        // NEXT frame, once the row exists: otherwise the new set opened a screen above while you
                        // stayed parked at the button.
                        scope.launch {
                            delay(16)
                            scrollTo(new.id)
                        }
                    }
                }

                Block {
                    val maxes = templates.maxTable
                    val totals = remember(draft, maxes) { TotalsValues.of(draft, maxes) }
                    TotalsBar(totals)
                }

                Block {
                    EveryDaySection(
                        EveryDayValues.of(draft),
                        notificationsRefused = settings.deniedNotifications,
                        update = update,
                    )
                }

                FineTuningSection(FineTuningValues.of(draft.plan), update = update)

                Block {
                    FinishBlock(
                        validationIssue = validationIssue,
                        saving = saving,
                        mode = mode,
                        templates = templates,
                        onSave = { save() },
                        onDeleted = { onDone(null) },
                    )
                }
              }
            }
        }

        // **An OVERLAY, not another presentation**: the panel hangs off the top of the screen, and
        // its state lives at this root, not on the token.
        val editingID = editingSet
        if (editingID != null) {
            val editing = draft.plan.sets.firstOrNull { it.id == editingID }
            if (editing != null) {
                GripPanel(
                    grip = editing.grip,
                    onChange = { grip ->
                        update { draft ->
                            draft.withSets { sets -> sets.map { if (it.id == editingID) it.copy(grip = grip) else it } }
                        }
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
        // Only when there is something to lose: a discarding back gesture, unlike everything else
        // here, has no undo.
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

/// One document block: its heading and controls, grouped tighter than the blocks around it.
@Composable
private fun Block(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
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
    validationIssue: String?,
    saving: Boolean,
    mode: BuilderMode,
    templates: TemplateStore,
    onSave: () -> Unit,
    onDeleted: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        validationIssue?.let { issue ->
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
            // The document STAYS OPEN on a rollback: closing destroys the form and the routine with it.
            Text(
                tr("That change couldn't be saved — the routine is still here. Try again."),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.alarm,
            )
        }

        val editingID = mode.editingID
        if (editingID != null) {
            // No confirmation dialog: Today arms a 10 s undo bar instead.
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
            // Saves and STOPS: building a routine and doing one are two decisions, and Start lives on
            // Today (Nuri, 2026-08-09).
            PrimaryButton(
                title = tr("Save routine"),
                icon = Icons.Filled.Check,
                enabled = validationIssue == null && !saving,
                onClick = onSave,
            )
        }
    }
}

/// The one set the undo bar can put back — held with its ORIGINAL index.
private data class RemovedSet(val index: Int, val set: SetPlan)

@Preview(name = "Builder · first run", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun BuilderFirstRunPreview() {
    GetAGripTheme {
        // Drawn from a draft: a preview cannot reach a store.
        BuilderDocumentPreview(RoutineDraft.blank())
    }
}

@Preview(name = "Builder · edit", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun BuilderEditPreview() {
    GetAGripTheme {
        BuilderDocumentPreview(RoutineDraft.starter)
    }
}

/// A store-free rehearsal of the document for previews.
@Composable
private fun BuilderDocumentPreview(seed: RoutineDraft) {
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
        val update: DraftUpdate = { transform -> draft = transform(draft) }
        NameSection(draft.plan.name) { draft = draft.copy(plan = draft.plan.copy(name = it)) }
        RhythmSection(RhythmValues.of(draft.plan), update = update)
        CapsLabel(tr("SETS"))
        draft.plan.sets.forEachIndexed { index, set ->
            SetRowView(
                set = set,
                context = SetRowContext.of(draft.plan),
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
        TotalsBar(TotalsValues.of(draft, run.nuri.getagrip.engine.MaxTable()))
        EveryDaySection(EveryDayValues.of(draft), update = update)
        FineTuningSection(FineTuningValues.of(draft.plan), update = update)
    }
}
