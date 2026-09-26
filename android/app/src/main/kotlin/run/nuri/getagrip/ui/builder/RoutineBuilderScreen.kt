// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SecondaryButton
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.InstrumentSurface
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// **THE ROUTINE BUILDER — one screen, three pages: Rhythm · Sets · Schedule.**
///
/// Page 1 is what every set follows (name, hold, rest, break, hands, target load) and fits one
/// phone screen while creating; page 2 is the sets, compact rows that open in place; page 3 is
/// when you train and the fine tuning. Constants still come before variables — the reason
/// RHYTHM always sat above the sets.
///
/// **The wizard IS the editor**: creating walks Back / Next with page dots and ends in "Save
/// routine"; editing jumps with a switcher and saves from any page. Nothing else differs, so
/// there is no second surface to keep in sync.
///
/// **Nothing touches the store until Save.** The builder holds a DRAFT VALUE: Cancel IS undo,
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
    val creating = mode.isCreating
    val fontScale = LocalDensity.current.fontScale
    val largeText = fontScale >= 1.3f

    // **The seed is read ONCE, without observing the store.** `templates.routines` republishes
    // on every write anywhere (a session logged, a sync landing); keyed on it, the draft would
    // reset mid-edit. `withoutReadObservation` gives the guarantee iOS gets from seeding a child
    // view's `@State` in its init.
    val seed = remember(mode) {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
            when (mode) {
                // BLANK, always (Nuri, 2026-08-10): the plan is yours, not somebody's to pick.
                BuilderMode.FirstRun, BuilderMode.AddAnother -> RoutineDraft.blank()
                // Missing means deleted while Today still showed it. A blank draft is non-destructive:
                // the store CREATES rather than resurrects, and nothing typed is lost.
                is BuilderMode.Edit ->
                    templates.routines.firstOrNull { it.id == mode.id }?.draft ?: RoutineDraft.blank()
            }
        }
    }

    // The Rhythm page edits ONE routine-wide band, so a band every set shares is folded up to the
    // routine for editing (and demoted again on Save).
    val editableSeed = remember(seed) { BuilderDraft.opening(seed) }
    // **SAVED, not remembered — for creating AND editing.** Rotation destroys every `remember`,
    // and the rescue stash lags up to half a second and does not exist for edits.
    val draftState = rememberSaveable(seed, stateSaver = RoutineDraftSaver) { mutableStateOf(editableSeed) }
    var draft by draftState
    /// The seed, kept only to answer "is this dirty".
    val initialDraft = editableSeed
    /// Every section writes through this ONE remembered door — see `DraftUpdate`.
    val update: DraftUpdate = remember(draftState) { { transform -> draftState.value = transform(draftState.value) } }

    var page by rememberSaveable { mutableStateOf(BuilderPage.Rhythm) }
    /// At most ONE open set row, which guarantees one dense control cluster on screen at a time.
    var expanded by rememberSaveable { mutableStateOf<UUID?>(null) }
    /// Which set's grip the panel is editing. HERE, not on the token: the panel hangs off the top
    /// of the screen, out of any scrolling row's reach.
    var editingSet by rememberSaveable { mutableStateOf<UUID?>(null) }
    /// A set just added, for the Sets page to scroll to once its row exists.
    var scrollToSet by remember { mutableStateOf<UUID?>(null) }
    /// Whether this builder has been OPENED (stash swept). Saved, so a rotation does not swap in
    /// an older stash.
    var opened by rememberSaveable { mutableStateOf(false) }
    /// Held with its ORIGINAL id and index, so Undo restores the same row in the same place.
    var removedSet by remember { mutableStateOf<RemovedSet?>(null) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    /// A Save in flight: both Saves dim and a second tap is refused. The write is not instant and
    /// a new draft has no id yet, so two taps used to create the routine TWICE.
    var saving by remember { mutableStateOf(false) }

    val reduceMotion = rememberReduceMotion()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(mode) {
        if (opened) return@LaunchedEffect
        opened = true
        // A rescue copy exists only if a previous session died mid-build (Save and Cancel clear it).
        // `initialDraft` stays at the seed, so a restored builder is DIRTY and Cancel still asks.
        if (BuilderDraft.stashes(mode)) {
            val rescued = templates.restoreDraft()
            if (rescued != null && rescued != draft) draft = BuilderDraft.opening(rescued)
        }
    }

    // One pending write reads the current draft: a held stepper cannot defer the rescue forever
    // or reallocate a timer per step.
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
    // Folded ONCE per draft: title, both Saves and the subtitle all ask, and each walks the sets.
    val validationIssue = draft.validationIssue
    val canSave = validationIssue == null
    val subtitle = BuilderDraft.subtitle(draft, creating, page)

    fun go(target: BuilderPage) {
        if (target == page) return
        focus.clearFocus()
        page = target
    }

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
                // ONE entry point for create and edit; the store asks for notification permission
                // once, on the first Save of a routine with reminders.
                val saved = templates.save(draft)
                // A rolled-back save leaves the builder OPEN with the error inline.
                if (saved == null) return@launch
                onDone(saved.id)
            } finally {
                saving = false
            }
        }
    }

    fun addSet(new: SetPlan) {
        update { current -> current.withSets { it + new } }
        expanded = new.id
        scrollToSet = new.id
    }

    // **Predictive back**: while creating it walks back a page, as the Back button does; on the
    // first page, and always while editing, it IS Cancel, asking only when there is something to
    // lose. No system back animation: a builder that may ask "discard?" must not first animate
    // itself away.
    PredictiveBackHandler(enabled = editingSet == null) { progress ->
        try {
            progress.collect { }
            val previous = page.previous
            if (creating && previous != null) go(previous) else cancel()
        } catch (_: CancellationException) {
            // Released short of the threshold: nothing to do.
        }
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (creating) page.title else tr("Edit routine"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary,
                            )
                            // **The price of every edit, on every page, and the disabled Save's only
                            // NEARBY explanation**: it swaps to the validation issue the instant Save refuses.
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (canSave) palette.inkSecondary else palette.armed,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = { cancel() }) { Text(tr("Cancel")) }
                    },
                    actions = {
                        TextButton(onClick = { save() }, enabled = canSave && !saving) {
                            Text(tr("Save"), fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.card,
                        titleContentColor = palette.inkPrimary,
                    ),
                    // 56, not 64: page 1 has to fit a 360 × 740 dp phone, and the title is two short lines.
                    // Large text grows the bar with the words rather than clipping them.
                    expandedHeight = if (largeText) 64.dp * minOf(fontScale, 2f) else 56.dp,
                )
            },
            bottomBar = {
                if (creating) {
                    CreateNavigation(
                        page = page,
                        canSave = canSave && !saving,
                        onBack = { page.previous?.let(::go) },
                        onNext = { page.next?.let(::go) },
                        onSave = { save() },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                if (!creating) {
                    // Pinned: the page scrolls under it, never it with the page.
                    BuilderPageSwitcher(
                        current = page,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.hPadding)
                            .padding(top = 8.dp, bottom = 8.dp)
                            .widthIn(max = Metrics.maxContentWidth)
                            .wrapContentWidth(Alignment.CenterHorizontally),
                    ) { go(it) }
                }
                // A slide in the direction of travel; a cross-fade under Reduce Motion. No swipe
                // between pages: the steppers and set rows own horizontal touches.
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(Motion.state(true)) togetherWith fadeOut(Motion.state(true))
                        } else {
                            val forward = targetState.ordinal > initialState.ordinal
                            (slideInHorizontally(Motion.state(false)) { if (forward) it else -it } +
                                fadeIn(Motion.state(false))) togetherWith
                                (slideOutHorizontally(Motion.state(false)) { if (forward) -it else it } +
                                    fadeOut(Motion.state(false)))
                        }
                    },
                    label = "builderPage",
                    modifier = Modifier.fillMaxSize(),
                ) { shown ->
                    // A fresh scroll state per page, so every page opens at its top.
                    val scrollState = rememberScrollState()
                    val anchors = remember { BuilderScrollAnchors() }
                    val onePage = shown == BuilderPage.Rhythm
                    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned(anchors::contentPlaced)
                                .padding(horizontal = Metrics.hPadding)
                                .padding(top = if (onePage) 8.dp else 12.dp, bottom = if (onePage) 12.dp else 28.dp)
                                .widthIn(max = Metrics.maxContentWidth),
                            verticalArrangement = Arrangement.spacedBy(if (onePage) 12.dp else 18.dp),
                        ) {
                            when (shown) {
                                BuilderPage.Rhythm -> {
                                    NameSection(draft.plan.name) { name ->
                                        update { it.copy(plan = it.plan.copy(name = name)) }
                                    }
                                    RhythmSection(RhythmValues.of(draft.plan), update = update)
                                    RoutineLoadBlock(
                                        lo = draft.plan.targetLoPercent,
                                        hi = draft.plan.targetHiPercent,
                                        setsVary = draft.plan.sets.any { it.hasTarget || it.hasPercentTarget },
                                        missingMaxes = draft.plan.targetPercentBand != null &&
                                            PlanMath.missingBenchmarkGripCount(draft.plan, templates.maxTable) > 0,
                                        update = update,
                                    )
                                }
                                BuilderPage.Sets -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val percentBandsVary = BuilderDraft.percentBandsVary(draft)
                                        // Folded once and compared by VALUE: a set edit redraws only its own row.
                                        val rowContext = SetRowContext.of(draft.plan)
                                        val sets = draft.plan.sets
                                        sets.forEachIndexed { index, set ->
                                            // Keyed by id, so a reorder MOVES a row's state instead of handing
                                            // the open accordion to whichever set lands in its slot.
                                            key(set.id) {
                                                val id = set.id
                                                Box(Modifier.onGloballyPositioned { anchors.placed(id, it) }) {
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
                                                                update { d -> d.withSets { all -> all.filterNot { it.id == id } } }
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
                                    }
                                    SetButtons(
                                        canDuplicate = draft.plan.sets.isNotEmpty(),
                                        // A FRESH set that follows the Rhythm page.
                                        onAdd = { addSet(SetPlan()) },
                                        onDuplicateLast = {
                                            draftState.value.plan.sets.lastOrNull()?.let { addSet(it.copy(id = UUID.randomUUID())) }
                                        },
                                    )
                                    // Advisories about the sets — never the totals, which the subtitle states.
                                    if (BuilderDraft.isVeryLong(draft)) {
                                        Advisory(tr("This routine runs over an hour."), Icons.Outlined.Schedule)
                                    }
                                    // NEXT frame, once the row exists: otherwise the new set opened a screen
                                    // above while you stayed parked at the button.
                                    val target = scrollToSet
                                    LaunchedEffect(target) {
                                        if (target == null) return@LaunchedEffect
                                        delay(16)
                                        anchors.offset(target)?.let { y ->
                                            if (reduceMotion) scrollState.scrollTo(y) else scrollState.animateScrollTo(y)
                                        }
                                        scrollToSet = null
                                    }
                                }
                                BuilderPage.Schedule -> {
                                    EveryDaySection(
                                        EveryDayValues.of(draft),
                                        notificationsRefused = settings.deniedNotifications,
                                        update = update,
                                    )
                                    FineTuningSection(FineTuningValues.of(draft.plan), update = update)
                                    val editingID = mode.editingID
                                    if (editingID != null) {
                                        DeleteBlock(editingID, templates, onDeleted = { onDone(null) })
                                    }
                                }
                            }
                            if (templates.saveError != null) {
                                // The builder STAYS OPEN on a rollback: closing destroys the routine with it.
                                Text(
                                    tr("Couldn't save the change. Try again."),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.alarm,
                                )
                            }
                        }
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
                        update { d ->
                            d.withSets { sets -> sets.map { if (it.id == editingID) it.copy(grip = grip) else it } }
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

/// The routine's target, in one card with no caps label (the row names itself, and page 1 has
/// to fit one screen), plus the one warning that belongs to it.
@Composable
private fun RoutineLoadBlock(
    lo: Double?,
    hi: Double?,
    setsVary: Boolean,
    missingMaxes: Boolean,
    update: DraftUpdate,
) {
    val palette = LocalGripPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InstrumentSurface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card) {
            RoutineTargetRow(
                lo = lo,
                hi = hi,
                setsVary = setsVary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { band -> update { BuilderDraft.withRoutineBand(it, band) } }
        }
        if (missingMaxes) {
            Advisory(tr("Some grips have no max yet, so their sets have no target."), Icons.Outlined.WarningAmber)
        }
    }
}

@Composable
private fun Advisory(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val palette = LocalGripPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = palette.armed, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = palette.armed)
    }
}

