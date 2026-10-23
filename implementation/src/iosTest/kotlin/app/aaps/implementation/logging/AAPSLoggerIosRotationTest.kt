package app.aaps.implementation.logging

import app.aaps.core.interfaces.logging.LTag
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many log files are kept, against real files in a temporary directory.
 *
 * The limit is tiny here so that a few lines fill a file. Errors are used because they are never
 * switched off by a tag setting.
 */
@OptIn(ExperimentalForeignApi::class)
class AAPSLoggerIosRotationTest {

    private val manager = NSFileManager.defaultManager
    private val directory = NSTemporaryDirectory() + "aaps-log-rotation-" + NSUUID().UUIDString()

    init {
        manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    }

    @AfterTest
    fun removeDirectory() = run { manager.removeItemAtPath(directory, null); Unit }

    private fun logger(maxFiles: Int) = AAPSLoggerIos(maxBytes = 10, maxFiles = maxFiles, directory = directory)

    @Test
    fun `no more than the limit of files is kept`() {
        val logger = logger(maxFiles = 4)

        repeat(20) { logger.error(LTag.CORE, "line $it") }

        assertEquals(listOf("aaps.log", "aaps.log.1", "aaps.log.2", "aaps.log.3"), IosLogFiles.allNewestFirst(directory))
    }

    /** `.1` holds the newest of the old lines, the highest number the oldest. */
    @Test
    fun `older lines move to higher numbers`() {
        val logger = logger(maxFiles = 3)

        repeat(3) { logger.error(LTag.CORE, "line $it") }

        assertTrue(read("aaps.log.1").contains("line 1"))
        assertTrue(read("aaps.log.2").contains("line 0"))
        assertTrue(read("aaps.log").contains("line 2"))
    }

    private fun read(name: String): String =
        manager.contentsAtPath("$directory/$name")?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty()
}
