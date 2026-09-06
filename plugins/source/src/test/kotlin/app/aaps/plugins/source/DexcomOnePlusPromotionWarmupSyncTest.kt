package app.aaps.plugins.source

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.session.OnePlusMacArbiter
import app.aaps.shared.tests.SharedPreferencesMock
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * "Promotion of presoak to production didn't work again. Status remained IDLE. Had to force close
 * AAPS to get it working." — the staging driver DID take over and DID feed the loop (glucose kept
 * arriving fine), but nothing ever pushed its real state into the production `_warmup`/[warmup]
 * that the Status screen reads, so it stayed frozen at whatever the OLD production driver had last
 * reported. Only a restart fixed it, because [DexcomOnePlusPlugin.onStart] polls the driver's
 * current phase directly instead of waiting for an event that a steady-state driver never re-emits.
 */
class DexcomOnePlusPromotionWarmupSyncTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var warmupBasalGuard: DexcomOnePlusWarmupBasalGuard

    private val availabilityProvider: DexcomOnePlusAvailabilityProvider = mock()
    private val bleRadioPriority: BleRadioPriority = mock()

    private val productionPrefs: SharedPreferences = SharedPreferencesMock()
    private val stagingPrefs: SharedPreferences = SharedPreferencesMock()

    private lateinit var plugin: DexcomOnePlusPlugin

    @BeforeEach
    fun setup() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(PRODUCTION_PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(productionPrefs)
        whenever(context.getSharedPreferences(STAGING_PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(stagingPrefs)
        plugin = DexcomOnePlusPlugin(
            rh, aapsLogger, preferences, config, context, persistenceLayer, warmupBasalGuard, availabilityProvider, bleRadioPriority,
        )
    }

    /** The driver instances and their MAC claims are process-wide — none of it may travel to the next test. */
    @AfterEach
    fun tearDownDrivers() {
        runCatching { OnePlusCgmDrivers.select(useReal = false) }
        OnePlusMacArbiter.reset()
    }

    /** Pokes state a real staging session reaches only through BLE, which this test does not run. */
    private fun setPrivate(name: String, value: Any?) {
        DexcomOnePlusPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value)
    }

    @Test
    fun `promotion syncs production status from the promoted driver, not the stale pre-promotion one`() = runTest {
        // A settled pre-soak: real persisted soak clock, and the in-memory evidence a live staging
        // session would have built up after 6 valid readings.
        OnePlusSensorStore(context, OnePlusCgmDrivers.STAGING_NAMESPACE)
            .startSessionForSensor("AA:BB:CC:DD:EE:01", System.currentTimeMillis() - 60_000L, null)
        setPrivate("stagingPresent", true)
        setPrivate("stagingValidEgvCount", 6)
        setPrivate("stagingLastValueMgdl", 120.0)
        setPrivate("stagingLastValueAtMs", System.currentTimeMillis())

        // What production was showing right before the promotion: the old sensor was still READY.
        plugin.onWarmup(OnePlusWarmupState(phase = OnePlusWarmupState.Phase.READY))
        assertThat(plugin.warmup.value.phase).isEqualTo(OnePlusWarmupState.Phase.READY)

        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Ok)
        // The promoted driver has no live BLE session in this test, so its real state is IDLE.
        // Production must show that — not the stale READY left over from the sensor that was just
        // retired (in the field this stale phase is what leaves the Status screen frozen).
        assertThat(plugin.warmup.value.phase).isEqualTo(OnePlusWarmupState.Phase.IDLE)
    }

    /**
     * Before this fix, `OnePlusCgmDrivers.default()` kept handing out the OLD (retired) production
     * instance for the rest of the session — a bare flag flip (`stagingPublishesToLoop`) inside the
     * plugin routed glucose to the loop, but the registry itself never learned about the swap. Every
     * reader that asks the registry directly instead of the plugin's own StateFlow — the Status and
     * Warmup screens, and the reconnect watchdog's `armReconnectWatchdog` — kept watching the retired
     * instance until an app restart. See docs/DEXCOM_ONEPLUS_DUAL_SENSOR_STAGING_PLAN.md.
     */
    @Test
    fun `promotion swaps the registry so default() and the reconnect watchdog see the promoted driver`() = runTest {
        OnePlusSensorStore(context, OnePlusCgmDrivers.STAGING_NAMESPACE)
            .startSessionForSensor("AA:BB:CC:DD:EE:02", System.currentTimeMillis() - 60_000L, null)
        setPrivate("stagingPresent", true)
        setPrivate("stagingValidEgvCount", 6)
        setPrivate("stagingLastValueMgdl", 120.0)
        setPrivate("stagingLastValueAtMs", System.currentTimeMillis())
        // Captured before the swap: after it, `OnePlusCgmDrivers.staging()` builds a fresh instance.
        val stagingInstance = OnePlusCgmDrivers.staging()

        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Ok)
        assertThat(OnePlusCgmDrivers.default()).isSameInstanceAs(stagingInstance)
        // A later pre-soak must get its own instance, never the one that is now production.
        assertThat(OnePlusCgmDrivers.staging()).isNotSameInstanceAs(stagingInstance)
    }

    companion object {
        private const val PRODUCTION_PREFS_NAME = "dexcom_oneplus_sensor"
        private const val STAGING_PREFS_NAME = "dexcom_oneplus_sensor_staging"
    }
}
