package app.aaps.plugins.aps.openAPSAIMI.utils

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The decision log is appended to on every loop tick, so an append must add and keep, nothing else. */
@OptIn(ExperimentalForeignApi::class)
class IosAimiLocalFilesAppendTest {

    private val files = aimiLocalFiles()
    private val path = NSTemporaryDirectory() + "aimi-append-" + NSUUID().UUIDString() + ".jsonl"

    @AfterTest
    fun removeFile() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    @Test
    fun appendingToAMissingFileCreatesIt() {
        assertTrue(files.appendText(path, "first\n"))

        assertEquals("first\n", files.readText(path))
    }

    @Test
    fun appendingKeepsWhatWasThereAndAddsAtTheEnd() {
        assertTrue(files.appendText(path, "first\n"))
        assertTrue(files.appendText(path, "second\n"))
        assertTrue(files.appendText(path, "é third\n"))

        assertEquals("first\nsecond\né third\n", files.readText(path))
    }
}
