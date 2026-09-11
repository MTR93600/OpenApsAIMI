package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The support package carries the header and the last 3000 rows of `oapsaimiML2_records.csv`.
 *
 * Behaviour from `origin/dev_OAPSAIMI` @ `02c90656b1` (`addCsvTail` in the Advisor Activity).
 * Rows are counted, not dated: the date column is written in the device locale, so parsing it
 * back would break on some devices.
 *
 * The selector is commonMain with no File I/O, so the tests live in `commonTest` (`kotlin.test`).
 */
class AimiSupportCsvTailTest {

    @Test
    fun `an empty file is skipped`() {
        assertNull(AimiSupportCsvTail.select(emptyList()))
    }

    @Test
    fun `header only is kept with an empty body`() {
        val tail = AimiSupportCsvTail.select(listOf("dateStr,smbGiven"))
        assertEquals("dateStr,smbGiven", tail?.header)
        assertEquals(emptyList(), tail?.body)
    }

    @Test
    fun `a short file keeps every body row newest last`() {
        val tail = AimiSupportCsvTail.select(
            listOf("hdr", "r1", "r2", "r3"),
        )
        assertEquals("hdr", tail?.header)
        assertEquals(listOf("r1", "r2", "r3"), tail?.body)
    }

    @Test
    fun `a long file keeps the header and the last 3000 body rows`() {
        val header = "dateStr,smbGiven"
        val body = (1..4000).map { "row$it" }
        val tail = AimiSupportCsvTail.select(listOf(header) + body)
        assertEquals(header, tail?.header)
        assertEquals(AimiSupportCsvTail.MAX_CSV_ROWS_IN_PACKAGE, tail?.body?.size)
        assertEquals("row1001", tail?.body?.first())
        assertEquals("row4000", tail?.body?.last())
    }

    @Test
    fun `the zip entry is header then body each on its own line`() {
        val tail = AimiSupportCsvTail.Selected(
            header = "hdr",
            body = listOf("a", "b"),
        )
        assertEquals("hdr\na\nb\n", AimiSupportCsvTail.toText(tail))
    }
}
