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

/// EVERY DAY — how many sessions a day this routine asks for, and when to say something.
///
/// The session count DRIVES the reminder rows beneath it, so "twice a day" is expressed
/// inline. There is no second surface where reminder times live.
@Composable
fun EveryDaySection(
    /// Only what this card draws — see `EveryDayValues`.
    values: EveryDayValues,
    modifier: Modifier = Modifier,
    /// **Whether the permission dialog came back NO**, as a VALUE so this section never touches
    /// a store.
    ///
    /// Not "can we post now": the ask happens on the first SAVE with reminders on, so a first-run
    /// builder is unpermitted simply because it has not asked yet.
    notificationsRefused: Boolean = false,
    update: DraftUpdate,
) {
    val palette = LocalGripPalette.current
    val context = LocalContext.current

    /// From the system: no store owns authorization, and it can change while this screen is open.
    val notificationsBlocked = remember(context) {
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /// **Blocked is not refused.** The flag records the no; the live check clears the note the
    /// moment permission is granted in system Settings.
    val showDenied = notificationsBlocked && notificationsRefused

    // Repair: a draft whose reminders and session count disagree (older stash, synced merge)
    // would draw fewer rows than promised. Guarded so an untouched document stays clean.
    LaunchedEffect(Unit) {
        update { draft ->
            if (draft.reminders.size != draft.sessionsPerDay) draft.setSessionsPerDay(draft.sessionsPerDay)
            else draft
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CapsLabel(tr("HOW OFTEN"), Modifier.padding(start = 6.dp))

        Surface(shape = RoundedCornerShape(Metrics.radiusCard), color = palette.card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // Ritual or whenever (Nuri, 2026-08-10). A chip PAIR, not a toggle: two named peers, not one
                // thing switched off.
                ChipGrid(
                    base = 2,
                    modifier = Modifier.semantics { contentDescription = L10n.tr("How often") },
                    content = listOf(
                        { cell: Modifier ->
                            Chip(tr("Daily ritual"), !values.isOnDemand, cell) {
                                update { it.copy(isOnDemand = false) }
                            }
                        },
                        { cell: Modifier ->
                            Chip(tr("Whenever"), values.isOnDemand, cell) {
                                update { it.copy(isOnDemand = true) }
                            }
                        },
                    ),
                )

                if (values.isOnDemand) {
                    // Declined in one sentence. The times stay in the draft, so flipping back restores them.
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
                        // Via `setSessionsPerDay`, which PARKS removed times: 2 → 1 → 2 restores the user's 19:15,
                        // not the 19:00 default.
                        IntChipRow(
                            values = listOf(1, 2, 3, 4),
                            selection = values.sessionsPerDay,
                            // Bare numeral chips need the container label to make "2" a sentence.
                            modifier = Modifier.semantics { contentDescription = L10n.tr("Sessions a day") },
                        ) { count -> update { it.setSessionsPerDay(count) } }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ToggleRow(
                            title = tr("Remind me"),
                            checked = values.remindersEnabled,
                            explainer = null,
                        ) { enabled -> update { it.copy(remindersEnabled = enabled) } }

                        // The times follow the toggle; the draft keeps them either way. `Motion.state` — see
                        // `TargetBandRow`.
        AnimatedVisibility(
            visible = values.remindersEnabled,
            enter = expandVertically(Motion.state(rememberReduceMotion())) +
                fadeIn(Motion.state(rememberReduceMotion())),
            exit = shrinkVertically(Motion.state(rememberReduceMotion())) +
                fadeOut(Motion.state(rememberReduceMotion())),
        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                // Bounded by the LIST too: a mismatched draft loses a row, never crashes.
                                val rows = minOf(values.sessionsPerDay, values.reminders.size)
                                repeat(rows) { index ->
                                    ReminderRow(
                                        index = index,
                                        time = values.reminders[index],
                                    ) { newTime ->
                                        // NOT sorted or deduped here: reordering under the finger would swap the
                                        // row being edited. `RoutineDraft.normalized` tidies on save.
                                        update { draft ->
                                            if (index !in draft.reminders.indices) draft
                                            else draft.copy(reminders = draft.reminders.toMutableList()
                                                .also { it[index] = newTime })
                                        }
                                    }
                                }
                                if (showDenied) {
                                    // Denied ends the NOTIFICATION, never the setting: reminders stay ON, so
                                    // relenting in Settings later just works.
                                    DeniedRow()
                                } else if (notificationsBlocked) {
                                    // Not yet asked: saying so makes the OS dialog after Save expected, not an
                                    // ambush — why the ask is contextual.
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
        // The system's 12/24-hour setting, as `ReminderTime.displayText` follows.
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
            EveryDaySection(EveryDayValues.of(draft)) { draft = it(draft) }
        }
    }
}
