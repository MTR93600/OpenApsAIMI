package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.utils.DirectoryAimiStorage
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On iOS the study files go to the AIMI storage folder (the app's Documents/AAPS), where the
 * Hormonitor viewer reads them, and the dataset id uses the same SHA-256 as Android.
 */
class IosHormonitorStudyPlatformTest {

    private val storage = DirectoryAimiStorage(NSTemporaryDirectory().trimEnd('/') + "/hormonitor-platform-" + NSUUID().UUIDString)
    private val platform = IosHormonitorStudyPlatform(storage)

    @Test
    fun theStudyFilesAreWrittenWhereTheViewerReads() {
        assertEquals(storage.file("AIMI_HORMONITOR_daily_outcomes_v1.jsonl"), platform.primaryFile("AIMI_HORMONITOR_daily_outcomes_v1.jsonl"))
        assertEquals(storage.file("AIMI_HORMONITOR_daily_outcomes_v1.jsonl"), platform.fallbackFile("AIMI_HORMONITOR_daily_outcomes_v1.jsonl"))
    }

    @Test
    fun sha256IsTheSameAsOnAndroid() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", platform.sha256Hex("abc"))
    }

    @Test
    fun theSeedHasTheAppAndDeviceParts() {
        assertTrue(platform.datasetIdSeed().contains("|"))
        assertTrue(platform.uptimeMs() > 0L)
    }
}
