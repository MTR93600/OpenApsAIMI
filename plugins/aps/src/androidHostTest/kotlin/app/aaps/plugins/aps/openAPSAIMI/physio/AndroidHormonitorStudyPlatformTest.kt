package app.aaps.plugins.aps.openAPSAIMI.physio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Android half must keep what the exporter did before it moved to shared code: the same two
 * folders and the same dataset id seed, or study files would land elsewhere and every phone would
 * start a new dataset id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidHormonitorStudyPlatformTest {

    private val context = RuntimeEnvironment.getApplication()
    private val platform = AndroidHormonitorStudyPlatform(context)

    @Test
    fun theStudyFilesStayInDocumentsAaps() {
        assertTrue(platform.primaryFile("x.jsonl").value.endsWith("/Documents/AAPS/x.jsonl"))
    }

    @Test
    fun theFallbackStaysInTheAppScopedAapsFolder() {
        val expected = File(File(context.getExternalFilesDir(null), "AAPS"), "x.jsonl").absolutePath
        assertEquals(expected, platform.fallbackFile("x.jsonl").value)
    }

    @Test
    fun theDatasetIdSeedIsPackageAndAndroidId() {
        assertTrue(platform.datasetIdSeed().startsWith("${context.packageName}|"))
    }

    @Test
    fun sha256IsTheStandardOne() {
        // SHA-256("abc"), the standard test vector.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", platform.sha256Hex("abc"))
    }
}
