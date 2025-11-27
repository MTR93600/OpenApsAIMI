package app.aaps.plugins.aps.openAPSAIMI.comparison

import android.content.Context
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AimiSmbComparatorTest {

    private val determineBasalSMB: DetermineBasalSMB = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val comparator = AimiSmbComparator(determineBasalSMB, context)

    @Test
    fun `test generateCsvRow normal`() {
        val aimi = RT().apply {
            timestamp = 1672531200000L // 2023-01-01 00:00:00 UTC
            rate = 1.0
            insulinReq = 0.5
            duration = 30
            reason = StringBuilder("Normal reason")
        }
        val smb = RT().apply {
            rate = 0.8
            insulinReq = 0.4
            duration = 30
            reason = StringBuilder("SMB reason")
        }
        val gs = mockk<GlucoseStatus>(relaxed = true)
        // Mock glucose value if possible, or use a concrete implementation if GlucoseStatus is open/data class
        // GlucoseStatus is an interface? Let's check imports. It's likely a class or interface.
        // In the code: app.aaps.core.interfaces.aps.GlucoseStatus
        // Let's assume we can mock the field or property.
        // But generateCsvRow uses gs.glucose.
        // Let's create a simple object or mock property.
        
        // Using mockk for gs
        io.mockk.every { gs.glucose } returns 120.0

        val row = comparator.generateCsvRow(aimi, smb, gs, 1.5, 10.0)

        // Expected format: Timestamp,Date,BG,IOB,COB,AIMI_Rate,AIMI_SMB,AIMI_Duration,SMB_Rate,SMB_SMB,SMB_Duration,Diff_Rate,Diff_SMB,Reason_AIMI,Reason_SMB
        // Date depends on locale/timezone in SimpleDateFormat, but usually it's local time.
        // We can check parts.
        
        val parts = row.split(",")
        assertEquals(15, parts.size)
        assertEquals("1672531200000", parts[0])
        assertEquals("120.0", parts[2])
        assertEquals("1.5", parts[3]) // IOB
        assertEquals("10.0", parts[4]) // COB
        assertEquals("1.0", parts[5]) // AIMI Rate
        assertEquals("0.5", parts[6]) // AIMI SMB
        assertEquals("\"Normal reason\"", parts[13])
        assertEquals("\"SMB reason\"\n", parts[14]) // Last one has newline
    }

    @Test
    fun `test generateCsvRow with newlines`() {
        val aimi = RT().apply {
            timestamp = 1672531200000L
            rate = 1.0
            insulinReq = 0.5
            duration = 30
            reason = StringBuilder("Line 1\nLine 2")
        }
        val smb = RT().apply {
            rate = 0.8
            insulinReq = 0.4
            duration = 30
            reason = StringBuilder("SMB Line 1\r\nSMB Line 2")
        }
        val gs = mockk<GlucoseStatus>(relaxed = true)
        io.mockk.every { gs.glucose } returns 120.0

        val row = comparator.generateCsvRow(aimi, smb, gs, 1.5, 10.0)

        // Check that newlines are replaced
        assertTrue(row.contains("\"Line 1 | Line 2\""))
        assertTrue(row.contains("\"SMB Line 1 | SMB Line 2\""))
        
        // Ensure the row itself ends with newline but doesn't contain others
        assertEquals(1, row.count { it == '\n' })
    }

    @Test
    fun `test generateCsvRow with quotes`() {
        val aimi = RT().apply {
            timestamp = 1672531200000L
            rate = 1.0
            insulinReq = 0.5
            duration = 30
            reason = StringBuilder("Reason with \"quotes\"")
        }
        val smb = RT().apply {
            rate = 0.8
            insulinReq = 0.4
            duration = 30
            reason = StringBuilder("SMB \"quoted\"")
        }
        val gs = mockk<GlucoseStatus>(relaxed = true)
        io.mockk.every { gs.glucose } returns 120.0

        val row = comparator.generateCsvRow(aimi, smb, gs, 1.5, 10.0)

        // Quotes should be replaced by single quotes
        assertTrue(row.contains("\"Reason with 'quotes'\""))
        assertTrue(row.contains("\"SMB 'quoted'\""))
    }
}
