package app.aaps.plugins.source.dexcomoneplus.oem

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DeviceProfileRegistryTest {

    @Test
    fun pixel_doesNotRequestMtuBeforeDiscover() {
        // Juggluco Dex path never requestMtu before discover; Pixel previously did and
        // could race CCCD/auth on fragile stacks.
        assertThat(DeviceProfileRegistry.PixelDefault.requestMtuOnConnect).isFalse()
        assertThat(DeviceProfileRegistry.SamsungDefault.requestMtuOnConnect).isFalse()
        assertThat(DeviceProfileRegistry.GenericFallback.requestMtuOnConnect).isFalse()
    }

    @Test
    fun samsung_requiresAdvOnFirstAttemptAndAutoConnectFromStart() {
        val s = DeviceProfileRegistry.SamsungDefault
        assertThat(s.autoConnectFromAttempt).isEqualTo(0)
        // Hard ADV gate only on attempt 0 (driver); flag still true for Samsung.
        assertThat(s.requireAdvBeforeConnect).isTrue()
        assertThat(s.preConnectScanMs).isAtLeast(8_000L)
    }
}
