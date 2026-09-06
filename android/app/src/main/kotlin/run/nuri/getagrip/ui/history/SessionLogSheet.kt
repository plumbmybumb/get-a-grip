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

/// Everything the log sheet is deciding, as a VALUE — so the Save rule and the day
/// arithmetic can be asserted without a UI tree.
///
/// **Save stays enabled after two taps.** Kind + day alone is a complete log; the two strain
/// axes are optional and store nil, because the fast path must cost exactly what it costs
/// today. `daysAgo` is clamped rather than validated: a negative would file training in the
/// future, and the store clamps it again on the way in.
data class SessionLogDraft(
    /// No default. Volume and limit are genuinely different days and the app cannot guess
    /// which you had — pre-selecting one would get it wrong half the time and silently
    /// mis-describe the week, which is exactly what this feature exists to fix.
    val kind: SessionKind? = null,
    val daysAgo: Int = 0,
    /// Two hours is pre-filled: a close-enough duration is more useful to the load model than
    /// nothing at all, and it is visible and one drag from right.
    val minutes: Int = 120,
    val rpe: RPE? = null,
    val fingerStrain: FingerStrain? = null,
) {
    /// The whole Save rule, in one place. `isLoggedByHand` is the engine's own gate — `hang`
    /// belongs to the runner and `benchmark` to `recordMax`, and neither may be created here.
    val canSave: Boolean get() = kind?.isLoggedByHand == true

    fun day(today: DayStamp): DayStamp = today - daysAgo.coerceAtLeast(0)

    /// States the rule on the screen that invokes it. A climb settles the day; a manual hang
    /// only fills one session share, so this copy must follow the selected kind.
    val consequence: String
        get() {
            val day = if (daysAgo == 0) L10n.tr("today") else L10n.tr("yesterday")
            return when (kind) {
                SessionKind.hangManual ->
                    L10n.tr(
                        "That counts as one session for %s, but it does not settle the day. You can still do another session if you want one.",
                        day,
                    )
                SessionKind.climbVolume, SessionKind.climbLimit ->
                    L10n.tr(
                        "That completes %s — no reminders, and a full day on your calendar. You can still do a hang session if you want one.",
                        day,
                    )
                else -> L10n.tr("Choose a session kind to see how it counts.")
            }
        }

    companion object {
        /// The stops the dial offers. Minutes, and the ladder is what makes the control
        /// quotable — every duration it can produce is readable without touching it.
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
/// There is deliberately nothing to start and nothing to time automatically. You are on the
/// wall for two hours with the phone in a bag; the app cannot watch it, cannot measure it,
/// and pretending otherwise would mean a timer running in your pocket that you have to
/// remember to stop. So this is a record of something that already happened — which is also
/// why it can name yesterday: the realistic moment to log Tuesday's session is Wednesday
/// morning.
///
/// TRANSLATION NOTE: iOS presents a `NavigationStack` sheet with Cancel/Save in the toolbar.
/// The Android house answer is a `ModalBottomSheet` whose actions sit at the foot of its own
/// content — a bottom sheet has no toolbar, and a confirm button under the thumb is the
/// platform's own shape for a short form.
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
                // The sheet STAYS OPEN on a rollback: dismissing on failure loses the two
                // decisions and tells the user it worked.
                failed = true
                return@launch
            }
            // Everything History draws is folded from the feed, which has no live query
            // behind it — a write that skipped this would land and stay invisible.
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

                /// Today or yesterday, and nothing further back. A full date picker would be the
                /// heaviest control on this fast log to serve a case — logging Thursday's session
                /// on Sunday — that barely happens and that History can already show is missing.
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
                    // Tap-to-type belongs on a row that needs it. Two hours versus two hours
                    // fifteen is noise inside a five-point self-report, and a keyboard would cost
                    // the fast path this sheet is built around.
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
                        tr("That couldn't be saved — nothing was logged. Try again."),
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
        // `ChipGrid`, not a plain Row: a third chip is what tips this row over at
        // accessibility sizes, and the grid wraps where a row would squeeze three labels past
        // legibility.
        ChipGrid(
            base = 3,
            content = SessionLogDraft.kinds.map { option ->
                { cellModifier: Modifier ->
                    Chip(option.shortName, selected == option, cellModifier) { onSelect(option) }
                }
            },
        )
        // The explainer for the SELECTED one, or all of them while undecided — "volume" and
        // "limit" are jargon somebody may only half-know, and a mis-picked chip quietly
        // mis-describes the week this screen exists to describe honestly.
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
