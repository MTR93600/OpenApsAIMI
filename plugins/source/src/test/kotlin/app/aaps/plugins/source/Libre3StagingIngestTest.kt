package app.aaps.plugins.source

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.data.ue.Sources
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
import app.aaps.plugins.libre3.Libre3CgmDrivers
import app.aaps.plugins.libre3.Libre3GlucoseSample
import app.aaps.plugins.libre3.identity.Libre3SensorIdentity
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.source.keys.Libre3BooleanKey
import app.aaps.shared.tests.SharedPreferencesMock
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Invariant I1: a pre-soak reading never reaches the database and never reaches the loop.
 *
 * This is the point of the whole feature, so it is asserted straight on the plugin, with a real
 * [Libre3NativePlugin] and a mocked [PersistenceLayer]. The two preferences files are in memory,
 * so the pre-soak store and the production store behave as they do on a phone.
 *
 * See `docs/LIBRE3_PRESOAK_PLAN.md` §15.2.
 */
class Libre3StagingIngestTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer

    private val bleRadioPriority: BleRadioPriority = mock()

    /** Promotion tells the active calibration plugin to drop the retired sensor's fingersticks. */
    private val activeCalibration: Calibration = mock()
    private val activePlugin: ActivePlugin = mock<ActivePlugin>().also { whenever(it.activeCalibration).thenReturn(activeCalibration) }
    private val availabilityProvider: Libre3AvailabilityProvider = mock()

    private val productionPrefs: SharedPreferences = SharedPreferencesMock()
    private val stagingPrefs: SharedPreferences = SharedPreferencesMock()

    private lateinit var plugin: Libre3NativePlugin

    private val stagedActivatedAtMs = 1_777_216_508_000L
    private val staged = Libre3SensorIdentity(
        serialNumber = "MH0PRESOAK",
        bleAddress = "AA:BB:CC:DD:EE:02",
        blePin = byteArrayOf(9, 8, 7, 6),
        receiverId = 4321,
        generation = 0,
        warmupMinutes = 60,
        wearDurationMinutes = 14 * 24 * 60,
        activatedAtMs = stagedActivatedAtMs,
    )

    /**
     * One pre-soak reading. The value stays inside the range a sensor may report, because a value
     * outside it is refused on purpose, so the life counter is what tells the samples apart.
     */
    private fun sample(index: Int) = Libre3GlucoseSample(
        mgdl = 100.0 + (index % 50),
        timestampMs = stagedActivatedAtMs + index * 60_000L,
        lifeCount = 100 + index,
    )

    @BeforeEach
    fun setup() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(PRODUCTION_PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(productionPrefs)
        whenever(context.getSharedPreferences(STAGING_PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(stagingPrefs)
        whenever(preferences.get(Libre3BooleanKey.PresoakEnabled)).thenReturn(true)
        runTest {
            whenever(persistenceLayer.insertCgmSourceData(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(PersistenceLayer.TransactionResult())
        }
        Libre3Ingest.reset()
        plugin = Libre3NativePlugin(
            rh, aapsLogger, preferences, config, context, persistenceLayer, availabilityProvider, bleRadioPriority, activePlugin,
        )
    }

    @AfterEach
    fun tearDownDrivers() {
        // The driver choice and the pre-soak instance are process wide, so neither may travel to
        // the next test.
        Libre3CgmDrivers.releaseStagingInstance()?.let { runCatching { it.shutdown() } }
        runCatching { Libre3CgmDrivers.select(useReal = false) }
    }

    /** What the NFC scan does before `beginStaging` is called: it writes the sensor into the slot. */
    private fun storeStagedSensor() {
        assertThat(Libre3SensorStore(context, Libre3CgmDrivers.STAGING_NAMESPACE).saveIdentityAndWait(staged)).isTrue()
    }

    private fun startPresoak() {
        storeStagedSensor()
        assertThat(plugin.beginStaging(staged)).isTrue()
    }

    private fun feed(count: Int) {
        repeat(count) { plugin.stagingWatcher.onGlucose(sample(it)) }
    }

    @Test
    fun `a staging sample never reaches the persistence layer`() {
        startPresoak()

        feed(10)

        runBlocking {
            verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), anyOrNull())
        }
    }

    @Test
    fun `a staging sample never moves the production dedup floor`() {
        Libre3Ingest.seed(4242, emptyList())
        startPresoak()

        feed(10)

        assertThat(Libre3Ingest.lastAcceptedLifeCount()).isEqualTo(4242)
    }

    @Test
    fun `staging samples are visible`() {
        startPresoak()

        feed(10)

        val evidence = plugin.stagingEvidence.value!!
        assertThat(evidence.validCount).isEqualTo(10)
        assertThat(evidence.lastValueMgdl).isEqualTo(sample(9).mgdl)
        assertThat(evidence.lastValueAtEpochMs).isEqualTo(sample(9).timestampMs)
        assertThat(plugin.stagingCurve.value.map { it.timestampMs })
            .containsExactlyElementsIn((0..9).map { sample(it).timestampMs })
            .inOrder()
        assertThat(plugin.stagingState.value).isEqualTo(StagingState.READY)
    }

    @Test
    fun `the curve is capped`() {
        startPresoak()

        feed(Libre3Staging.CURVE_CAP + 50)

        assertThat(plugin.stagingCurve.value).hasSize(Libre3Staging.CURVE_CAP)
        assertThat(plugin.stagingCurve.value.first().timestampMs).isEqualTo(sample(50).timestampMs)
    }

    @Test
    fun `after promotion the same path does publish`() = runTest {
        startPresoak()
        feed(10)

        assertThat(plugin.promoteStagingToProduction()).isEqualTo(PromotionResult.Ok)

        // The promoted sensor now goes through the plugin's own production watcher.
        plugin.onGlucose(Libre3GlucoseSample(mgdl = 123.0, timestampMs = stagedActivatedAtMs + 3_600_000L, lifeCount = 900))
        verify(persistenceLayer, timeout(SLOW_INSERT_MS)).insertCgmSourceData(
            eq(Sources.Libre3Native), any(), any(), anyOrNull(),
        )
        assertThat(plugin.stagingState.value).isEqualTo(StagingState.ABSENT)
        assertThat(plugin.stagingCurve.value).isEmpty()
    }

    @Test
    fun `promotion is refused when no staging sensor is present`() = runTest {
        assertThat(plugin.promoteStagingToProduction())
            .isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT))

        verify(persistenceLayer, never()).insertCgmSourceData(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `allowEarly makes no difference`() = runTest {
        // Decision D2: there is no soak gate on a Libre 3, so there is nothing for it to relax.
        val early = plugin.promoteStagingToProduction(allowEarly = true)
        val normal = plugin.promoteStagingToProduction(allowEarly = false)

        assertThat(early).isEqualTo(normal)
        assertThat(early).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT))
    }

    @Test
    fun `promotion has no soak gate`() = runTest {
        // Decision D1: the user already pays real sensor wear time for the soak, so a sensor that
        // has soaked for one minute and collected nothing may still be promoted.
        startPresoak()

        assertThat(plugin.promoteStagingToProduction(allowEarly = false)).isEqualTo(PromotionResult.Ok)
    }

    @Test
    fun `promotion writes the sensor change at the pre-soak activation time`() = runTest {
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(true)
        startPresoak()
        feed(10)

        assertThat(plugin.promoteStagingToProduction()).isEqualTo(PromotionResult.Ok)

        // Dated on the real activation of the pre-soak sensor, not on the promotion, so the sensor
        // age and the calibration session are right from the first minute.
        verify(persistenceLayer, timeout(SLOW_INSERT_MS)).insertCgmSourceData(
            eq(Sources.Libre3Native), eq(emptyList()), eq(emptyList()), eq(stagedActivatedAtMs),
        )
    }

    @Test
    fun `promotion takes the staged sensor over into the production file`() = runTest {
        startPresoak()
        feed(10)

        assertThat(plugin.promoteStagingToProduction()).isEqualTo(PromotionResult.Ok)

        val production = Libre3SensorStore(context, null).loadIdentity()!!
        assertThat(production.serialNumber).isEqualTo(staged.serialNumber)
        assertThat(production.bleAddress).isEqualTo(staged.bleAddress)
        // The pre-soak copy of the PIN and of the pairing key must not survive the promotion.
        assertThat(Libre3SensorStore(context, Libre3CgmDrivers.STAGING_NAMESPACE).loadIdentity()).isNull()
    }

    @Test
    fun `cancelling a pre-soak leaves production alone`() {
        assertThat(Libre3SensorStore(context, null).saveIdentityAndWait(PRODUCTION_SENSOR)).isTrue()
        startPresoak()
        feed(3)

        plugin.cancelStaging()

        assertThat(plugin.stagingState.value).isEqualTo(StagingState.ABSENT)
        assertThat(plugin.stagingEvidence.value).isNull()
        assertThat(plugin.stagingCurve.value).isEmpty()
        assertThat(Libre3SensorStore(context, null).loadIdentity()!!.serialNumber)
            .isEqualTo(PRODUCTION_SENSOR.serialNumber)
    }

    @Test
    fun `a pre-soak on the sensor that already feeds the loop is refused`() {
        assertThat(Libre3SensorStore(context, null).saveIdentityAndWait(staged)).isTrue()
        storeStagedSensor()

        assertThat(plugin.beginStaging(staged)).isFalse()
        assertThat(plugin.stagingState.value).isEqualTo(StagingState.ABSENT)
    }

    @Test
    fun `nothing starts while the pre-soak is switched off`() {
        whenever(preferences.get(Libre3BooleanKey.PresoakEnabled)).thenReturn(false)
        storeStagedSensor()

        assertThat(plugin.beginStaging(staged)).isFalse()
        assertThat(plugin.isStagingSensor(staged.serialNumber, staged.bleAddress)).isFalse()
        assertThat(plugin.stagingState.value).isEqualTo(StagingState.ABSENT)
    }

    companion object {

        private const val PRODUCTION_PREFS_NAME = "libre3_sensor_store"
        private const val STAGING_PREFS_NAME = "libre3_sensor_store_staging"

        /** How long a database insert on a background scope may take before the test gives up. */
        private const val SLOW_INSERT_MS = 5_000L

        private val PRODUCTION_SENSOR = Libre3SensorIdentity(
            serialNumber = "MH0RUNNING",
            bleAddress = "AA:BB:CC:DD:EE:01",
            blePin = byteArrayOf(1, 2, 3, 4),
            receiverId = 1234,
            generation = 0,
            warmupMinutes = 60,
            wearDurationMinutes = 14 * 24 * 60,
            activatedAtMs = 1_777_000_000_000L,
        )
    }
}
