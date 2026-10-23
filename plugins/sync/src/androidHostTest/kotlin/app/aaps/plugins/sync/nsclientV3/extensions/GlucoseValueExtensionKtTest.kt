package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.nssdk.mapper.convertToRemoteAndBack
import app.aaps.plugins.sync.extensions.contentEqualsTo
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class GlucoseValueExtensionKtTest : TestBaseWithProfile() {

    @Test
    fun toGlucoseValue() {
        val glucoseValue = GV(
            timestamp = 10000,
            isValid = true,
            raw = 101.0,
            value = 99.0,
            trendArrow = TrendArrow.DOUBLE_UP,
            noise = 1.0,
            sourceSensor = SourceSensor.DEXCOM_G6,
            ids = IDs(
                nightscoutId = "nightscoutId"
            )
        )

        val glucoseValue2 = glucoseValue.toNSSvgV3().convertToRemoteAndBack()?.toGV()
        assertThat(glucoseValue.contentEqualsTo(glucoseValue2!!)).isTrue()
        assertThat(glucoseValue.ids.contentEqualsTo(glucoseValue2.ids)).isTrue()
    }

    /** Nightscout gets the value the phone shows; the plain sensor value goes to `unfiltered`. */
    @Test
    fun correctedValueIsSentAsSgvAndTheSensorValueIsKept() {
        val gv = GV(timestamp = 10000, raw = 101.0, value = 99.0, trendArrow = TrendArrow.FLAT, noise = null, sourceSensor = SourceSensor.DEXCOM_G6)

        val ns = gv.toNSSvgV3(sgvOverride = 92.0)

        assertThat(ns.sgv).isEqualTo(92.0)
        assertThat(ns.unfiltered).isEqualTo(99.0)
    }

    @Test
    fun withoutACorrectionTheSensorValueIsSentAsBefore() {
        val gv = GV(timestamp = 10000, raw = 101.0, value = 99.0, trendArrow = TrendArrow.FLAT, noise = null, sourceSensor = SourceSensor.DEXCOM_G6)

        val ns = gv.toNSSvgV3()

        assertThat(ns.sgv).isEqualTo(99.0)
        assertThat(ns.unfiltered).isEqualTo(0.0)
    }
}
