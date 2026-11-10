package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.sharedPreferences.SP
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UnifiedActivityProviderMTRTest {

    // Fixed clock pinned 4 min into a 5-min aggregation bucket: rows at now-30s, now-5m and
    // now-10m must land in three distinct buckets. With System.currentTimeMillis() the test was
    // wall-clock flaky (now-30s and now-5m share a bucket whenever now % 5min < 30s).
    private val now = (1_780_000_000_000L / (5 * 60_000L)) * (5 * 60_000L) + 4 * 60_000L
    private val persistenceLayer = mockk<PersistenceLayer>()
    private val sp = mockk<SP>()
    private val aapsLogger = mockk<AAPSLogger>(relaxed = true)
    private lateinit var provider: UnifiedActivityProviderMTR

    @BeforeEach
    fun setUp() {
        every { sp.getString(UnifiedActivityProviderMTR.PREF_KEY_SOURCE_MODE, any()) } returns
            UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK
        provider = UnifiedActivityProviderMTR(persistenceLayer, sp, aapsLogger)
    }

    @Test
    fun `auto mode prefers latest Garmin HR over Health Connect`() {
        val result = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(
                hr("HealthConnect", 72.0, now - 60_000),
                hr("Garmin-Watchface", 88.0, now - 30_000),
            ),
            UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.bpm).isWithin(0.001).of(88.0)
        assertThat(result.source).isEqualTo("Garmin-Watchface")
    }

    @Test
    fun `auto mode uses legacy Garmin device tag`() {
        val result = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(hr("Garmin", 95.0, now - 10_000)),
            UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.bpm).isWithin(0.001).of(95.0)
        assertThat(result.source).isEqualTo("Garmin")
    }

    @Test
    fun `prefer wear uses Wear then Garmin fallback`() {
        val wearFirst = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(
                hr("Garmin-Watchface", 90.0, now - 5_000),
                hr("Pixel Watch", 78.0, now - 2_000),
            ),
            UnifiedActivityProviderMTR.MODE_PREFER_WEAR,
        )
        assertThat(wearFirst).isNotNull()
        assertThat(wearFirst!!.source).isEqualTo("Pixel Watch")
        assertThat(wearFirst.bpm).isWithin(0.001).of(78.0)

        val garminOnly = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(hr("Garmin-Watchface", 90.0, now - 2_000)),
            UnifiedActivityProviderMTR.MODE_PREFER_WEAR,
        )
        assertThat(garminOnly).isNotNull()
        assertThat(garminOnly!!.source).isEqualTo("Garmin-Watchface")
    }

    @Test
    fun `hc only ignores Garmin HR`() {
        val result = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(
                hr("Garmin-Watchface", 90.0, now - 5_000),
                hr("HealthConnect", 70.0, now - 60_000),
            ),
            UnifiedActivityProviderMTR.MODE_HEALTH_CONNECT_ONLY,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.source).isEqualTo("HealthConnect")
        assertThat(result.bpm).isWithin(0.001).of(70.0)
    }

    @Test
    fun `disabled mode returns null`() {
        assertThat(
            UnifiedActivityProviderMTR.resolveLatestHeartRate(
                listOf(hr("Garmin-Watchface", 90.0, now)),
                UnifiedActivityProviderMTR.MODE_DISABLED,
            )
        ).isNull()
    }

    @Test
    fun `legacy Garmin tag is not classified as Wear`() {
        assertThat(UnifiedActivityProviderMTR.isWearDevice("Garmin")).isFalse()
        assertThat(UnifiedActivityProviderMTR.isGarminDevice("Garmin")).isTrue()
    }

    @Test
    fun `15m window sums Garmin HTTP deltas when steps15min unset`() {
        val fiveMinMs = 5 * 60_000L
        val rows = listOf(
            garminHttpDelta(steps5 = 40, timestamp = now - 2 * fiveMinMs),
            garminHttpDelta(steps5 = 35, timestamp = now - fiveMinMs),
            garminHttpDelta(steps5 = 25, timestamp = now - 30_000),
        )

        val result = UnifiedActivityProviderMTR.resolveStepsTotalSince(
            records = rows,
            mode = UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK,
            startMs = now - 15 * 60_000L,
            nowMs = now,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.steps).isEqualTo(100)
        assertThat(result.source).isEqualTo("Garmin-Watchface")
    }

    @Test
    fun `15m window uses Health Connect prefilled steps15min when set`() {
        val rows = listOf(
            hcStepsRow(steps5 = 80, steps15 = 480, timestamp = now - 60_000),
        )

        val result = UnifiedActivityProviderMTR.resolveStepsTotalSince(
            records = rows,
            mode = UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK,
            startMs = now - 15 * 60_000L,
            nowMs = now,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.steps).isEqualTo(480)
        assertThat(result.source).isEqualTo("HealthConnect")
    }

    @Test
    fun `15m window stays zero when user is idle`() {
        val rows = listOf(
            hcStepsRow(steps5 = 0, steps15 = 0, timestamp = now - 60_000),
        )

        val result = UnifiedActivityProviderMTR.resolveStepsTotalSince(
            records = rows,
            mode = UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK,
            startMs = now - 15 * 60_000L,
            nowMs = now,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.steps).isEqualTo(0)
    }

    private fun garminHttpDelta(steps5: Int, timestamp: Long): SC =
        SC(
            duration = 300_000L,
            timestamp = timestamp,
            steps5min = steps5,
            steps10min = 0,
            steps15min = 0,
            steps30min = 0,
            steps60min = 0,
            steps180min = 0,
            device = "Garmin-Watchface",
        )

    private fun hcStepsRow(steps5: Int, steps15: Int, timestamp: Long): SC =
        SC(
            duration = 300_000L,
            timestamp = timestamp,
            steps5min = steps5,
            steps10min = steps15,
            steps15min = steps15,
            steps30min = steps15,
            steps60min = steps15,
            steps180min = steps15,
            device = "HealthConnect",
        )

    private fun hr(device: String, bpm: Double, timestamp: Long): HR =
        HR(
            duration = 300_000L,
            timestamp = timestamp,
            beatsPerMinute = bpm,
            device = device,
        )
}
