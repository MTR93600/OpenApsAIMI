package app.aaps.plugins.aps.openAPSAIMI.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReactivityDaypartTest {

    @Test
    fun fromHour_maps_four_dayparts() {
        assertEquals(ReactivityDaypart.NIGHT_0_6, ReactivityDaypart.fromHour(0))
        assertEquals(ReactivityDaypart.NIGHT_0_6, ReactivityDaypart.fromHour(5))
        assertEquals(ReactivityDaypart.MORNING_6_11, ReactivityDaypart.fromHour(6))
        assertEquals(ReactivityDaypart.MORNING_6_11, ReactivityDaypart.fromHour(10))
        assertEquals(ReactivityDaypart.MIDDAY_11_16, ReactivityDaypart.fromHour(11))
        assertEquals(ReactivityDaypart.EVENING_16_24, ReactivityDaypart.fromHour(16))
        assertEquals(ReactivityDaypart.EVENING_16_24, ReactivityDaypart.fromHour(23))
    }

    @Test
    fun combineFactors_uses_40_30_30_weights() {
        val combined = ReactivityDaypart.combineFactors(global = 0.5, short = 1.0, segment = 1.2)
        assertEquals(0.86, combined, 0.0001)
    }

    @Test
    fun shrinkTowardGlobal_pulls_sparse_segment_toward_global() {
        val shrunk = ReactivityDaypart.shrinkTowardGlobal(rawSegment = 0.6, global = 1.0, sampleCount = 5)
        assertTrue(shrunk > 0.6 && shrunk < 1.0)
        assertEquals(1.0, ReactivityDaypart.shrinkTowardGlobal(0.6, 1.0, sampleCount = 0), 0.0001)
    }

    @Test
    fun capSegmentAgainstGlobal_blocks_aggressive_segment_when_hypo_present() {
        assertEquals(0.5, ReactivityDaypart.capSegmentAgainstGlobal(1.2, 0.5, hypoCount = 1), 0.0001)
        assertEquals(1.2, ReactivityDaypart.capSegmentAgainstGlobal(1.2, 0.5, hypoCount = 0), 0.0001)
    }

    @Test
    fun exerciseAmplifiedHypoCount_boosts_hypo_burden_without_excluding() {
        assertEquals(0.0, ReactivityDaypart.exerciseAmplifiedHypoCount(0, exerciseInSegment = true), 0.0001)
        assertEquals(1.5, ReactivityDaypart.exerciseAmplifiedHypoCount(1, exerciseInSegment = true), 0.0001)
        assertEquals(3.0, ReactivityDaypart.exerciseAmplifiedHypoCount(2, exerciseInSegment = true), 0.0001)
    }

    @Test
    fun morning_segment_can_exceed_low_global_when_locally_hypo_free() {
        val raw = ReactivityDaypart.computeRawSegmentFactor(
            currentSegment = 1.0,
            hypoCount = 0,
            tirAbove180 = 45.0,
            exerciseInSegment = false,
        )
        val shrunk = ReactivityDaypart.shrinkTowardGlobal(raw, global = 0.5, sampleCount = 80)
        val capped = ReactivityDaypart.capSegmentAgainstGlobal(shrunk, global = 0.5, hypoCount = 0)
        val combined = ReactivityDaypart.combineFactors(global = 0.5, short = 1.0, segment = capped)
        assertTrue(capped > 0.5)
        assertTrue(combined > 0.7)
    }

    @Test
    fun countHypoEpisodes_groups_contiguous_low_readings() {
        val hypos = ReactivityDaypart.countHypoEpisodes(listOf(120.0, 65.0, 62.0, 80.0, 68.0, 95.0))
        assertEquals(2, hypos)
    }
}
