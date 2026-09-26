// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.history

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerStrain
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.store.LocalHistoryFeed
import run.nuri.getagrip.store.LocalTemplateStore
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.components.ChipGrid
import run.nuri.getagrip.ui.components.EffortPicker
import run.nuri.getagrip.ui.components.DialTrack
import run.nuri.getagrip.ui.components.PrimaryButton
import run.nuri.getagrip.ui.components.SubmissionState
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics

/// Everything the log sheet decides, as a VALUE, so the Save rule and day arithmetic are
/// testable without a UI tree.
///
/// **Save stays enabled after two taps.** Kind + day alone is a complete log; the strain axes
/// are optional and store nil. `daysAgo` is clamped (a negative files training in the future),
/// and the store clamps again.
data class SessionLogDraft(
    /// No default: volume and limit are different days, and a pre-selection would mis-describe
    /// the week half the time.
    val kind: SessionKind? = null,
    val daysAgo: Int = 0,
    /// Two hours pre-filled: close enough beats nothing for the load model, and is one drag from right.
    val minutes: Int = 120,
    val rpe: RPE? = null,
    val fingerStrain: FingerStrain? = null,
) {
    /// The whole Save rule. `isLoggedByHand` is the engine's gate: `hang` belongs to the runner and
    /// `benchmark` to `recordMax`.
    val canSave: Boolean get() = kind?.isLoggedByHand == true

    fun day(today: DayStamp): DayStamp = today - daysAgo.coerceAtLeast(0)

    /// A climb settles the day; a manual hang fills one session share — so the copy follows the kind.
    val consequence: String
        get() {
            val day = if (daysAgo == 0) L10n.tr("today") else L10n.tr("yesterday")
            return when (kind) {
                SessionKind.hangManual ->
                    L10n.tr(
                        "Counts as one session for %s.",
                        day,
                    )
                SessionKind.climbVolume, SessionKind.climbLimit ->
                    L10n.tr(
                        "Marks %s as trained and stops its reminders.",
                        day,
                    )
                else -> L10n.tr("Choose a session kind to see how it counts.")
            }
        }

    companion object {
        /// The dial's stops, in minutes; the ladder states every duration it can produce.
        val durationStops: List<Double> = listOf(30.0, 45.0, 60.0, 90.0, 120.0, 150.0, 180.0, 240.0)

        val kinds: List<SessionKind> =
            listOf(SessionKind.climbVolume, SessionKind.climbLimit, SessionKind.hangManual)

        fun durationLabel(minutes: Int): String = when (minutes) {
            30 -> L10n.tr("30m")
            45 -> L10n.tr("45m")
            60 -> L10n.tr("1h")
            90 -> L10n.tr("1h30")
            120 -> L10n.tr("2h")
            150 -> L10n.tr("2h30")
            180 -> L10n.tr("3h")
            240 -> L10n.tr("4h")
            else -> "${minutes}m"
        }
    }
}

