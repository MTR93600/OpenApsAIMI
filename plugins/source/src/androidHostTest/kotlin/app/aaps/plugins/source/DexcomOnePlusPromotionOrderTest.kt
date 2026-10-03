package app.aaps.plugins.source

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverStub
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.session.OnePlusMacArbiter
import app.aaps.plugins.source.keys.DexcomOnePlusBooleanKey
import app.aaps.shared.tests.SharedPreferencesMock
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Promotion order of Dexcom ONE+.
 *
 * Every refusal returns before the irreversible steps. A success follows the ref
 * (`DexcomOnePlusPlugin.promoteStagingToProduction` at `3dd0ca64772`): guards, then the
 * exchange, then `logSensorChange`, then `ignoreEntriesBefore` inside `runCatching`
 * (ref L934), then `select` and `resumeStoredSession`, each in its own `runCatching`.
 */
class DexcomOnePlusPromotionOrderTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var warmupBasalGuard: DexcomOnePlusWarmupBasalGuard

    private val availabilityProvider: DexcomOnePlusAvailabilityProvider = mock()
    private val bleRadioPriority: BleRadioPriority = mock()
    private val activeCalibration: Calibration = mock()
    private val activePlugin: ActivePlugin = mock<ActivePlugin>().also {
        whenever(it.activeCalibration).thenReturn(activeCalibration)
    }

    private val productionPrefs: SharedPreferences = SharedPreferencesMock()
    private val stagingPrefs: SharedPreferences = SharedPreferencesMock()

    private lateinit var plugin: DexcomOnePlusPlugin

    @BeforeEach
    fun setup() = runTest {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(PRODUCTION_PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(productionPrefs)
        whenever(context.getSharedPreferences(STAGING_PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(stagingPrefs)
        whenever(persistenceLayer.insertCgmSourceData(any(), any(), any(), anyOrNull()))
            .thenReturn(PersistenceLayer.TransactionResult())
        plugin = DexcomOnePlusPlugin(
            rh, aapsLogger, preferences, config, context, persistenceLayer,
            warmupBasalGuard, availabilityProvider, bleRadioPriority, activePlugin,
        )
    }

    @AfterEach
    fun tearDownDrivers() {
        OnePlusCgmDrivers.select(useReal = true)
        OnePlusCgmDrivers.promoteStagingInstance()?.shutdown()
        OnePlusCgmDrivers.select(useReal = false)
        OnePlusMacArbiter.reset()
    }

    @Test
    fun `absent staging writes nothing irreversible`() = runTest {
        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT))
        assertNothingIrreversible()
    }

    @Test
    fun `fewer than six readings writes nothing irreversible`() = runTest {
        setPrivate("stagingPresent", true)
        setPrivate("stagingValidEgvCount", 5)

        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_NO_VALID_GLUCOSE))
        assertNothingIrreversible()
    }

    @Test
    fun `a missing soak clock writes nothing irreversible`() = runTest {
        setPrivate("stagingPresent", true)
        setPrivate("stagingValidEgvCount", 6)

        val result = plugin.promoteStagingToProduction(allowEarly = false)

        assertThat(result).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_NOT_SETTLED))
        assertNothingIrreversible()
    }

    @Test
    fun `an early promotion without a recent reading writes nothing irreversible`() = runTest {
        readyStaging(mac = "AA:BB:CC:DD:EE:21", ageMs = 60_000L)
        setPrivate("stagingLastValueAtMs", System.currentTimeMillis() - 30L * 60L * 1000L)

        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_NO_RECENT_GLUCOSE))
        assertNothingIrreversible()
    }

    @Test
    fun `a soak shorter than twelve hours writes nothing irreversible`() = runTest {
        readyStaging(mac = "AA:BB:CC:DD:EE:22", ageMs = 60_000L)
        setStagingState(StagingState.READY)

        val result = plugin.promoteStagingToProduction(allowEarly = false)

        assertThat(result).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_NOT_SETTLED))
        assertNothingIrreversible()
    }

    @Test
    fun `a long soak that is not READY writes nothing irreversible`() = runTest {
        readyStaging(mac = "AA:BB:CC:DD:EE:23", ageMs = 13L * 60L * 60L * 1000L)
        setStagingState(StagingState.WARMUP)

        val result = plugin.promoteStagingToProduction(allowEarly = false)

        assertThat(result).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_NOT_SETTLED))
        assertNothingIrreversible()
    }

    @Test
    fun `the bound runs after the exchange and before select`() = runTest {
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(true)
        val startMs = System.currentTimeMillis() - 60_000L
        readyStaging(mac = "AA:BB:CC:DD:EE:24", ageMs = 60_000L, startMs = startMs)
        val soak = OnePlusCgmDrivers.staging()
        var useRealAtBound = true
        var productionWasSoakAtBound = false
        var stagingClearedAtBound = false
        var identityAdoptedAtBound = false
        whenever(activeCalibration.ignoreEntriesBefore(any())).thenAnswer {
            useRealAtBound = OnePlusCgmDrivers.useRealSkeleton
            productionWasSoakAtBound = OnePlusCgmDrivers.realProduction() === soak
            stagingClearedAtBound = stagingPrefs.getString(KEY_PIN, null) == null
            identityAdoptedAtBound = productionPrefs.getString(KEY_PIN, null) == "1234"
            null
        }

        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Ok)
        // At the bound the exchange is done and `select` has not flipped the in-memory flag yet.
        assertThat(identityAdoptedAtBound).isTrue()
        assertThat(stagingClearedAtBound).isTrue()
        assertThat(productionWasSoakAtBound).isTrue()
        assertThat(useRealAtBound).isFalse()
        val order = inOrder(preferences, activeCalibration)
        order.verify(preferences).put(DexcomOnePlusBooleanKey.UseRealSkeleton, true)
        order.verify(preferences).get(BooleanKey.BgSourceCreateSensorChange)
        order.verify(activeCalibration).ignoreEntriesBefore(any())
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isTrue()
        assertThat(OnePlusCgmDrivers.default()).isSameInstanceAs(soak)
        verify(persistenceLayer, timeout(2_000L)).insertCgmSourceData(any(), any(), any(), eq(startMs))
    }

    @Test
    fun `a thrown bound is swallowed and the exchange stays`() = runTest {
        // Ref L934: runCatching around ignoreEntriesBefore. The throw does not undo the exchange
        // and does not stop the steps that follow it. The function still returns Ok.
        readyStaging(mac = "AA:BB:CC:DD:EE:25", ageMs = 60_000L)
        val soak = OnePlusCgmDrivers.staging()
        whenever(activeCalibration.ignoreEntriesBefore(any())).thenThrow(IllegalStateException("bound failed"))

        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Ok)
        verify(activeCalibration).ignoreEntriesBefore(any())
        assertThat(productionPrefs.getString(KEY_PIN, null)).isEqualTo("1234")
        assertThat(stagingPrefs.getString(KEY_PIN, null)).isNull()
        assertThat(OnePlusCgmDrivers.default()).isSameInstanceAs(soak)
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isTrue()
    }

    @Test
    fun `a failure after the bound leaves the exchange and still returns Ok`() = runTest {
        // Ref: resumeStoredSession sits in runCatching after the bound. A throw there is the
        // state the ref reaches — Ok, identity already in the production file, default() already
        // the pre-soak instance.
        readyStaging(mac = "AA:BB:CC:DD:EE:26", ageMs = 60_000L)
        val soak = OnePlusCgmDrivers.staging()
        val steps = mutableListOf<String>()
        whenever(activeCalibration.ignoreEntriesBefore(any())).thenAnswer {
            steps.add("bound")
            null
        }
        val throwingStore = mock<OnePlusSensorStore>()
        whenever(throwingStore.load()).thenAnswer {
            steps.add("resume")
            throw IllegalStateException("resume failed after the bound")
        }
        OnePlusCgmDriverReal::class.java.getDeclaredField("sensorStore").apply {
            isAccessible = true
            set(soak, throwingStore)
        }

        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Ok)
        assertThat(steps).containsExactly("bound", "resume").inOrder()
        assertThat(productionPrefs.getString(KEY_PIN, null)).isEqualTo("1234")
        assertThat(stagingPrefs.getString(KEY_PIN, null)).isNull()
        assertThat(OnePlusCgmDrivers.default()).isSameInstanceAs(soak)
    }

    @Test
    fun `promotion shows the promoted driver's phase, not the retired one`() = runTest {
        readyStaging(mac = "AA:BB:CC:DD:EE:27", ageMs = 60_000L)
        plugin.onWarmup(OnePlusWarmupState(phase = OnePlusWarmupState.Phase.READY))
        assertThat(plugin.warmup.value.phase).isEqualTo(OnePlusWarmupState.Phase.READY)

        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Ok)
        assertThat(plugin.warmup.value.phase).isEqualTo(OnePlusWarmupState.Phase.IDLE)
    }

    @Test
    fun `after promotion the live link writes its MAC into the production file`() = runTest {
        val mac = "AA:BB:CC:DD:EE:28"
        readyStaging(mac = mac, ageMs = 60_000L)
        val promoted = OnePlusCgmDrivers.staging()
        bindDriverContext(promoted)

        assertThat(plugin.promoteStagingToProduction(allowEarly = true)).isEqualTo(PromotionResult.Ok)

        promoted.onAuthSucceeded(mac, ByteArray(16) { 7 })

        assertThat(productionPrefs.getString(KEY_MAC, null)).isEqualTo(mac)
        assertThat(stagingPrefs.getString(KEY_MAC, null)).isNull()
        assertThat(stagingPrefs.getString(KEY_PIN, null)).isNull()
        assertThat(promoted.sensorStore()?.load()?.identity?.pin).isEqualTo("1234")
    }

    private suspend fun assertNothingIrreversible() {
        verify(activeCalibration, never()).ignoreEntriesBefore(any())
        verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), anyOrNull())
        assertThat(productionPrefs.getString(KEY_PIN, null)).isNull()
        assertThat(productionPrefs.getString(KEY_MAC, null)).isNull()
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isFalse()
        assertThat(OnePlusCgmDrivers.default()).isSameInstanceAs(OnePlusCgmDriverStub.instance)
    }

    private fun readyStaging(mac: String, ageMs: Long, startMs: Long = System.currentTimeMillis() - ageMs) {
        val stagingStore = OnePlusSensorStore(context, OnePlusCgmDrivers.STAGING_NAMESPACE)
        stagingStore.saveIdentity(OnePlusSensorIdentity(pin = "1234", serial = "SOAK1"))
        stagingStore.startSessionForSensor(mac, startMs, null)
        setPrivate("stagingPresent", true)
        setPrivate("stagingValidEgvCount", 6)
        setPrivate("stagingLastValueMgdl", 120.0)
        setPrivate("stagingLastValueAtMs", System.currentTimeMillis())
    }

    private fun setStagingState(state: StagingState) {
        @Suppress("UNCHECKED_CAST")
        val flow = DexcomOnePlusPlugin::class.java.getDeclaredField("_stagingState")
            .apply { isAccessible = true }
            .get(plugin) as MutableStateFlow<StagingState>
        flow.value = state
    }

    private fun setPrivate(name: String, value: Any?) {
        DexcomOnePlusPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value)
    }

    /** Context and store, without `setContext`: that call reads `Build.MANUFACTURER`. */
    private fun bindDriverContext(driver: OnePlusCgmDriverReal) {
        OnePlusCgmDriverReal::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.set(driver, context)
        OnePlusCgmDriverReal::class.java.getDeclaredField("sensorStore")
            .apply { isAccessible = true }
            .set(driver, OnePlusSensorStore(context, OnePlusCgmDrivers.STAGING_NAMESPACE))
    }

    companion object {
        private const val PRODUCTION_PREFS_NAME = "dexcom_oneplus_sensor"
        private const val STAGING_PREFS_NAME = "dexcom_oneplus_sensor_staging"
        private const val KEY_PIN = "pin"
        private const val KEY_MAC = "last_mac"
    }
}
