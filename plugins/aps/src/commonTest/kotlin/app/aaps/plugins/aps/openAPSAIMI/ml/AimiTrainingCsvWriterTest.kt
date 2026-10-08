package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks what [AimiTrainingCsvWriter] puts on disk, byte for byte.
 *
 * These files are the corpus the SMB and basal models are trained from on the phone, so the whole
 * stored text is compared, not just "the call did not fail". A changed separator, a lost line break
 * or a dropped row is training data that cannot be recovered.
 *
 * Test names are camelCase on purpose. A backtick name holding a comma does not compile on Kotlin
 * Native, and this source set is built for the iOS simulator as well.
 */
class AimiTrainingCsvWriterTest {

    private val header = "dateStr,bg,iob,smbGiven\n"
    private val row = "10/04/2026 12:34,120,1.5,0.4"
    private val csv = AimiPath("/aimi/oapsaimiML2_records.csv")
    private val fallback = AimiPath("/aimi-app-scoped/oapsaimiML2_records.csv")

    private fun writerOn(storage: InMemoryAimiStorage) = AimiTrainingCsvWriter(storage, NoOpAapsLogger)

    // ---- a file that is not there yet ------------------------------------------------------------

    @Test
    fun aMissingFileIsCreatedWithTheHeaderThenTheRow() {
        val storage = InMemoryAimiStorage()

        val written = writerOn(storage).appendRow(csv, header, row)

        assertTrue(written)
        assertEquals("dateStr,bg,iob,smbGiven\n10/04/2026 12:34,120,1.5,0.4\n", storage.files[csv.value])
    }

    @Test
    fun twoRowsLandOnTheirOwnLinesUnderOneHeader() {
        val storage = InMemoryAimiStorage()
        val writer = writerOn(storage)

        writer.appendRow(csv, header, row)
        writer.appendRow(csv, header, "10/04/2026 12:39,126,1.8,0.2")

        assertEquals(
            "dateStr,bg,iob,smbGiven\n" +
                "10/04/2026 12:34,120,1.5,0.4\n" +
                "10/04/2026 12:39,126,1.8,0.2\n",
            storage.files[csv.value],
        )
    }

    // ---- the line break guard -------------------------------------------------------------------

    @Test
    fun aRowIsNotGluedOntoAFileThatDoesNotEndWithALineBreak() {
        // What a clean up used to leave behind: the last row with no line break after it.
        val storage = InMemoryAimiStorage().also {
            it.files[csv.value] = "dateStr,bg,iob,smbGiven\n10/04/2026 12:29,118,1.2,0.1"
        }

        writerOn(storage).appendRow(csv, header, row)

        assertEquals(
            "dateStr,bg,iob,smbGiven\n" +
                "10/04/2026 12:29,118,1.2,0.1\n" +
                "10/04/2026 12:34,120,1.5,0.4\n",
            storage.files[csv.value],
        )
    }

    @Test
    fun noSecondLineBreakIsAddedWhenTheFileAlreadyEndsWithOne() {
        val storage = InMemoryAimiStorage().also {
            it.files[csv.value] = "dateStr,bg,iob,smbGiven\n10/04/2026 12:29,118,1.2,0.1\n"
        }

        writerOn(storage).appendRow(csv, header, row)

        assertEquals(
            "dateStr,bg,iob,smbGiven\n" +
                "10/04/2026 12:29,118,1.2,0.1\n" +
                "10/04/2026 12:34,120,1.5,0.4\n",
            storage.files[csv.value],
        )
    }

    // ---- the header of a file that is already there ----------------------------------------------

    @Test
    fun aStaleHeaderIsReplacedAndEveryRowIsKept() {
        // The frozen 13 name header over wider rows is what made the SMB trainer learn the wrong
        // column. The old rows must stay exactly as they were.
        val storage = InMemoryAimiStorage().also {
            it.files[csv.value] = "dateStr,bg,iob\n10/04/2026 12:29,118,1.2,0.1\n"
        }

        writerOn(storage).appendRow(csv, header, row)

        assertEquals(
            "dateStr,bg,iob,smbGiven\n" +
                "10/04/2026 12:29,118,1.2,0.1\n" +
                "10/04/2026 12:34,120,1.5,0.4\n",
            storage.files[csv.value],
        )
    }

    @Test
    fun aCurrentHeaderIsLeftAloneAndTheRowStillLands() {
        val storage = InMemoryAimiStorage().also {
            it.files[csv.value] = "dateStr,bg,iob,smbGiven\n"
        }

        writerOn(storage).appendRow(csv, header, row)

        assertEquals("dateStr,bg,iob,smbGiven\n10/04/2026 12:34,120,1.5,0.4\n", storage.files[csv.value])
    }

    @Test
    fun theHeaderIsOnlyCheckedOncePerPath() {
        val storage = InMemoryAimiStorage().also {
            it.files[csv.value] = "dateStr,bg,iob\n10/04/2026 12:29,118,1.2,0.1\n"
        }
        val writer = writerOn(storage)

        writer.appendRow(csv, header, row)
        // A header that drifts back after the first check is deliberately not repaired again: the
        // check is once per path per app start, so this row goes under whatever is there now.
        storage.files[csv.value] = "dateStr,bg\n"
        writer.appendRow(csv, header, "10/04/2026 12:39,126,1.8,0.2")

        assertEquals("dateStr,bg\n10/04/2026 12:39,126,1.8,0.2\n", storage.files[csv.value])
    }

    // ---- the app scoped fallback ------------------------------------------------------------------

    @Test
    fun aRowTheSharedStorageRefusesLandsInTheAppScopedFile() {
        val storage = InMemoryAimiStorage().also { it.deniedPaths.add(csv.value) }

        writerOn(storage).appendRowWithFallback(csv, "oapsaimiML2_records.csv", header, row)

        assertEquals("dateStr,bg,iob,smbGiven\n10/04/2026 12:34,120,1.5,0.4\n", storage.files[fallback.value])
    }

    @Test
    fun aRowTheSharedStorageTakesNeverReachesTheAppScopedFile() {
        val storage = InMemoryAimiStorage()

        writerOn(storage).appendRowWithFallback(csv, "oapsaimiML2_records.csv", header, row)

        assertEquals("dateStr,bg,iob,smbGiven\n10/04/2026 12:34,120,1.5,0.4\n", storage.files[csv.value])
        assertFalse(storage.files.containsKey(fallback.value))
    }

    @Test
    fun bothLocationsRefusingLosesTheRowRatherThanThrowing() {
        val storage = InMemoryAimiStorage().also {
            it.deniedPaths.add(csv.value)
            it.deniedPaths.add(fallback.value)
        }

        // The point is that it returns: a CSV write must never take down a dosing tick.
        writerOn(storage).appendRowWithFallback(csv, "oapsaimiML2_records.csv", header, row)

        assertFalse(storage.files.containsKey(csv.value))
        assertFalse(storage.files.containsKey(fallback.value))
    }
}
