// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.ui.history.ShareCache
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// `<cache>/shared` holds COPIES of somebody's training history, written for one share and —
/// until this rule — never removed: every export ever made sat there, each in a directory of
/// its own. Plain files and a temporary directory; nothing here needs Android.
class ShareCacheTests {

    private fun export(root: File, directory: String, name: String, modified: Long): File {
        val file = File(File(root, directory).apply { mkdirs() }, name)
        file.writeText("record,workout\n")
        file.setLastModified(modified)
        return file
    }

    /// What `shareDocument` and `shareImage` do before they write: one share's files at a
    /// time, whichever sheet the share came from.
    @Test
    fun everythingIsRemovedBeforeANewExportIsWritten() {
        val root = createTempDirectory("share-cache").toFile()
        try {
            val now = 1_000_000_000_000L
            val exports = listOf(
                export(root, "0d5f", "get-a-grip-training-all-pulls.csv", now - 60_000),
                export(root, "9a1c", "get-a-grip-training-recent-summary.csv", now - 1_000),
                export(root, ".", "get-a-grip-5-weeks.png", now),
            )

            ShareCache.purge(root)

            for (file in exports) assertFalse(file.exists(), file.path)
            assertEquals(emptyList(), root.listFiles().orEmpty().toList(), "the folders go with their files")
            assertTrue(root.isDirectory, "the shared folder itself stays for the next export")
        } finally {
            root.deleteRecursively()
        }
    }

    /// At launch only what is older than a day: reading a shared link is itself what starts
    /// the process when it is not running, so emptying the folder at every start would race
    /// the very read that woke the app.
    @Test
    fun atLaunchOnlyExportsOlderThanADayAreRemoved() {
        val root = createTempDirectory("share-cache").toFile()
        try {
            val now = 1_000_000_000_000L
            val day = ShareCache.staleAfterMillis
            assertEquals(24L * 60 * 60 * 1000, day)
            val lastWeek = export(root, "old", "get-a-grip-training-all-pulls.csv", now - 7 * day)
            val justOver = export(root, "over", "get-a-grip-training-all-summary.csv", now - day - 1)
            val onTheLine = export(root, "line", "get-a-grip-training-all-summary.csv", now - day)
            val thisMorning = export(root, "new", "get-a-grip-training-recent-summary.csv", now - 3_600_000)
            val oldPicture = export(root, ".", "get-a-grip-5-weeks.png", now - 2 * day)
            // A directory's own timestamp is not its export's: this one was "touched" long
            // ago and written to a moment ago, and it is the FILE that says how old it is.
            val recentInOldFolder = export(root, "touched", "get-a-grip-workout-pulls.csv", now - 1_000)
            File(root, "touched").setLastModified(now - 30 * day)

            ShareCache.purge(root, olderThanMillis = day, now = now)

            assertFalse(lastWeek.exists())
            assertFalse(File(root, "old").exists())
            assertFalse(justOver.exists())
            assertFalse(oldPicture.exists())
            assertTrue(onTheLine.exists(), "exactly a day old is not yet older than a day")
            assertTrue(thisMorning.exists())
            assertTrue(recentInOldFolder.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    /// A share must never fail because the tidy-up could not run.
    @Test
    fun aFolderThatIsNotThereIsNothingToPurge() {
        val missing = File(createTempDirectory("share-cache").toFile().apply { deleteRecursively() }, "shared")
        ShareCache.purge(missing)
        ShareCache.purge(missing, olderThanMillis = 1, now = 2)
        assertFalse(missing.exists())
    }
}
