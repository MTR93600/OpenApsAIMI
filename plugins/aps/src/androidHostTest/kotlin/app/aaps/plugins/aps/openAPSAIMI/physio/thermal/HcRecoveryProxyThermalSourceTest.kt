package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import app.aaps.plugins.aps.openAPSAIMI.physio.HRVDataMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.RHRDataMTR
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class HcRecoveryProxyThermalSourceTest {

    @Test
    fun build_creates_daily_proxy_samples_from_rhr_elevation() {
        val zone = ZoneId.systemDefault()
        val day1 = LocalDate.now(zone).minusDays(2)
        val day2 = LocalDate.now(zone).minusDays(1)
        val ts1 = day1.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val ts2 = day2.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        val rhr = listOf(
            RHRDataMTR(timestamp = ts1, bpm = 60, source = "HealthConnect(RestingHR:com.garmin.android.apps.connectmobile)"),
            RHRDataMTR(timestamp = ts2, bpm = 72, source = "HealthConnect(RestingHR:com.garmin.android.apps.connectmobile)"),
        )

        val samples = HcRecoveryProxyThermalSource.build(
            rhrPoints = rhr,
            hrvPoints = emptyList(),
            daysBack = 3,
            nowMs = ts2 + 3_600_000L,
        )

        assertThat(samples).isNotEmpty()
        assertThat(samples.last().deltaCelsius).isGreaterThan(0.0)
        assertThat(samples.last().dataOrigin).contains("Garmin")
    }

    @Test
    fun build_returns_empty_when_insufficient_rhr_history() {
        val samples = HcRecoveryProxyThermalSource.build(
            rhrPoints = listOf(RHRDataMTR(timestamp = System.currentTimeMillis(), bpm = 65)),
            hrvPoints = emptyList(),
            daysBack = 3,
        )
        assertThat(samples).isEmpty()
    }
}
