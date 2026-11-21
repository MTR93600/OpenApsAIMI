package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AimiBoostControl
import app.aaps.core.interfaces.aps.AimiBoostState
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class EssentialViewModelTest {

    @Mock private lateinit var boostControl: AimiBoostControl
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var config: Config
    @Mock private lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock private lateinit var rxBus: RxBus

    private val now = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(boostControl.state).thenReturn(MutableStateFlow(AimiBoostState()))
        whenever(preferences.get(IntKey.OApsAIMIManualBoostPercent)).thenReturn(30)
        whenever(rxBus.toFlow(EventNsClientStatusUpdated::class)).thenReturn(emptyFlow())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun client_loopTargetFromDeviceStatus() {
        whenever(config.AAPSCLIENT).thenReturn(true)
        fresh(target = 80.0, ageMs = 2 * 60_000L)
        val local = mock<APS>()
        val localResult = mock<APSResult>()
        whenever(localResult.targetBG).thenReturn(110.0)
        whenever(local.lastAPSResult).thenReturn(localResult)
        whenever(activePlugin.activeAPS).thenReturn(local)
        clearInvocations(activePlugin)

        assertThat(viewModel().loopTargetMgdl(now)).isEqualTo(80.0)
        verify(activePlugin, never()).activeAPS
    }

    @Test
    fun client_loopTargetStale_null() {
        whenever(config.AAPSCLIENT).thenReturn(true)
        fresh(target = 80.0, ageMs = 16 * 60_000L)
        val viewModel = viewModel()
        clearInvocations(processedDeviceStatusData)

        assertThat(viewModel.loopTargetMgdl(now)).isNull()
        verify(processedDeviceStatusData, never()).getAPSResult()
    }

    @Test
    fun master_loopTargetUnchanged_15minRule() {
        whenever(config.AAPSCLIENT).thenReturn(false)
        whenever(config.APS).thenReturn(true)
        val aps = mock<APS>()
        val result = mock<APSResult>()
        whenever(result.targetBG).thenReturn(85.0)
        whenever(aps.lastAPSResult).thenReturn(result)
        whenever(activePlugin.activeAPS).thenReturn(aps)
        val viewModel = viewModel()

        whenever(result.date).thenReturn(now - 10 * 60_000L)
        assertThat(viewModel.loopTargetMgdl(now)).isEqualTo(85.0)

        whenever(result.date).thenReturn(now - 16 * 60_000L)
        assertThat(viewModel.loopTargetMgdl(now)).isNull()
        verify(processedDeviceStatusData, never()).getAPSResult()
    }

    @Test
    fun client_refreshesOnNsClientStatusUpdated() {
        whenever(config.AAPSCLIENT).thenReturn(true)
        viewModel()
        verify(rxBus).toFlow(EventNsClientStatusUpdated::class)
    }

    @Test
    fun master_doesNotSubscribeNsClientStatusUpdated() {
        whenever(config.AAPSCLIENT).thenReturn(false)
        viewModel()
        verify(rxBus, never()).toFlow(EventNsClientStatusUpdated::class)
    }

    private fun viewModel(): EssentialViewModel {
        clearInvocations(rxBus)
        return EssentialViewModel(
            boostControl, preferences, activePlugin, config, processedDeviceStatusData, rxBus,
        )
    }

    private fun fresh(target: Double, ageMs: Long) {
        val ts = now - ageMs
        val open = ProcessedDeviceStatusData.OpenAPSData().apply {
            clockSuggested = ts
            clockSuggestedDecoded = ts
        }
        whenever(processedDeviceStatusData.openAPSData).thenReturn(open)
        whenever(processedDeviceStatusData.openApsTimestamp).thenReturn(ts)
        val result = mock<APSResult>()
        whenever(result.targetBG).thenReturn(target)
        whenever(processedDeviceStatusData.getAPSResult()).thenReturn(result)
    }
}