/// "Add a set" and, once one exists, "Duplicate last set" — side by side while both labels keep
/// one line, stacked when they would not.
@Composable
private fun SetButtons(canDuplicate: Boolean, onAdd: () -> Unit, onDuplicateLast: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val density = LocalDensity.current
    val add = tr("Add a set")
    val duplicate = tr("Duplicate last set")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widest = with(density) {
            listOf(add, duplicate).maxOf { measurer.measure(it, style).size.width }.toDp()
        }
        // Label + icon + gap + padding, per half.
        val sideBySide = !canDuplicate || (widest + 18.dp + 8.dp + 32.dp) * 2 + 10.dp <= maxWidth
        if (sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashedButton(add, Icons.Filled.Add, Modifier.weight(1f), onAdd)
                if (canDuplicate) DashedButton(duplicate, Icons.Outlined.ContentCopy, Modifier.weight(1f), onDuplicateLast)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DashedButton(add, Icons.Filled.Add, Modifier.fillMaxWidth(), onAdd)
                DashedButton(duplicate, Icons.Outlined.ContentCopy, Modifier.fillMaxWidth(), onDuplicateLast)
            }
        }
    }
}

@Composable
private fun DashedButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalGripPalette.current
    val outline = palette.inkTertiary.copy(alpha = 0.45f)
    Row(
        modifier
            .heightIn(min = 50.dp)
            .drawBehind {
                val radius = Metrics.radiusCard.toPx()
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.graphite, modifier = Modifier.size(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.graphite,
        )
    }
}

