package app.aaps.core.objects.extensions

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ApsResultSourceTest {

    @Mock lateinit var config: Config
    @Mock lateinit var deviceStatus: ProcessedDeviceStatusData

    private val now = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
    }

    @Test
    fun master_returnsLocalValueUnchanged() {
        whenever(config.APS).thenReturn(true)
        whenever(config.AAPSCLIENT).thenReturn(false)
        val local = apsResult(85.0)
        val fromMaster = apsResult(120.0)
        whenever(deviceStatus.getAPSResult()).thenReturn(fromMaster)

        assertThat(ApsResultSource.targetMgdl(config, deviceStatus, now) { local.targetBG }).isEqualTo(85.0)
        assertThat(ApsResultSource.displayed(config, deviceStatus, now) { local }).isSameInstanceAs(local)
        verify(deviceStatus, never()).getAPSResult()
    }

    @Test
    fun master_localNull_returnsNull() {
        whenever(config.APS).thenReturn(true)
        whenever(config.AAPSCLIENT).thenReturn(false)
        val fromMaster = apsResult(120.0)
        whenever(deviceStatus.getAPSResult()).thenReturn(fromMaster)

        assertThat(ApsResultSource.targetMgdl(config, deviceStatus, now) { null }).isNull()
        assertThat(ApsResultSource.displayed(config, deviceStatus, now) { null }).isNull()
        verify(deviceStatus, never()).getAPSResult()
    }

    @Test
    fun client_returnsDeviceStatusTarget() {
        client()
        val master = fresh(target = 80.0, ageMs = 2 * 60_000L)

        assertThat(ApsResultSource.targetMgdl(config, deviceStatus, now) { 120.0 }).isEqualTo(80.0)
        assertThat(ApsResultSource.displayed(config, deviceStatus, now) { null }).isSameInstanceAs(master)
    }

    @Test
    fun client_staleDeviceStatus_returnsNull() {
        client()
        fresh(target = 80.0, ageMs = 16 * 60_000L)

        assertThat(ApsResultSource.targetMgdl(config, deviceStatus, now) { 120.0 }).isNull()
        assertThat(ApsResultSource.displayed(config, deviceStatus, now) { null }).isNull()
        verify(deviceStatus, never()).getAPSResult()
    }

    @Test
    fun client_noDeviceStatus_returnsNull() {
        client()
        whenever(deviceStatus.openApsTimestamp).thenReturn(-1L)

        assertThat(ApsResultSource.targetMgdl(config, deviceStatus, now) { 90.0 }).isNull()
        verify(deviceStatus, never()).getAPSResult()
    }

    @Test
    fun client_zeroOrNaNTarget_returnsNull() {
        client()
        fresh(target = 0.0, ageMs = 60_000L)
        assertThat(ApsResultSource.targetMgdl(config, deviceStatus, now) { 90.0 }).isNull()

        fresh(target = Double.NaN, ageMs = 60_000L)
        assertThat(ApsResultSource.targetMgdl(config, deviceStatus, now) { 90.0 }).isNull()
    }

    /** A timestamp ahead of the phone is not a current target. */
    @Test
    fun client_futureTimestamp_returnsNull() {
        client()
        fresh(target = 80.0, ageMs = -60_000L)

        assertThat(ApsResultSource.displayed(config, deviceStatus, now) { null }).isNull()
        verify(deviceStatus, never()).getAPSResult()
    }

    @Test
    fun pumpControl_returnsLocal() {
        whenever(config.APS).thenReturn(false)
        whenever(config.AAPSCLIENT).thenReturn(false)
        val fromMaster = apsResult(120.0)
        whenever(deviceStatus.getAPSResult()).thenReturn(fromMaster)

        assertThat(ApsResultSource.targetMgdl(config, deviceStatus, now) { 85.0 }).isEqualTo(85.0)
        verify(deviceStatus, never()).getAPSResult()
    }

    private fun client() {
        whenever(config.APS).thenReturn(false)
        whenever(config.AAPSCLIENT).thenReturn(true)
    }

    private fun fresh(target: Double, ageMs: Long): APSResult {
        val ts = now - ageMs
        whenever(deviceStatus.openApsTimestamp).thenReturn(ts)
        val result = apsResult(target)
        whenever(deviceStatus.getAPSResult()).thenReturn(result)
        return result
    }

    private fun apsResult(target: Double): APSResult {
        val result = mock<APSResult>()
        whenever(result.targetBG).thenReturn(target)
        return result
    }
}
