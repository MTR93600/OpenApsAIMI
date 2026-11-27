package app.aaps.plugins.source

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.dexcomoneplus.OnePlusDisplaySlot
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DexcomOnePlusIngestTest {

    @BeforeEach
    fun clearDedup() {
        DexcomOnePlusIngest.clearDedupForTests()
    }

    @Test
    fun `mapToGv uses native source sensor and trend`() {
        val sample = OnePlusGlucoseSample(
            mgdl = 118.0,
            timestampMs = 1_700_000_000_000L,
            trendSlopeMgdlPerMin = 1.2,
            sequence = 42L,
        )

        val gv = DexcomOnePlusIngest.mapToGv(sample)

        assertThat(gv.timestamp).isEqualTo(1_700_000_000_000L)
        assertThat(gv.value).isEqualTo(118.0)
        assertThat(gv.raw).isNull()
        assertThat(gv.noise).isNull()
        assertThat(gv.trendArrow).isEqualTo(TrendArrow.FORTY_FIVE_UP)
        assertThat(gv.sourceSensor).isEqualTo(SourceSensor.DEXCOM_ONEPLUS_NATIVE)
    }

    /**
     * Direct-to-watch: a reading the watch took in the sensor's watch slot must never reach
     * insertCgmSourceData, even when it is new and has a fresh sequence number.
     */
    @Test
    fun `a watch slot reading never reaches the loop`() {
        val watch = OnePlusGlucoseSample(
            mgdl = 64.0,
            timestampMs = 1_700_000_000_000L,
            sequence = 7L,
            displaySlot = OnePlusDisplaySlot.WATCH,
        )
        assertThat(DexcomOnePlusIngest.mayFeedLoop(watch)).isFalse()
        assertThat(DexcomOnePlusIngest.shouldAccept(watch)).isFalse()
        // It did not move the dedup state either: the phone's copy of the same reading still goes in.
        assertThat(DexcomOnePlusIngest.shouldAccept(watch.copy(displaySlot = OnePlusDisplaySlot.PHONE))).isTrue()
    }

    @Test
    fun `mapToGv keeps NONE when the sensor sent no usable trend`() {
        val gv = DexcomOnePlusIngest.mapToGv(
            OnePlusGlucoseSample(mgdl = 90.0, timestampMs = 1L, trendSlopeMgdlPerMin = null)
        )
        assertThat(gv.trendArrow).isEqualTo(TrendArrow.NONE)
    }

    /**
     * The regression this covers: the parser reports a slope in mg/dL per minute, and that used to
     * be matched against arrow *names*. Nothing matched, so every reading arrived as NONE and the
     * glucose history showed the invalid-arrow icon for the whole ONE+ list.
     */
    @Test
    fun `a slope the parser really produces never degrades to NONE`() {
        listOf(-5.0, -2.5, -1.4, -0.5, 0.0, 0.9, 1.2, 2.4, 4.0).forEach { slope ->
            val gv = DexcomOnePlusIngest.mapToGv(
                OnePlusGlucoseSample(mgdl = 118.0, timestampMs = 1L, trendSlopeMgdlPerMin = slope)
            )
            assertThat(gv.trendArrow).isNotEqualTo(TrendArrow.NONE)
        }
    }

    @Test
    fun `trendArrowFor maps slope to arrow at every boundary`() {
        assertThat(DexcomOnePlusIngest.trendArrowFor(null)).isEqualTo(TrendArrow.NONE)
        assertThat(DexcomOnePlusIngest.trendArrowFor(4.0)).isEqualTo(TrendArrow.DOUBLE_UP)
        assertThat(DexcomOnePlusIngest.trendArrowFor(3.0)).isEqualTo(TrendArrow.DOUBLE_UP)
        assertThat(DexcomOnePlusIngest.trendArrowFor(2.9)).isEqualTo(TrendArrow.SINGLE_UP)
        assertThat(DexcomOnePlusIngest.trendArrowFor(2.0)).isEqualTo(TrendArrow.SINGLE_UP)
        assertThat(DexcomOnePlusIngest.trendArrowFor(1.9)).isEqualTo(TrendArrow.FORTY_FIVE_UP)
        assertThat(DexcomOnePlusIngest.trendArrowFor(1.0)).isEqualTo(TrendArrow.FORTY_FIVE_UP)
        assertThat(DexcomOnePlusIngest.trendArrowFor(0.9)).isEqualTo(TrendArrow.FLAT)
        assertThat(DexcomOnePlusIngest.trendArrowFor(0.0)).isEqualTo(TrendArrow.FLAT)
        assertThat(DexcomOnePlusIngest.trendArrowFor(-0.9)).isEqualTo(TrendArrow.FLAT)
        assertThat(DexcomOnePlusIngest.trendArrowFor(-1.0)).isEqualTo(TrendArrow.FORTY_FIVE_DOWN)
        assertThat(DexcomOnePlusIngest.trendArrowFor(-1.9)).isEqualTo(TrendArrow.FORTY_FIVE_DOWN)
        assertThat(DexcomOnePlusIngest.trendArrowFor(-2.0)).isEqualTo(TrendArrow.SINGLE_DOWN)
        assertThat(DexcomOnePlusIngest.trendArrowFor(-2.9)).isEqualTo(TrendArrow.SINGLE_DOWN)
        assertThat(DexcomOnePlusIngest.trendArrowFor(-3.0)).isEqualTo(TrendArrow.DOUBLE_DOWN)
        assertThat(DexcomOnePlusIngest.trendArrowFor(-5.0)).isEqualTo(TrendArrow.DOUBLE_DOWN)
    }

    @Test
    fun `warmup WARMING blocks ingest`() {
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.WARMING)).isTrue()
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.READY)).isFalse()
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.IDLE)).isFalse()
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.PAIRING)).isFalse()
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.FAILED)).isFalse()
    }

    @Test
    fun `shouldAccept drops near-duplicate timestamps`() {
        val a = OnePlusGlucoseSample(mgdl = 100.0, timestampMs = 1_000_000L)
        val near = OnePlusGlucoseSample(mgdl = 101.0, timestampMs = 1_000_000L + 60_000L)
        val far = OnePlusGlucoseSample(mgdl = 102.0, timestampMs = 1_000_000L + DexcomOnePlusIngest.DEDUP_WINDOW_MS)
        assertThat(DexcomOnePlusIngest.shouldAccept(a)).isTrue()
        assertThat(DexcomOnePlusIngest.shouldAccept(near)).isFalse()
        assertThat(DexcomOnePlusIngest.shouldAccept(far)).isTrue()
    }

    @Test
    fun `shouldAccept drops duplicate sequence`() {
        val a = OnePlusGlucoseSample(mgdl = 110.0, timestampMs = 10L, sequence = 7L)
        val b = OnePlusGlucoseSample(mgdl = 111.0, timestampMs = 10L + DexcomOnePlusIngest.DEDUP_WINDOW_MS, sequence = 7L)
        assertThat(DexcomOnePlusIngest.shouldAccept(a)).isTrue()
        assertThat(DexcomOnePlusIngest.shouldAccept(b)).isFalse()
    }

    @Test
    fun `seed sequence floor rejects re-reads after a restart`() {
        // Simulate rehydration after an app update: last accepted sequence was 100.
        DexcomOnePlusIngest.seed(lastSequence = 100L, recentTimestampsMs = emptyList())
        // A backfilled/re-read reading at or below the floor is rejected even with a fresh timestamp.
        assertThat(DexcomOnePlusIngest.shouldAccept(OnePlusGlucoseSample(mgdl = 100.0, timestampMs = 5_000_000L, sequence = 100L))).isFalse()
        assertThat(DexcomOnePlusIngest.shouldAccept(OnePlusGlucoseSample(mgdl = 100.0, timestampMs = 5_000_000L, sequence = 99L))).isFalse()
        // A genuinely new reading above the floor is accepted.
        assertThat(DexcomOnePlusIngest.shouldAccept(OnePlusGlucoseSample(mgdl = 100.0, timestampMs = 6_000_000L, sequence = 101L))).isTrue()
    }

    @Test
    fun `seed timestamps reject recomputed re-reads within window`() {
        // The parser recomputes ts = now - age on each read, so a re-read after restart lands at a
        // slightly different timestamp that DB dedup (exact match) misses. Seeding recent stored
        // timestamps re-arms the near-duplicate window so the re-read is still dropped.
        DexcomOnePlusIngest.seed(lastSequence = -1L, recentTimestampsMs = listOf(2_000_000L))
        val reRead = OnePlusGlucoseSample(mgdl = 120.0, timestampMs = 2_000_000L + 30_000L) // 30 s off, no sequence
        assertThat(DexcomOnePlusIngest.shouldAccept(reRead)).isFalse()
    }

    private val now = 1_700_000_000_000L
    private val sensor = "AA:BB:CC:DD:EE:01"

    private fun fromWatch(enabled: Boolean = true, address: String? = sensor, phoneAddress: String? = sensor, mgdl: Double = 120.0, timestampMs: Long = now) =
        DexcomOnePlusIngest.mayFeedLoopFromWatch(enabled, address, phoneAddress, mgdl, timestampMs, now)

    private fun watchSample(timestampMs: Long, sequence: Long? = null) =
        OnePlusGlucoseSample(mgdl = 120.0, timestampMs = timestampMs, sequence = sequence, displaySlot = OnePlusDisplaySlot.WATCH)

    @Test
    fun `a watch reading of the same sensor may feed the loop when the setting is on`() {
        assertThat(fromWatch()).isTrue()
        assertThat(fromWatch(timestampMs = now - DexcomOnePlusIngest.WATCH_MAX_AGE_MS)).isTrue()
    }

    @Test
    fun `a watch reading older than the sensor backfill is refused`() {
        assertThat(fromWatch(timestampMs = now - DexcomOnePlusIngest.WATCH_MAX_AGE_MS - 1)).isFalse()
        assertThat(fromWatch(timestampMs = now - 24 * 3_600_000L)).isFalse()
    }

    @Test
    fun `watch readings are refused when the watch clock does not agree with the phone clock`() {
        assertThat(DexcomOnePlusIngest.watchClockAgrees(now, now)).isTrue()
        assertThat(DexcomOnePlusIngest.watchClockAgrees(now - DexcomOnePlusIngest.WATCH_MAX_CLOCK_SKEW_MS, now)).isTrue()
        assertThat(DexcomOnePlusIngest.watchClockAgrees(now + DexcomOnePlusIngest.WATCH_MAX_CLOCK_SKEW_MS, now)).isTrue()
        // A watch clock behind: its readings would look older or newer than they are.
        assertThat(DexcomOnePlusIngest.watchClockAgrees(now - DexcomOnePlusIngest.WATCH_MAX_CLOCK_SKEW_MS - 1, now)).isFalse()
        assertThat(DexcomOnePlusIngest.watchClockAgrees(now + DexcomOnePlusIngest.WATCH_MAX_CLOCK_SKEW_MS + 1, now)).isFalse()
        // A watch app that does not send its clock.
        assertThat(DexcomOnePlusIngest.watchClockAgrees(null, now)).isFalse()
    }

    @Test
    fun `a watch reading is refused when the setting is off`() {
        assertThat(fromWatch(enabled = false)).isFalse()
    }

    @Test
    fun `a watch reading of another sensor, or with no sensor on the phone, is refused`() {
        assertThat(fromWatch(address = "AA:BB:CC:DD:EE:02")).isFalse()
        assertThat(fromWatch(address = null)).isFalse()
        assertThat(fromWatch(phoneAddress = null)).isFalse()
        assertThat(fromWatch(address = "", phoneAddress = "")).isFalse()
    }

    @Test
    fun `a watch reading out of the sensor's range is refused`() {
        assertThat(fromWatch(mgdl = 39.0)).isFalse()
        assertThat(fromWatch(mgdl = 401.0)).isFalse()
        assertThat(fromWatch(mgdl = 40.0)).isTrue()
        assertThat(fromWatch(mgdl = 400.0)).isTrue()
    }

    @Test
    fun `a watch reading in the future is refused`() {
        assertThat(fromWatch(timestampMs = now + DexcomOnePlusIngest.WATCH_MAX_AHEAD_MS)).isTrue()
        assertThat(fromWatch(timestampMs = now + DexcomOnePlusIngest.WATCH_MAX_AHEAD_MS + 1)).isFalse()
        assertThat(fromWatch(timestampMs = 0L)).isFalse()
    }

    @Test
    fun `a watch reading fills a hole behind a newer phone reading`() {
        DexcomOnePlusIngest.seed(lastSequence = 100L, recentTimestampsMs = emptyList())
        assertThat(DexcomOnePlusIngest.shouldAccept(OnePlusGlucoseSample(mgdl = 100.0, timestampMs = now, sequence = 101L))).isTrue()
        // 20 minutes older than the phone's newest reading: the sequence floor does not apply to it.
        assertThat(DexcomOnePlusIngest.shouldAcceptFromWatch(watchSample(now - 20 * 60_000L))).isTrue()
    }

    @Test
    fun `the watch copy and the phone copy of the same reading go in once`() {
        // The watch first, then the phone's backfill of the same reading, a few seconds apart.
        assertThat(DexcomOnePlusIngest.shouldAcceptFromWatch(watchSample(now))).isTrue()
        assertThat(DexcomOnePlusIngest.shouldAccept(OnePlusGlucoseSample(mgdl = 120.0, timestampMs = now + 5_000L, sequence = 200L))).isFalse()
        // The phone first, then the watch.
        assertThat(DexcomOnePlusIngest.shouldAccept(OnePlusGlucoseSample(mgdl = 120.0, timestampMs = now + 300_000L, sequence = 201L))).isTrue()
        assertThat(DexcomOnePlusIngest.shouldAcceptFromWatch(watchSample(now + 303_000L))).isFalse()
        // The same watch reading sent again.
        assertThat(DexcomOnePlusIngest.shouldAcceptFromWatch(watchSample(now))).isFalse()
    }

    @Test
    fun `only a watch slot reading with no sequence takes the watch path`() {
        assertThat(DexcomOnePlusIngest.shouldAcceptFromWatch(watchSample(now).copy(displaySlot = OnePlusDisplaySlot.PHONE))).isFalse()
        assertThat(DexcomOnePlusIngest.shouldAcceptFromWatch(watchSample(now, sequence = 5L))).isFalse()
    }
}
