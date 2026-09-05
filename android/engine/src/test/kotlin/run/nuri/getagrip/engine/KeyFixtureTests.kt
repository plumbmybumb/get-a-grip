// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/// The canonical key IS the registry — there is no grip library to register in — so
/// forking one grip into two trend series, or merging two into one, is the worst
/// corruption this app can inflict on history. `Fixtures/keys/*.json` pins the key, the
/// per-hand `MaxTable` key and the frozen English display copy on both platforms.
///
/// Field reading for `keys/grip-keys.json`: `name` / `shortName` are the FINGER SET's
/// (`FingerSet.name` / `.shortName`); `line`, `displayName` and `shortForm` are the whole
/// grip's (`GripSpec.line` / `.displayName` / `.shortName`). `line` differs from
/// `displayName` in the POSITION's case only.
class KeyFixtureTests {

    /// The fixture copy is English, and `L10n.tr` formats through the default locale.
    @BeforeTest
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun gripKeysAndDisplayCopy() {
        for (row in Fixtures.load("keys/grip-keys.json").jsonArray.map { it.jsonObject }) {
            fun text(key: String) = row.getValue(key).jsonPrimitive.content
            val grip = GripSpec(
                edgeMM = row.getValue("edgeMM").jsonPrimitive.content.toInt(),
                fingers = FingerSet.fromToken(text("fingers")),
                position = GripPosition(text("position")),
            )
            val label = text("key")

            assertEquals(label, grip.key, "key")
            // The token must round-trip: rebuilding the grip from the fixture's own
            // token cannot change the key it was written for.
            assertEquals(text("fingers"), grip.fingers.token, "$label token")

            val maxKey = row.getValue("maxKey").jsonObject
            assertEquals(
                maxKey.getValue("left").jsonPrimitive.content,
                MaxTable.key(grip.key, Side.left), "$label maxKey left",
            )
            assertEquals(
                maxKey.getValue("right").jsonPrimitive.content,
                MaxTable.key(grip.key, Side.right), "$label maxKey right",
            )
            assertEquals(
                maxKey.getValue("both").jsonPrimitive.content,
                MaxTable.key(grip.key, Side.both), "$label maxKey both",
            )

            assertEquals(text("name"), grip.fingers.name, "$label name")
            assertEquals(text("shortName"), grip.fingers.shortName, "$label shortName")
            assertEquals(text("line"), grip.line, "$label line")
            assertEquals(text("displayName"), grip.displayName, "$label displayName")
            assertEquals(text("shortForm"), grip.shortName, "$label shortForm")
        }
    }

    /// The prefills are a product spec, so their keys get pinned like one: these are what
    /// every future log, trend and max for those routines will join on.
    @Test
    fun seedRoutineKeys() {
        val doc = Fixtures.load("keys/starter-keys.json").jsonObject
        assertEquals(
            doc.getValue("starter").jsonArray.map { it.jsonPrimitive.content },
            RoutineDraft.starter.plan.sets.map { it.grip.key },
        )
        assertEquals(
            doc.getValue("maxDay").jsonArray.map { it.jsonPrimitive.content },
            RoutineDraft.maxDay.plan.sets.map { it.grip.key },
        )
    }

    /// The slot is content-keyed, so 08:00 has the same notification identifier on both
    /// of a user's devices with nothing synced. (The 0…1439 clamp lives in
    /// `blob/ReminderTime.json`, so every row here is already in range.)
    @Test
    fun reminderSlots() {
        for (row in Fixtures.load("keys/reminder-slots.json").jsonArray.map { it.jsonObject }) {
            val minutes = row.getValue("minutesFromMidnight").jsonPrimitive.content.toInt()
            val time = ReminderTime(minutes)
            assertEquals(minutes, time.minutesFromMidnight)
            assertEquals(row.getValue("slot").jsonPrimitive.content, time.slot, "slot for $minutes")
            assertEquals(row.getValue("hour").jsonPrimitive.content.toInt(), time.hour, "hour for $minutes")
            assertEquals(
                row.getValue("minute").jsonPrimitive.content.toInt(), time.minute,
                "minute for $minutes",
            )
        }
    }
}
