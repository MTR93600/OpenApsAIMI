package app.aaps.core.objects.overview

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.interfaces.smoothing.SmoothingContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DashboardCoherentGlucoseTest {

    private val preferGs = object : Smoothing {
        override suspend fun smooth(data: MutableList<InMemoryGlucoseValue>, context: SmoothingContext) = data
        override fun preferDashboardGlucoseFromGlucoseStatus(): Boolean = true
    }

    private val preferLastBg = object : Smoothing {
        override suspend fun smooth(data: MutableList<InMemoryGlucoseValue>, context: SmoothingContext) = data
        override fun preferDashboardGlucoseFromGlucoseStatus(): Boolean = false
    }

    private data class TestGs(
        override val glucose: Double,
        override val noise: Double = 0.0,
        override val delta: Double = 0.0,
        override val shortAvgDelta: Double = 0.0,
        override val longAvgDelta: Double = 0.0,
        override val date: Long
    ) : GlucoseStatus

    @Test
    fun displayMgdl_prefersFreshGlucoseStatusWhenSmoothingOptIn() {
        val now = 1_000_000L
        val lastBg = InMemoryGlucoseValue(
            timestamp = now,
            value = 200.0,
            trendArrow = TrendArrow.NONE,
            smoothed = null,
            filledGap = false,
            sourceSensor = SourceSensor.UNKNOWN
        )
        val gs = TestGs(glucose = 150.0, date = now - 60_000L)
        assertEquals(150.0, DashboardCoherentGlucose.displayMgdl(lastBg, gs, preferGs, now))
        assertEquals(gs.date, DashboardCoherentGlucose.displayTimestamp(lastBg, gs, preferGs, now))
    }

    @Test
    fun displayMgdl_fallsBackWhenGlucoseStatusStale() {
        val now = 1_000_000L
        val lastBg = InMemoryGlucoseValue(
            timestamp = now,
            value = 200.0,
            trendArrow = TrendArrow.NONE,
            smoothed = 180.0,
            filledGap = false,
            sourceSensor = SourceSensor.UNKNOWN
        )
        val gs = TestGs(glucose = 150.0, date = now - 10 * 60_000L)
        assertEquals(180.0, DashboardCoherentGlucose.displayMgdl(lastBg, gs, preferGs, now))
        assertEquals(now, DashboardCoherentGlucose.displayTimestamp(lastBg, gs, preferGs, now))
    }

    @Test
    fun displayMgdl_ignoresGlucoseStatusWhenSmoothingDoesNotOptIn() {
        val now = 1_000_000L
        val lastBg = InMemoryGlucoseValue(
            timestamp = now,
            value = 200.0,
            trendArrow = TrendArrow.NONE,
            smoothed = null,
            filledGap = false,
            sourceSensor = SourceSensor.UNKNOWN
        )
        val gs = TestGs(glucose = 150.0, date = now)
        assertEquals(200.0, DashboardCoherentGlucose.displayMgdl(lastBg, gs, preferLastBg, now))
    }

    @Test
    fun displayMgdl_nullLastBgFreshGs_returnsGlucoseStatusValue() {
        val now = 1_000_000L
        val gs = TestGs(glucose = 120.0, date = now)
        assertEquals(120.0, DashboardCoherentGlucose.displayMgdl(null, gs, preferGs, now))
        assertEquals(now, DashboardCoherentGlucose.displayTimestamp(null, gs, preferGs, now))
    }

    @Test
    fun displayMgdl_nullLastBgStaleGs_returnsNull() {
        val now = 1_000_000L
        val gs = TestGs(glucose = 120.0, date = now - 10 * 60_000L)
        assertNull(DashboardCoherentGlucose.displayMgdl(null, gs, preferGs, now))
    }
}
