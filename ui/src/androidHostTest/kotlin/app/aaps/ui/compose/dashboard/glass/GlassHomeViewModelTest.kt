package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

@OptIn(ExperimentalCoroutinesApi::class)
internal class GlassHomeViewModelTest {

    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var processedTbrEbData: ProcessedTbrEbData
    @Mock private lateinit var loop: Loop
    @Mock private lateinit var config: Config
    @Mock private lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rh: TextResolver
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var preferences: Preferences

    private val pump = mock<PumpWithConcentration>()
    private val failure = AtomicReference<Throwable>()
    private val now = 1_700_000_000_000L
    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        failure.set(null)
        whenever(persistenceLayer.observeChanges(any<KClass<Any>>())).thenReturn(emptyFlow())
        whenever(persistenceLayer.databaseClearedFlow).thenReturn(emptyFlow())
        runBlocking {
            whenever(persistenceLayer.getRunningModeActiveAt(any())).thenReturn(RM(timestamp = now, mode = RM.Mode.OPEN_LOOP, duration = 0L))
            whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
            whenever(persistenceLayer.getStepsCountFromTimeToTime(any(), any())).thenReturn(emptyList())
            whenever(persistenceLayer.getHeartRatesFromTimeToTime(any(), any())).thenReturn(emptyList())
        }
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.reservoirLevel).thenReturn(MutableStateFlow(null))
        whenever(pump.lastDataTime).thenReturn(MutableStateFlow(now))
        whenever(dateUtil.now()).thenReturn(now)
        whenever(dateUtil.beginOfDay(any())).thenReturn(now)
        whenever(rh.gs(any<TextRef>())).thenReturn("text")
        whenever(rh.gs(any<TextRef>(), anyVararg())).thenReturn("text")
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.toTargetRangeString(any(), any(), any(), any())).thenReturn("80")
        whenever(aapsLogger.error(any<LTag>(), any<String>(), any<Throwable>())).thenAnswer {
            failure.set(it.getArgument(2))
            Unit
        }
        whenever(rxBus.toFlow(EventNsClientStatusUpdated::class)).thenReturn(emptyFlow())
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun client_targetPillShowsMasterTarget() {
        whenever(config.AAPSCLIENT).thenReturn(true)
        val ts = now - 60_000L
        val open = ProcessedDeviceStatusData.OpenAPSData().apply {
            clockSuggested = ts
            clockSuggestedDecoded = ts
        }
        whenever(processedDeviceStatusData.openAPSData).thenReturn(open)
        whenever(processedDeviceStatusData.openApsTimestamp).thenReturn(ts)
        val master = mock<APSResult>()
        whenever(master.targetBG).thenReturn(80.0)
        whenever(processedDeviceStatusData.getAPSResult()).thenReturn(master)
        val local = mock<APSResult>()
        whenever(local.targetBG).thenReturn(120.0)
        whenever(loop.lastRun).thenReturn(Loop.LastRun().apply { request = local })
        clearInvocations(loop, processedDeviceStatusData)

        val vm = shown()

        assertThat(runBlocking { awaitTarget(vm) }).isEqualTo("80")
        verify(loop, never()).lastRun
        verify(processedDeviceStatusData).getAPSResult()
    }

    @Test
    fun master_targetPillUnchanged() {
        whenever(config.AAPSCLIENT).thenReturn(false)
        whenever(config.APS).thenReturn(true)
        whenever(profileUtil.toTargetRangeString(any(), any(), any(), any())).thenReturn("85")
        val local = mock<APSResult>()
        whenever(local.targetBG).thenReturn(85.0)
        whenever(loop.lastRun).thenReturn(Loop.LastRun().apply { request = local })
        clearInvocations(loop)

        val vm = shown()

        assertThat(runBlocking { awaitTarget(vm) }).isEqualTo("85")
        verify(loop).lastRun
        verify(processedDeviceStatusData, never()).getAPSResult()
    }

    @Test
    fun client_refreshesOnNsClientStatusUpdated() {
        whenever(config.AAPSCLIENT).thenReturn(true)
        shown()
        verify(rxBus).toFlow(EventNsClientStatusUpdated::class)
    }

    @Test
    fun master_doesNotSubscribeNsClientStatusUpdated() {
        whenever(config.AAPSCLIENT).thenReturn(false)
        shown()
        verify(rxBus, never()).toFlow(EventNsClientStatusUpdated::class)
    }

    private fun shown(): GlassHomeViewModel {
        clearInvocations(rxBus)
        val vm = GlassHomeViewModel(
            persistenceLayer, profileFunction, profileUtil, processedTbrEbData, loop, config,
            processedDeviceStatusData, rxBus, dateUtil, rh, decimalFormatter, aapsLogger, activePlugin, preferences,
        )
        scope.launch { vm.status.collect {} }
        return vm
    }

    /** Waits for the refresh, which runs on the IO dispatcher, to publish a target. */
    private suspend fun awaitTarget(vm: GlassHomeViewModel): String = try {
        withTimeout(5_000) { vm.status.mapNotNull { it.targetText }.first() }
    } catch (e: TimeoutCancellationException) {
        failure.get()?.let { throw it }
        throw e
    }
}
