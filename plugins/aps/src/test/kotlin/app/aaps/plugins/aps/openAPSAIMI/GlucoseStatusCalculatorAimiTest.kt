package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.iob.BucketedRecord
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class GlucoseStatusCalculatorAimiTest {

    private val log: AAPSLogger = mockk(relaxed = true)
    private val iobCobCalculator: IobCobCalculator = mockk(relaxed = true)
    private val dateUtil: DateUtil = mockk(relaxed = true)
    private val fmt: DecimalFormatter = mockk(relaxed = true)
    private val deltaCalculator: DeltaCalculator = mockk(relaxed = true)
    private val preferences: Preferences = mockk(relaxed = true)

    private lateinit var calculator: GlucoseStatusCalculatorAimi

    @Before
    fun setUp() {
        calculator = GlucoseStatusCalculatorAimi(log, iobCobCalculator, dateUtil, fmt, deltaCalculator, preferences)
        every { dateUtil.now() } returns 1000000L
        every { fmt.to1Decimal(any()) } returns "0.0"
        every { fmt.to2Decimal(any()) } returns "0.00"
    }

    @Test
    fun `test compute with no data`() {
        every { iobCobCalculator.ads.getBucketedDataTableCopy() } returns emptyList()
        val result = calculator.compute(allowOldData = false)
        assertEquals(null, result.gs)
        assertEquals(null, result.features)
    }

    @Test
    fun `test compute with valid data`() {
        val now = 1000000L
        val records = listOf(
            createRecord(now, 100.0),
            createRecord(now - 5 * 60 * 1000, 105.0),
            createRecord(now - 10 * 60 * 1000, 110.0),
            createRecord(now - 15 * 60 * 1000, 115.0),
            createRecord(now - 20 * 60 * 1000, 120.0)
        )
        every { iobCobCalculator.ads.getBucketedDataTableCopy() } returns records
        every { deltaCalculator.calculateDeltas(any()) } returns DeltaCalculator.Deltas(
            delta = -5.0,
            shortAvgDelta = -5.0,
            longAvgDelta = -5.0
        )
        every { preferences.get(DoubleKey.OApsAIMIAutodriveAcceleration) } returns 1.0
        every { preferences.get(DoubleKey.OApsAIMIcombinedDelta) } returns 1.0
        every { preferences.get(IntKey.OApsAIMINightGrowthAgeYears) } returns 12

        val result = calculator.compute(allowOldData = true)
        assertNotNull(result.gs)
        assertNotNull(result.features)
        
        // Verify features derived from QuadraticFit
        // With linear drop 120 -> 100 over 20 mins, accel should be near 0
        // delta should be around -5
        
        val features = result.features!!
        // assert(features.combinedDelta == -5.0) // Mocked deltaCalculator returns -5
        assertEquals(-5.0, features.combinedDelta, 0.1)
    }

    private fun createRecord(timestamp: Long, value: Double): BucketedRecord {
        val record = mockk<BucketedRecord>(relaxed = true)
        every { record.timestamp } returns timestamp
        every { record.recalculated } returns value
        every { record.value } returns value
        every { record.filledGap } returns false
        return record
    }
}
