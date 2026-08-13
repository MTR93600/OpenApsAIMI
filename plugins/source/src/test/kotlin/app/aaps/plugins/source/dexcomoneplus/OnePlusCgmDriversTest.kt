package app.aaps.plugins.source.dexcomoneplus

import app.aaps.core.interfaces.source.SensorSlot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OnePlusCgmDriversTest {

    @AfterEach
    fun resetToStub() {
        OnePlusCgmDrivers.select(useReal = false)
    }

    @Test
    fun select_switchesStubAndReal() {
        val stub = OnePlusCgmDrivers.select(useReal = false)
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isFalse()
        assertThat(stub).isSameInstanceAs(OnePlusCgmDriverStub.instance)

        val real = OnePlusCgmDrivers.select(useReal = true)
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isTrue()
        assertThat(real).isSameInstanceAs(OnePlusCgmDrivers.realSkeleton())

        val back = OnePlusCgmDrivers.select(useReal = false)
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isFalse()
        assertThat(back).isSameInstanceAs(OnePlusCgmDriverStub.instance)
    }

    @Test
    fun `production keeps the original store file and staging gets its own`() {
        assertThat(OnePlusCgmDrivers.storeNamespace(SensorSlot.PRODUCTION)).isNull()
        assertThat(OnePlusCgmDrivers.storeNamespace(SensorSlot.STAGING))
            .isEqualTo(OnePlusCgmDrivers.STAGING_NAMESPACE)
    }

    @Test
    fun `the two slots never share a store namespace`() {
        // Sharing it is what let a pre-soak adopt the sensor already in use (same MAC, PIN and key).
        assertThat(OnePlusCgmDrivers.storeNamespace(SensorSlot.STAGING))
            .isNotEqualTo(OnePlusCgmDrivers.storeNamespace(SensorSlot.PRODUCTION))
    }
}
