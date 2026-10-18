package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.plugins.dexcomoneplus.session.OnePlusMacArbiter
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OnePlusCgmDriversTest {

    @AfterEach
    fun resetToStub() {
        // A leftover staging instance must be dropped too: `by lazy` used to keep a shut-down
        // driver for the next test, which is the bug this registry exists to avoid.
        OnePlusCgmDrivers.select(useReal = true)
        OnePlusCgmDrivers.promoteStagingInstance()?.shutdown()
        OnePlusCgmDrivers.select(useReal = false)
        OnePlusMacArbiter.reset()
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
    fun `a promotion makes the pre-soak instance the production default`() {
        OnePlusCgmDrivers.select(useReal = true)
        val soak = OnePlusCgmDrivers.staging()

        val retired = OnePlusCgmDrivers.promoteStagingInstance()

        assertThat(OnePlusCgmDrivers.default()).isSameInstanceAs(soak)
        assertThat(OnePlusCgmDrivers.realProduction()).isSameInstanceAs(soak)
        assertThat(retired).isNotSameInstanceAs(soak)
        // The next pre-soak is a new instance. The one that now feeds the loop is not reused.
        assertThat(OnePlusCgmDrivers.staging()).isNotSameInstanceAs(soak)
    }

    @Test
    fun `a second promotion does not reuse a shut-down instance`() {
        OnePlusCgmDrivers.select(useReal = true)
        val firstSoak = OnePlusCgmDrivers.staging()
        val retiredFirst = OnePlusCgmDrivers.promoteStagingInstance()
        retiredFirst?.shutdown()

        val secondSoak = OnePlusCgmDrivers.staging()
        assertThat(secondSoak).isNotSameInstanceAs(firstSoak)
        assertThat(secondSoak).isNotSameInstanceAs(retiredFirst)

        val retiredSecond = OnePlusCgmDrivers.promoteStagingInstance()
        assertThat(retiredSecond).isSameInstanceAs(firstSoak)
        retiredSecond?.shutdown()

        assertThat(OnePlusCgmDrivers.default()).isSameInstanceAs(secondSoak)
        assertThat(OnePlusCgmDrivers.default()).isNotSameInstanceAs(retiredFirst)
        assertThat(OnePlusCgmDrivers.default()).isNotSameInstanceAs(firstSoak)
    }

    @Test
    fun `each driver instance has its own arbiter token`() {
        val first = OnePlusCgmDriverReal(storeNamespace = OnePlusCgmDrivers.STAGING_NAMESPACE)
        val second = OnePlusCgmDriverReal(storeNamespace = OnePlusCgmDrivers.STAGING_NAMESPACE)
        try {
            val firstToken = arbiterOwner(first)
            val secondToken = arbiterOwner(second)
            assertThat(firstToken).startsWith("staging#")
            assertThat(secondToken).startsWith("staging#")
            assertThat(firstToken).isNotEqualTo(secondToken)
            // Same slot name, different instance: the second claim must not release the first.
            assertThat(OnePlusMacArbiter.claim(MAC, firstToken)).isTrue()
            assertThat(OnePlusMacArbiter.claim(MAC, secondToken)).isFalse()
            assertThat(OnePlusMacArbiter.ownerOf(MAC)).isEqualTo(firstToken)
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    private fun arbiterOwner(driver: OnePlusCgmDriverReal): String =
        OnePlusCgmDriverReal::class.java.getDeclaredField("arbiterOwner")
            .apply { isAccessible = true }
            .get(driver) as String

    @Test
    fun `the two slots never share a store namespace`() {
        // Sharing it is what let a pre-soak adopt the sensor already in use (same MAC, PIN and key).
        assertThat(OnePlusCgmDrivers.storeNamespace(SensorSlot.STAGING))
            .isNotEqualTo(OnePlusCgmDrivers.storeNamespace(SensorSlot.PRODUCTION))
    }

    companion object {
        private const val MAC = "AA:BB:CC:DD:EE:10"
    }
}
