package app.aaps.plugins.source

import android.content.Context
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
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Libre 3 writes the calibration cutoff only when promotion succeeds.
 *
 * Ref `Libre3PromotionHandoverTest` @ `6598201d` L133, and `Libre3NativePlugin.kt` L981
 * (reject, no call) / L1051–1055 (success: sensor change dated at activation, then
 * `ignoreEntriesBefore(System.currentTimeMillis())`).
 */
class Libre3PromotionCalibrationTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var activePlugin: ActivePlugin

    private val calibration: Calibration = mock()
    private val bleRadioPriority: BleRadioPriority = mock()
    private val availabilityProvider: Libre3AvailabilityProvider = mock()

    private lateinit var plugin: Libre3NativePlugin

    @BeforeEach
    fun setup() {
        whenever(activePlugin.activeCalibration).thenReturn(calibration)
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(false)
        plugin = Libre3NativePlugin(
            rh, aapsLogger, preferences, config, context, persistenceLayer, availabilityProvider, bleRadioPriority, activePlugin,
        )
    }

    @Test
    fun a_rejected_promotion_does_not_move_the_cutoff() = runTest {
        val result = plugin.promoteStagingToProduction(allowEarly = true)

        assertThat(result).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT))
        assertThat(plugin.stagingState.value).isEqualTo(StagingState.ABSENT)
        verify(calibration, never()).ignoreEntriesBefore(any())
    }

    @Test
    fun a_successful_promotion_drops_fingersticks_from_before_the_swap() = runTest {
        val activatedAtMs = 1_699_000_000_000L
        plugin.noteStagingActivation(activatedAtMs)
        assertThat(plugin.stagingState.value).isEqualTo(StagingState.READY)

        val before = System.currentTimeMillis()
        val result = plugin.promoteStagingToProduction(allowEarly = false)
        val after = System.currentTimeMillis()

        assertThat(result).isEqualTo(PromotionResult.Ok)
        assertThat(plugin.stagingState.value).isEqualTo(StagingState.ABSENT)
        val cutoff = argumentCaptor<Long>()
        verify(calibration).ignoreEntriesBefore(cutoff.capture())
        assertThat(cutoff.firstValue).isAtLeast(before)
        assertThat(cutoff.firstValue).isAtMost(after)
        assertThat(cutoff.firstValue).isNotEqualTo(activatedAtMs)

        val again = plugin.promoteStagingToProduction()
        assertThat(again).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT))
        verify(calibration).ignoreEntriesBefore(any())
    }

    @Test
    fun a_non_positive_activation_does_not_arm_promotion() = runTest {
        plugin.noteStagingActivation(0L)

        assertThat(plugin.promoteStagingToProduction()).isEqualTo(PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT))
        verify(calibration, never()).ignoreEntriesBefore(any())
    }
}