/// Logging a session after the fact — at the climbing gym or away from the gauge.
///
/// Nothing to start or time: on the wall for two hours with the phone in a bag, the app cannot
/// watch, and a pocket timer is one more thing to stop. It records what already happened,
/// which is why it can name yesterday.
///
/// TRANSLATION NOTE: iOS uses a `NavigationStack` sheet with toolbar Cancel/Save. A bottom
/// sheet has no toolbar, so the actions sit at its foot, under the thumb.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionLogSheet(onClose: () -> Unit) {
    val palette = LocalGripPalette.current
    val templates = LocalTemplateStore.current
    val feed = LocalHistoryFeed.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var draft by remember { mutableStateOf(SessionLogDraft()) }
    var failed by remember { mutableStateOf(false) }
    val submission = remember { SubmissionState() }

    fun save() {
        if (submission.isRunning) return
        val submitted = draft
        val kind = submitted.kind ?: return
        failed = false
        submission.launch(scope) {
            val log = templates.recordLoggedSession(
                kind = kind,
                daysAgo = submitted.daysAgo,
                minutes = submitted.minutes,
                rpe = submitted.rpe,
                fingerStrain = submitted.fingerStrain,
            )
            if (log == null) {
                // STAYS OPEN on a rollback: dismissing loses the two decisions and implies success.
                failed = true
                return@launch
            }
            // The feed has no live query; a write that skipped this would stay invisible.
            feed.refresh()
            // The haptic names its cause: the session that actually landed.
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onClose()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = palette.field,
        shape = RoundedCornerShape(topStart = Metrics.radiusSheet, topEnd = Metrics.radiusSheet),
    ) {
        Column(Modifier.padding(horizontal = Metrics.hPadding)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Log a session"), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
                TextButton(onClick = onClose) { Text(tr("Cancel"), color = palette.inkSecondary) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KindBlock(draft.kind) { draft = draft.copy(kind = it) }

                /// Today or yesterday only. A full date picker would be the heaviest control here, for a case
                /// that barely happens and that History already shows as missing.
                val dayChoices: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(tr("Today"), draft.daysAgo == 0, Modifier.weight(1f)) { draft = draft.copy(daysAgo = 0) }
                        Chip(tr("Yesterday"), draft.daysAgo == 1, Modifier.weight(1f)) { draft = draft.copy(daysAgo = 1) }
                    }
                }
                if (LocalDensity.current.fontScale > 1.3f) {
                    Block(tr("WHEN"), dayChoices)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CapsLabel(tr("WHEN"))
                        dayChoices()
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CapsLabel(tr("HOW LONG"))
                        Spacer(Modifier.weight(1f))
                        Text(SessionLogDraft.durationLabel(draft.minutes),
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold, color = palette.inkPrimary)
                    }
                    // No tap-to-type: fifteen minutes is noise in a five-point self-report, and a keyboard would
                    // cost the fast path.
                    DialTrack(
                        value = draft.minutes.toDouble(),
                        values = SessionLogDraft.durationStops,
                        format = { SessionLogDraft.durationLabel(it.toInt()) },
                        spokenUnit = "",
                        label = tr("How long the session was"),
                    ) { draft = draft.copy(minutes = it.toInt()) }
                }

                EffortPicker(draft.rpe?.rawValue, RPE.entries.map { it.displayName },
                    tr("How hard did it feel?"), "effort.overall") {
                    draft = draft.copy(rpe = it?.let(RPE::fromRaw))
                }
                EffortPicker(draft.fingerStrain?.rawValue, FingerStrain.entries.map { it.displayName },
                    tr("On your fingers"), "effort.fingers") {
                    draft = draft.copy(fingerStrain = it?.let(FingerStrain::fromRaw))
                }

                Text(
                    draft.consequence,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )

                if (failed) {
                    Text(
                        tr("Couldn't save. Try again."),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = palette.alarm,
                    )
                }
            }
            PrimaryButton(tr("Save"), modifier = Modifier.padding(vertical = 10.dp),
                enabled = draft.canSave && !submission.isRunning, onClick = ::save)
        }
    }
}

@Composable
private fun KindBlock(selected: SessionKind?, onSelect: (SessionKind) -> Unit) {
    val palette = LocalGripPalette.current
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CapsLabel(tr("WHAT KIND OF SESSION"), Modifier.weight(1f))
            val disclosureState = tr(if (expanded) "Expanded" else "Collapsed")
            IconButton(onClick = { expanded = !expanded },
                modifier = Modifier.semantics { stateDescription = disclosureState }) {
                Icon(Icons.Outlined.Info, tr("About session types"), tint = palette.inkSecondary)
            }
        }
        // `ChipGrid`: a third chip tips a plain Row past legibility at accessibility sizes.
        ChipGrid(
            base = 3,
            content = SessionLogDraft.kinds.map { option ->
                { cellModifier: Modifier ->
                    Chip(option.shortName, selected == option, cellModifier) { onSelect(option) }
                }
            },
        )
        // The SELECTED kind's explainer, or all while undecided: "volume" and "limit" are jargon, and
        // a mis-picked chip mis-describes the week.
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (selected?.let { listOf(it) } ?: SessionLogDraft.kinds).forEach { option ->
                Text(
                    if (selected == null) tr("%s — %s", option.shortName, option.explainer)
                    else option.explainer,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                )
            }
        }
    }
}

@Composable
private fun Block(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsLabel(label)
        content()
    }
}

@Preview(name = "Session log body", showBackground = true, widthDp = 380)
@Composable
private fun SessionLogPreview() {
    GetAGripTheme {
        val palette = LocalGripPalette.current
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            KindBlock(SessionKind.climbLimit) {}
            Text(
                SessionLogDraft(kind = SessionKind.climbLimit).consequence,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
            )
        }
    }
}