/// Creating's foot: where you are, then Back and Next — or "Save routine" in Next's place on the
/// last page. Side by side while Back keeps its one line; stacked, forward action first, at the
/// text sizes where it would wrap.
@Composable
private fun CreateNavigation(
    page: BuilderPage,
    canSave: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val buttonHeight: Dp = Metrics.buttonHeight
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Metrics.hPadding)
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BuilderPageDots(page)
        val back: @Composable (Modifier) -> Unit = { m ->
            if (page.previous != null) {
                SecondaryButton(
                    tr("Back"),
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    modifier = m.heightIn(min = buttonHeight),
                    onClick = onBack,
                )
            }
        }
        val forward: @Composable (Modifier) -> Unit = { m ->
            if (page.next != null) {
                PrimaryButton(tr("Next"), modifier = m, onClick = onNext)
            } else {
                PrimaryButton(tr("Save routine"), modifier = m, icon = Icons.Filled.Check, enabled = canSave, onClick = onSave)
            }
        }
        Box(Modifier.widthIn(max = Metrics.maxContentWidth)) {
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    forward(Modifier.fillMaxWidth())
                    back(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    back(Modifier)
                    forward(Modifier.weight(1f))
                }
            }
        }
    }
}

/// Editing's last block. No confirmation dialog: Today arms a 10 s undo bar instead.
@Composable
private fun DeleteBlock(editingID: UUID, templates: TemplateStore, onDeleted: () -> Unit) {
    val palette = LocalGripPalette.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            tr("Its sessions are deleted too."),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkTertiary,
        )
    }
}

/// The one set the undo bar can put back — held with its ORIGINAL index.
private data class RemovedSet(val index: Int, val set: SetPlan)
