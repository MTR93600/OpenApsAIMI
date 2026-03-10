package app.aaps.plugins.aps.openAPSAIMI

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class NightGrowthResistanceMonitorTest {

    private val monitor = DefaultNightGrowthResistanceMonitor(ZoneId.of("UTC"))
    private val config = NGRConfig(
        enabled = true,
        pediatricAgeYears = 10,
        nightStart = LocalTime.of(22, 0),
        nightEnd = LocalTime.of(7, 0),
        minRiseSlope = 3.0,
        minDurationMin = 15,
        minEventualOverTarget = 20,
        allowSMBBoostFactor = 1.2,
        allowBasalBoostFactor = 1.2,
        maxSMBClampU = 2.0,
        extraIobPer30Min = 0.5,
        decayMinutes = 30
    )

    @Test
    fun `test evaluate inactive outside night`() {
        val now = Instant.parse("2023-01-01T12:00:00Z") // Noon
        val result = monitor.evaluate(
            now = now,
            bg = 150.0,
            delta = 5.0,
            shortAvgDelta = 5.0,
            longAvgDelta = 5.0,
            eventualBG = 200.0,
            targetBG = 100.0,
            iob = 1.0,
            cob = 0.0,
            react = 0.0,
            isMealActive = false,
            config = config
        )
        assertEquals(NGRState.INACTIVE, result.state)
        assertEquals("NGR inactive: outside night window", result.reason)
    }

    @Test
    fun `test evaluate suspected`() {
        // 23:00 UTC
        val start = Instant.parse("2023-01-01T23:00:00Z")
        
        // Step 1: Positive slope start
        var result = monitor.evaluate(
            now = start,
            bg = 150.0,
            delta = 4.0, // > minRiseSlope 3.0
            shortAvgDelta = 4.0,
            longAvgDelta = 4.0,
            eventualBG = 150.0,
            targetBG = 100.0,
            iob = 1.0,
            cob = 0.0,
            react = 0.0,
            isMealActive = false,
            config = config
        )
        // Not enough duration yet
        assertEquals(NGRState.INACTIVE, result.state)

        // Step 2: 20 mins later (duration > 15)
        val later = start.plusSeconds(20 * 60)
        result = monitor.evaluate(
            now = later,
            bg = 200.0,
            delta = 4.0,
            shortAvgDelta = 4.0,
            longAvgDelta = 4.0,
            eventualBG = 200.0, // > target + 20 (120)
            targetBG = 100.0,
            iob = 1.0,
            cob = 0.0,
            react = 0.0,
            isMealActive = false,
            config = config
        )
        
        assertEquals(NGRState.SUSPECTED, result.state)
        assertEquals(1.2 * 0.6 + 0.4, result.smbMultiplier, 0.1) // 1.0 + (1.2-1.0)*0.6 = 1.12
    }
}
