// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import run.nuri.getagrip.ui.theme.InstrumentSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.ReminderTime
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.ui.components.CapsLabel
import run.nuri.getagrip.ui.components.Chip
import run.nuri.getagrip.ui.components.ChipGrid
import run.nuri.getagrip.ui.components.IntChipRow
import run.nuri.getagrip.ui.l10n.tr
import run.nuri.getagrip.ui.theme.GetAGripTheme
import run.nuri.getagrip.ui.theme.LocalGripPalette
import run.nuri.getagrip.ui.theme.Metrics
import run.nuri.getagrip.ui.theme.Motion
import run.nuri.getagrip.ui.theme.rememberReduceMotion

/// EVERY DAY — how many sessions a day this routine asks for, and when to say something
/// about it.
///
/// The whole scheduling story is these few rows, and the session count DRIVES the reminder
/// rows underneath it: "twice a day" is expressed inline, in the document you are already
/// editing, rather than behind a scheduling screen you have to go and find. There is
/// deliberately no second surface where reminder times live.
@Composable
fun EveryDaySection(
    draft: RoutineDraft,
    modifier: Modifier = Modifier,
    /// **Whether the permission dialog was raised and came back NO.** Arrives as a VALUE,
    /// exactly like the palette and the grip list do, so this section still never touches a
    /// store and stays previewable.
    ///
    /// It is not the same question as "can we post right now": the ask happens on the first
    /// SAVE of a routine with reminders on, so a first-run builder is always unpermitted and
    /// has simply not been asked yet. See the two branches at the foot of the section.
    notificationsRefused: Boolean = false,
    onChange: (RoutineDraft) -> Unit,
) {
    val palette = LocalGripPalette.current
    val context = LocalContext.current

    /// Read from the system, not from a store: nothing in the store layer owns
    /// authorization, and the answer can change WHILE this screen is open — the user walks
    /// to Settings, flips the switch and comes back.
    val notificationsBlocked = remember(context) {
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /// **Blocked is not the same as refused, and the row must not say it is.** The caller's
    /// flag is the app's record that the answer came back no; the live system check above is
    /// what corrects it the moment the permission is granted in system Settings — so a user
    /// who relents needs no second visit to the builder for the note to go away.
    val showDenied = notificationsBlocked && notificationsRefused

    // Repair, not normalization: a draft whose reminder list and session count disagree (an
    // older stash, a merge from another device) would otherwise draw fewer rows than the
    // count on its face promises. The guard keeps the common path from marking an untouched
    // document dirty.
    LaunchedEffect(Unit) {
        if (draft.reminders.size != draft.sessionsPerDay) {
            onChange(draft.setSessionsPerDay(draft.sessionsPerDay))
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CapsLabel(tr("HOW OFTEN"), Modifier.padding(start = 6.dp))

        Surface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // Ritual or whenever (Nuri, 2026-08-10: "it's not a routine quite as much as
                // something I want to do whenever I want"). A chip PAIR, not a toggle,
                // because the two are peers with names — not one thing switched off.
                ChipGrid(
                    base = 2,
                    modifier = Modifier.semantics { contentDescription = L10n.tr("How often") },
                    content = listOf(
                        { cell: Modifier ->
                            Chip(tr("Daily ritual"), !draft.isOnDemand, cell) {
                                onChange(draft.copy(isOnDemand = false))
                            }
                        },
                        { cell: Modifier ->
                            Chip(tr("Whenever"), draft.isOnDemand, cell) {
                                onChange(draft.copy(isOnDemand = true))
                            }
                        },
                    ),
                )

                if (draft.isOnDemand) {
                    // The whole scheduling story, declined in one sentence. The times are
                    // KEPT in the draft — flipping back to a ritual restores them — so
                    // nothing here is destroyed, only quiet.
                    Text(
                        tr("No daily target and no reminders — it waits on Today until you feel like it."),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            tr("Sessions a day"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkPrimary,
                        )
                        // Never writes `sessionsPerDay` directly. `setSessionsPerDay` PARKS
                        // the times a lower count removes, so going 2 → 1 → 2 restores the
                        // user's own 19:15 instead of resetting it to the 19:00 default.
                        IntChipRow(
                            values = listOf(1, 2, 3, 4),
                            selection = draft.sessionsPerDay,
                            // The chips speak as bare numerals, which means nothing on their
                            // own; the container label makes "2" a sentence.
                            modifier = Modifier.semantics { contentDescription = L10n.tr("Sessions a day") },
                        ) { onChange(draft.setSessionsPerDay(it)) }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ToggleRow(
                            title = tr("Remind me"),
                            checked = draft.remindersEnabled,
                            explainer = null,
                        ) { onChange(draft.copy(remindersEnabled = it)) }

                        // The times are what the toggle is about, so they follow it rather
                        // than sitting there inert while it is off. The values stay in the
                        // draft either way — turning reminders back on restores the schedule.
                        // A disclosure is the ladder's DEFAULT motion — critically damped, and flat under
        // Reduce Motion. Compose's own default here is an unguarded 400 ms tween nothing in
        // this app chose.
        AnimatedVisibility(
            visible = draft.remindersEnabled,
            enter = expandVertically(Motion.state(rememberReduceMotion())) +
                fadeIn(Motion.state(rememberReduceMotion())),
            exit = shrinkVertically(Motion.state(rememberReduceMotion())) +
                fadeOut(Motion.state(rememberReduceMotion())),
        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                // Bounded by the LIST itself, never by the count alone: a
                                // mismatched draft must degrade to one row fewer, never to
                                // an index crash.
                                val rows = minOf(draft.sessionsPerDay, draft.reminders.size)
                                repeat(rows) { index ->
                                    ReminderRow(
                                        index = index,
                                        time = draft.reminders[index],
                                    ) { newTime ->
                                        // Deliberately NOT sorted or deduped here:
                                        // re-ordering the list under the finger would swap
                                        // the row being edited with the one below it.
                                        // `RoutineDraft.normalized` tidies on the way into
                                        // the store.
                                        val next = draft.reminders.toMutableList()
                                        next[index] = newTime
                                        onChange(draft.copy(reminders = next))
                                    }
                                }
                                if (showDenied) {
                                    // Denied is a dead end for the NOTIFICATION, never for
                                    // the setting: reminders stay ON in the draft, so
                                    // changing your mind in Settings later just works.
                                    DeniedRow()
                                } else if (notificationsBlocked) {
                                    // Not yet asked. Saying so is what makes the OS dialog
                                    // that follows the Save read as expected rather than as
                                    // an ambush — the whole reason the ask is contextual.
                                    Text(
                                        tr("Android will ask to allow notifications when you save."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(
    index: Int,
    time: ReminderTime,
    onChange: (ReminderTime) -> Unit,
) {
    val palette = LocalGripPalette.current
    var picking by remember { mutableStateOf(false) }
    // Read outside the semantics lambda, which is not composable.
    val spoken = tr("Session %d reminder time", index + 1) + ", " + time.displayText()

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { picking = true }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = spoken
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsLabel(tr("SESSION %d", index + 1), Modifier.weight(1f))
        Text(
            time.displayText(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
        )
    }

    if (picking) {
        TimePickerDialog(
            initialHour = time.hour,
            initialMinute = time.minute,
            onDismiss = { picking = false },
        ) { hour, minute ->
            picking = false
            onChange(ReminderTime(hour = hour, minute = minute))
        }
    }
}

/// Material 3 ships a `TimePicker` but no dialog around it, so this is the house wrapper.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val palette = LocalGripPalette.current
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        // The system's own 12/24-hour setting, never a hardcoded one — the same rule
        // `ReminderTime.displayText` follows with a localized formatter.
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text(tr("Reminder time"), color = palette.inkPrimary) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(tr("Set")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun DeniedRow() {
    val palette = LocalGripPalette.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("Notifications are off for Get a Grip."),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
        )
        Text(
            tr("Open Settings"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.graphite,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                }
                .padding(vertical = 12.dp)
                .semantics { role = Role.Button },
        )
    }
}

@Preview(name = "EveryDaySection", showBackground = true, widthDp = 380)
@Composable
private fun EveryDaySectionPreview() {
    GetAGripTheme {
        var draft by remember { mutableStateOf(RoutineDraft.starter) }
        Column(Modifier.padding(16.dp)) {
            EveryDaySection(draft) { draft = it }
        }
    }
}
