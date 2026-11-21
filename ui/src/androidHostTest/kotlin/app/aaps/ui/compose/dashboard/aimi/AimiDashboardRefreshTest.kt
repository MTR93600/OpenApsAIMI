package app.aaps.ui.compose.dashboard.aimi

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.reflect.KClass

/** The AIMI home subscribes to a device status only on a client. The values themselves are in the common tests. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AimiDashboardRefreshTest {

    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var processedTbrEbData: ProcessedTbrEbData
    @Mock private lateinit var iobCobCalculator: IobCobCalculator
    @Mock private lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock private lateinit var lastBgData: LastBgData
    @Mock private lateinit var loop: Loop
    @Mock private lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rh: TextResolver
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var config: Config

    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        whenever(rxBus.toFlow(EventLoopUpdateGui::class)).thenReturn(emptyFlow())
        whenever(rxBus.toFlow(EventNsClientStatusUpdated::class)).thenReturn(emptyFlow())
        whenever(persistenceLayer.observeChanges(any<KClass<Any>>())).thenReturn(emptyFlow())
        whenever(persistenceLayer.databaseClearedFlow).thenReturn(emptyFlow())
        whenever(preferences.observe(StringKey.OApsAIMIContextStorage)).thenReturn(MutableStateFlow(""))
        val pump = mock<PumpWithConcentration>()
        whenever(pump.batteryLevel).thenReturn(MutableStateFlow(null))
        whenever(activePlugin.activePump).thenReturn(pump)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
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
        verify(rxBus).toFlow(EventLoopUpdateGui::class)
    }

    private fun shown() {
        clearInvocations(rxBus)
        val vm = AimiDashboardViewModel(
            persistenceLayer, profileFunction, profileUtil, processedTbrEbData, iobCobCalculator,
            glucoseStatusProvider, lastBgData, loop, processedDeviceStatusData, dateUtil, rh,
            decimalFormatter, aapsLogger, activePlugin, preferences, rxBus, config,
        )
        scope.launch { vm.status.collect {} }
    }
}
