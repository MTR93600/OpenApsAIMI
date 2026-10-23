package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.data.model.HR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A heart rate row from HealthKit must take the same branch as a row from Health Connect.
 *
 * Before this rule, a "HealthKit" row was counted as a watch row, because every name that is not
 * Garmin, Health Connect or the phone is. In the Health Connect only mode that row was then
 * ignored on the iPhone, while the same reading on Android (a Health Connect row) was used.
 */
class UnifiedActivityHealthKitDeviceTest {

    private fun hr(device: String, timestamp: Long, bpm: Double) =
        HR(duration = 300_000L, timestamp = timestamp, beatsPerMinute = bpm, device = device)

    @Test
    fun `healthkit is the platform health store and not a watch`() {
        assertTrue(UnifiedActivityProviderMTR.isPlatformHealthStore(UnifiedActivityProviderMTR.HEALTHKIT_DEVICE))
        assertTrue(UnifiedActivityProviderMTR.isPlatformHealthStore("HealthConnect"))
        assertFalse(UnifiedActivityProviderMTR.isWearDevice(UnifiedActivityProviderMTR.HEALTHKIT_DEVICE))
        assertFalse(UnifiedActivityProviderMTR.isWearDevice("HealthConnect"))
        assertTrue(UnifiedActivityProviderMTR.isWearDevice("Pixel Watch 2"))
    }

    @Test
    fun `health connect only mode picks a healthkit row like a health connect row`() {
        val modes = listOf(
            UnifiedActivityProviderMTR.MODE_HEALTH_CONNECT_ONLY,
            UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK,
            UnifiedActivityProviderMTR.MODE_PREFER_WEAR,
        )
        for (mode in modes) {
            val android = UnifiedActivityProviderMTR.resolveLatestHeartRate(listOf(hr("HealthConnect", 1_000L, 72.0)), mode)
            val iphone = UnifiedActivityProviderMTR.resolveLatestHeartRate(
                listOf(hr(UnifiedActivityProviderMTR.HEALTHKIT_DEVICE, 1_000L, 72.0)),
                mode,
            )
            assertEquals(android?.bpm, iphone?.bpm, "mode $mode")
            assertEquals(android?.timestamp, iphone?.timestamp, "mode $mode")
        }
    }

    @Test
    fun `a real watch still wins over healthkit in prefer wear mode`() {
        val picked = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(
                hr(UnifiedActivityProviderMTR.HEALTHKIT_DEVICE, 2_000L, 90.0),
                hr("Pixel Watch 2", 1_000L, 60.0),
            ),
            UnifiedActivityProviderMTR.MODE_PREFER_WEAR,
        )
        assertEquals(60.0, picked?.bpm)
    }
}
