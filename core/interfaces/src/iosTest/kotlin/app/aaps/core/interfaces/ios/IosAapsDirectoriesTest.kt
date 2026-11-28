package app.aaps.core.interfaces.ios

import app.aaps.core.interfaces.configuration.ExternalOptions
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS AAPS folder tree, against a temporary `Documents` folder so the real one is never touched.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAapsDirectoriesTest {

    private val manager = NSFileManager.defaultManager
    private val documents = NSTemporaryDirectory().trimEnd('/') + "/aaps-dirs-test-" + NSUUID().UUIDString()
    private val sut = IosAapsDirectories(documents)
    private val engineeringMode = ExternalOptions.ENGINEERING_MODE.filename

    init {
        manager.createDirectoryAtPath(documents, withIntermediateDirectories = true, attributes = null, error = null)
    }

    @AfterTest
    fun cleanUp() {
        manager.removeItemAtPath(documents, null)
    }

    private fun exists(path: String) = manager.fileExistsAtPath(path)

    private fun write(path: String, text: String) {
        manager.createDirectoryAtPath(path.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null)
        assertTrue(NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null))
    }

    private fun read(path: String): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    @Test
    fun `the paths are all inside Documents AAPS`() {
        assertEquals("$documents/AAPS", sut.root)
        assertEquals("$documents/AAPS/preferences", sut.preferences)
        assertEquals("$documents/AAPS/exports", sut.exports)
        assertEquals("$documents/AAPS/extra", sut.extra)
        assertEquals("$documents/AAPS/ml", sut.ml)
        assertEquals("$documents/AAPS/logs", sut.logs)
    }

    @Test
    fun `the master app gets every folder`() {
        val result = sut.ensure(isClient = false)

        assertTrue(result.ok, "failed: ${result.failed}")
        listOf(sut.root, sut.preferences, sut.exports, sut.extra, sut.ml, sut.logs).forEach { assertTrue(exists(it!!), "missing $it") }
    }

    @Test
    fun `the client app gets every folder except ml`() {
        val result = sut.ensure(isClient = true)

        assertTrue(result.ok, "failed: ${result.failed}")
        listOf(sut.root, sut.preferences, sut.exports, sut.extra, sut.logs).forEach { assertTrue(exists(it!!), "missing $it") }
        assertFalse(exists(sut.ml!!), "a client has no model, so no ml folder")
    }

    @Test
    fun `a folder that cannot be made is reported`() {
        // A file where the root folder should be, so no folder can be made under it.
        write("$documents/AAPS", "not a folder")

        val result = sut.ensure(isClient = false)

        assertFalse(result.ok)
        assertTrue(result.failed.contains(sut.preferences), "failed: ${result.failed}")
    }

    @Test
    fun `migration moves the legacy folders and logs into the tree`() {
        write("$documents/extra/$engineeringMode", "")
        write("$documents/preferences/2026-01-01_120000_full.json", "{}")
        write("$documents/exports/2026-01-01_120000_UserEntry.csv", "a,b")
        write("$documents/aaps.log", "live")
        write("$documents/aaps.log.1", "older")
        write("$documents/notes.txt", "not a log")
        sut.ensure(isClient = false)

        val result = sut.migrateLegacy()

        assertTrue(exists("${sut.extra}/$engineeringMode"))
        assertTrue(exists("${sut.preferences}/2026-01-01_120000_full.json"))
        assertTrue(exists("${sut.exports}/2026-01-01_120000_UserEntry.csv"))
        assertEquals("live", read("${sut.logs}/aaps.log"))
        assertEquals("older", read("${sut.logs}/aaps.log.1"))
        assertTrue(exists("$documents/notes.txt"), "a file that is not a log stays where it is")
        assertFalse(exists("$documents/aaps.log"))
        assertEquals(5, result.moved.size)
        assertTrue(result.kept.isEmpty())
        listOf("extra", "preferences", "exports").forEach { assertFalse(exists("$documents/$it"), "the empty legacy $it folder should be gone") }
        assertEquals(3, result.removedFolders.size)
    }

    @Test
    fun `migration never overwrites a file that is already in the target`() {
        write("$documents/preferences/same.json", "legacy")
        write("$documents/preferences/other.json", "legacy other")
        write("$documents/aaps.log", "legacy log")
        write("${sut.preferences}/same.json", "new")
        write("${sut.logs}/aaps.log", "new log")

        val result = sut.migrateLegacy()

        assertEquals("new", read("${sut.preferences}/same.json"), "the target must be kept")
        assertEquals("legacy", read("$documents/preferences/same.json"), "the legacy file stays where it is")
        assertEquals("legacy other", read("${sut.preferences}/other.json"))
        assertEquals("new log", read("${sut.logs}/aaps.log"))
        assertEquals("legacy log", read("$documents/aaps.log"))
        assertEquals(setOf("$documents/preferences/same.json", "$documents/aaps.log"), result.kept.toSet())
    }

    @Test
    fun `a legacy folder that is not empty after the move is kept`() {
        write("$documents/exports/same.csv", "legacy")
        write("${sut.exports}/same.csv", "new")

        val result = sut.migrateLegacy()

        assertTrue(exists("$documents/exports"), "the folder still holds a file, so it must stay")
        assertTrue(result.removedFolders.isEmpty())
    }

    @Test
    fun `migration run a second time does nothing`() {
        write("$documents/extra/unfinished_mode", "")
        write("$documents/aaps.log", "live")
        sut.ensure(isClient = false)
        sut.migrateLegacy()

        val second = sut.migrateLegacy()

        assertTrue(second.moved.isEmpty())
        assertTrue(second.kept.isEmpty())
        assertTrue(second.removedFolders.isEmpty())
        assertTrue(exists("${sut.extra}/unfinished_mode"))
        assertEquals("live", read("${sut.logs}/aaps.log"))
    }

    @Test
    fun `engineering mode is created when the tree is made for the first time`() {
        val result = sut.prepare(isClient = false)

        assertTrue(result.firstCreation)
        assertTrue(result.engineeringModeCreated)
        assertTrue(exists("${sut.extra}/$engineeringMode"))
    }

    @Test
    fun `engineering mode does not come back after the user deleted it`() {
        sut.prepare(isClient = false)
        manager.removeItemAtPath("${sut.extra}/$engineeringMode", null)

        val second = sut.prepare(isClient = false)

        assertFalse(second.firstCreation)
        assertFalse(second.engineeringModeCreated)
        assertFalse(exists("${sut.extra}/$engineeringMode"))
    }

    @Test
    fun `engineering mode is not created when an older build had an extra folder`() {
        write("$documents/extra/unfinished_mode", "")

        val result = sut.prepare(isClient = false)

        assertFalse(result.firstCreation)
        assertFalse(result.engineeringModeCreated)
        assertFalse(exists("${sut.extra}/$engineeringMode"))
        assertTrue(exists("${sut.extra}/unfinished_mode"), "the legacy file is moved in")
    }

    @Test
    fun `engineering mode is created for the client app too on a new install`() {
        val result = sut.prepare(isClient = true)

        assertTrue(result.engineeringModeCreated)
        assertFalse(exists(sut.ml!!))
    }

    @Test
    fun `the readme is written when missing and lists every option file`() {
        sut.prepare(isClient = false)

        val readme = read("${sut.extra}/${IosAapsDirectories.README_NAME}")
        assertTrue(readme != null, "no README.txt")
        ExternalOptions.entries.forEach { assertTrue(readme.lines().any { line -> line.startsWith("${it.filename} - ") }, "${it.filename} is not listed") }
    }

    @Test
    fun `a readme the user changed is not written over`() {
        sut.ensure(isClient = false)
        write("${sut.extra}/${IosAapsDirectories.README_NAME}", "my notes")

        assertFalse(sut.writeReadmeIfMissing())
        assertEquals("my notes", read("${sut.extra}/${IosAapsDirectories.README_NAME}"))
    }

    @Test
    fun `only aaps log and its numbered files count as log files`() {
        assertTrue(IosAapsDirectories.isLogFileName("aaps.log"))
        assertTrue(IosAapsDirectories.isLogFileName("aaps.log.1"))
        assertTrue(IosAapsDirectories.isLogFileName("aaps.log.12"))
        assertFalse(IosAapsDirectories.isLogFileName("aaps.log.old"))
        assertFalse(IosAapsDirectories.isLogFileName("aaps.log.0"))
        assertFalse(IosAapsDirectories.isLogFileName("aaps-probe.log"))
        assertFalse(IosAapsDirectories.isLogFileName("other.log"))
    }
}
