package app.aaps.plugins.source

import android.content.Context
import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.SensorCalibrationResult
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusCalibrationOutcome
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSession
import app.aaps.plugins.dexcomoneplus.session.OnePlusCalibrationQueue
import app.aaps.plugins.source.keys.DexcomOnePlusBooleanKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The engineering switch is off by default. While it is off, [DexcomOnePlusPlugin.calibrateSensor]
 * returns [SensorCalibrationResult.NotSupported] and the production queue stays empty, so the
 * Control loop has nothing from which to build opcode 0x34.
 *
 * The session is forced up on purpose: a missing switch would queue the value. The assertion is
 * that the queue is still empty.
 */
class DexcomOnePlusSensorCalibrationTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var warmupBasalGuard: DexcomOnePlusWarmupBasalGuard

    private val availabilityProvider: DexcomOnePlusAvailabilityProvider = mock()
    private val bleRadioPriority: BleRadioPriority = mock()
    private val activePlugin: ActivePlugin = mock<ActivePlugin>().also {
        whenever(it.activeCalibration).thenReturn(mock<Calibration>())
    }

    private lateinit var plugin: DexcomOnePlusPlugin

    @BeforeEach
    fun setup() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(rh.gs(any<Int>())).thenReturn("not sent")
        plugin = DexcomOnePlusPlugin(
            rh, aapsLogger, preferences, config, context, persistenceLayer,
            warmupBasalGuard, availabilityProvider, bleRadioPriority, activePlugin, rxBus,
        )
        OnePlusCgmDrivers.select(useReal = true)
    }

    @AfterEach
    fun tearDownDrivers() {
        OnePlusCgmDrivers.select(useReal = true)
        OnePlusCgmDrivers.select(useReal = false)
    }

    @Test
    fun `the switch defaults to off`() {
        assertThat(DexcomOnePlusBooleanKey.SendCalibrationToSensor.defaultValue).isFalse()
        assertThat(DexcomOnePlusBooleanKey.SendCalibrationToSensor.engineeringModeOnly).isTrue()
        assertThat(DexcomOnePlusBooleanKey.SendCalibrationToSensor.exportable).isFalse()
    }

    @Test
    fun `switch off returns NotSupported and queues nothing`() {
        whenever(preferences.get(DexcomOnePlusBooleanKey.SendCalibrationToSensor)).thenReturn(false)
        val driver = OnePlusCgmDrivers.realProduction()
        markSessionUp(driver)
        val before = queueOf(driver).peek()

        val result = plugin.calibrateSensor(glucoseMgdl = 150, bloodAtMs = System.currentTimeMillis())

        assertThat(result).isEqualTo(SensorCalibrationResult.NotSupported)
        assertThat(driver.calibrationPending()).isFalse()
        assertThat(driver.lastCalibrationOutcome()).isNull()
        assertThat(queueOf(driver).peek()).isEqualTo(before)
        assertThat(queueOf(driver).peek()).isNull()
    }

    @Test
    fun `switch on and session down refuses without queueing`() {
        whenever(preferences.get(DexcomOnePlusBooleanKey.SendCalibrationToSensor)).thenReturn(true)
        val driver = OnePlusCgmDrivers.realProduction()

        val result = plugin.calibrateSensor(glucoseMgdl = 150, bloodAtMs = System.currentTimeMillis())

        assertThat(result).isInstanceOf(SensorCalibrationResult.Refused::class.java)
        assertThat((result as SensorCalibrationResult.Refused).reason).isEqualTo("not sent")
        assertThat(driver.calibrationPending()).isFalse()
        assertThat(driver.lastCalibrationOutcome()).isNull()
        assertThat(queueOf(driver).peek()).isNull()
    }

    @Test
    fun `switch on and a value outside 40 to 400 queues nothing`() {
        whenever(preferences.get(DexcomOnePlusBooleanKey.SendCalibrationToSensor)).thenReturn(true)
        val driver = OnePlusCgmDrivers.realProduction()
        markSessionUp(driver)

        val low = plugin.calibrateSensor(glucoseMgdl = 39, bloodAtMs = System.currentTimeMillis())
        val high = plugin.calibrateSensor(glucoseMgdl = 401, bloodAtMs = System.currentTimeMillis())

        assertThat(low).isInstanceOf(SensorCalibrationResult.Refused::class.java)
        assertThat(high).isInstanceOf(SensorCalibrationResult.Refused::class.java)
        assertThat(driver.calibrationPending()).isFalse()
        assertThat(driver.lastCalibrationOutcome()).isNull()
        assertThat(queueOf(driver).peek()).isNull()
    }

    @Test
    fun `switch on keeps the first fingerstick when a second one arrives`() {
        whenever(preferences.get(DexcomOnePlusBooleanKey.SendCalibrationToSensor)).thenReturn(true)
        val driver = OnePlusCgmDrivers.realProduction()
        markSessionUp(driver)
        val now = System.currentTimeMillis()

        val first = plugin.calibrateSensor(glucoseMgdl = 150, bloodAtMs = now)
        val second = plugin.calibrateSensor(glucoseMgdl = 160, bloodAtMs = now)

        assertThat(first).isEqualTo(SensorCalibrationResult.Queued)
        assertThat(second).isInstanceOf(SensorCalibrationResult.Refused::class.java)
        assertThat(driver.calibrationPending()).isTrue()
        assertThat(driver.lastCalibrationOutcome()).isEqualTo(OnePlusCalibrationOutcome.Pending)
        assertThat(queueOf(driver).peek()?.glucoseMgdl).isEqualTo(150)
    }

    private fun markSessionUp(driver: OnePlusCgmDriverReal) {
        val field = OnePlusCgmDriverReal::class.java.getDeclaredField("session")
        field.isAccessible = true
        field.set(driver, UpSession)
    }

    private fun queueOf(driver: OnePlusCgmDriverReal): OnePlusCalibrationQueue {
        val field = OnePlusCgmDriverReal::class.java.getDeclaredField("calibrationQueue")
        field.isAccessible = true
        return field.get(driver) as OnePlusCalibrationQueue
    }

    private object UpSession : OnePlusBleSession {
        override fun startWithPairingCode(deviceAddress: String, pairingCode: String) = Unit
        override fun stop(reason: String?) = Unit
        override fun isUp(): Boolean = true
        override fun warmupState() = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.READY)
    }
}
