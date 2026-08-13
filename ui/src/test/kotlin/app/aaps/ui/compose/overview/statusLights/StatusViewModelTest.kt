package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmSensorStatusProvider
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.ui.R as UiR
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class StatusViewModelTest {

    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var config: Config
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var tddCalculator: TddCalculator
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var processedDeviceStatusData: ProcessedDeviceStatusData

    private lateinit var sut: StatusViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // StandardTestDispatcher defers the init{}-launched refreshState() coroutine (no advanceUntilIdle),
        // so construction stays clean and we test the default uiState. The event-listener flows are still
        // built synchronously in init, so they must be non-null.
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(rxBus.toFlow(EventInitializationChanged::class.java)).thenReturn(emptyFlow())
        whenever(rxBus.toFlow(EventPumpStatusChanged::class.java)).thenReturn(emptyFlow())
        whenever(rxBus.toFlow(EventNsClientStatusUpdated::class.java)).thenReturn(emptyFlow())
        whenever(persistenceLayer.observeChanges(TE::class.java)).thenReturn(emptyFlow())
        whenever(persistenceLayer.databaseClearedFlow).thenReturn(emptyFlow())
        sut = StatusViewModel(
            rh, activePlugin, profileFunction, config, persistenceLayer, dateUtil, rxBus, preferences,
            tddCalculator, decimalFormatter, processedDeviceStatusData
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default uiState has no status items and hidden actions`() {
        val state = sut.uiState.value
        assertThat(state.sensorStatus).isNull()
        assertThat(state.insulinStatus).isNull()
        assertThat(state.showFill).isFalse()
        assertThat(state.showPumpBatteryChange).isFalse()
        assertThat(state.isPatchPump).isFalse()
    }

    @Test
    fun `a source that reports neither warm-up nor staging gets no extra row`() {
        whenever(activePlugin.activeBgSource).thenReturn(object : BgSource {})

        assertThat(sut.buildWarmUpStatus()).isNull()
        assertThat(sut.buildSecondSensorStatus()).isNull()
    }

    @Test
    fun `warm-up row counts down from the wall-clock end time`() {
        val source = FakeStagingBgSource()
        whenever(activePlugin.activeBgSource).thenReturn(source)
        whenever(dateUtil.now()).thenReturn(NOW)
        whenever(rh.gs(R.string.format_mins, 23)).thenReturn("23 min")
        whenever(rh.gs(UiR.string.overview_cgm_warmup_label)).thenReturn("Warm-up")
        source.warmupStatus.value = CgmWarmupStatus(
            active = true,
            phase = CgmWarmupStatus.Phase.WARMING,
            // A stale countdown is on purpose here: the end time must win over it.
            remainingMs = 5 * MINUTE_MS,
            endsAtEpochMs = NOW + 23 * MINUTE_MS,
            message = null,
            totalMs = 30 * MINUTE_MS
        )

        val item = sut.buildWarmUpStatus()

        assertThat(item).isNotNull()
        assertThat(item!!.label).isEqualTo("Warm-up")
        assertThat(item.age).isEqualTo("23 min")
        // 7 of the 30 warm-up minutes are done.
        assertThat(item.agePercent).isWithin(0.001f).of(7f / 30f)
    }

    @Test
    fun `warm-up row disappears once the warm-up is over`() {
        val source = FakeStagingBgSource()
        whenever(activePlugin.activeBgSource).thenReturn(source)
        source.warmupStatus.value = CgmWarmupStatus(
            active = false,
            phase = CgmWarmupStatus.Phase.WARMING,
            remainingMs = null,
            endsAtEpochMs = null,
            message = null
        )

        assertThat(sut.buildWarmUpStatus()).isNull()
    }

    @Test
    fun `second sensor row is absent while no second sensor is staged`() {
        whenever(activePlugin.activeBgSource).thenReturn(FakeStagingBgSource())

        assertThat(sut.buildSecondSensorStatus()).isNull()
    }

    @Test
    fun `settling second sensor shows whole hours left and its reading count`() {
        val source = FakeStagingBgSource()
        whenever(activePlugin.activeBgSource).thenReturn(source)
        whenever(rh.gs(UiR.string.overview_second_sensor_label)).thenReturn("New sensor")
        // 8 h 5 min left must read as 9 h, never as 8 h — the wait is not over until it is over.
        whenever(rh.gs(UiR.string.overview_second_sensor_ready_in, 9)).thenReturn("ready in 9 h")
        whenever(rh.gs(UiR.string.overview_second_sensor_readings, 12)).thenReturn("12 readings")
        source.stagingState.value = StagingState.SETTLING
        source.stagingSettleRemainingMs.value = 8 * HOUR_MS + 5 * MINUTE_MS
        source.stagingLifecycle.value = stagingLifecycle(ageMs = 4 * HOUR_MS)
        source.stagingEvidence.value = CgmStagingEvidence(validCount = 12, lastValueMgdl = 118.0, lastValueAtEpochMs = NOW)

        val item = sut.buildSecondSensorStatus()

        assertThat(item).isNotNull()
        assertThat(item!!.label).isEqualTo("New sensor")
        assertThat(item.age).isEqualTo("ready in 9 h")
        assertThat(item.level).isEqualTo("12 readings")
        assertThat(item.agePercent).isWithin(0.001f).of(4f / (4f + 8f + 5f / 60f))
    }

    @Test
    fun `ready second sensor is marked normal and drops the countdown`() {
        val source = FakeStagingBgSource()
        whenever(activePlugin.activeBgSource).thenReturn(source)
        whenever(rh.gs(UiR.string.overview_second_sensor_label)).thenReturn("New sensor")
        whenever(rh.gs(UiR.string.overview_second_sensor_ready)).thenReturn("ready")
        source.stagingState.value = StagingState.READY
        source.stagingLifecycle.value = stagingLifecycle(ageMs = 13 * HOUR_MS)

        val item = sut.buildSecondSensorStatus()

        assertThat(item).isNotNull()
        assertThat(item!!.age).isEqualTo("ready")
        assertThat(item.ageStatus).isEqualTo(StatusLevel.NORMAL)
        assertThat(item.agePercent).isEqualTo(-1f)
    }

    @Test
    fun `the switch is offered only once the second sensor is ready`() {
        val source = FakeStagingBgSource()
        whenever(activePlugin.activeBgSource).thenReturn(source)

        source.stagingState.value = StagingState.ABSENT
        assertThat(sut.canPromoteSecondSensor()).isFalse()
        source.stagingState.value = StagingState.WARMUP
        assertThat(sut.canPromoteSecondSensor()).isFalse()
        source.stagingState.value = StagingState.SETTLING
        assertThat(sut.canPromoteSecondSensor()).isFalse()
        source.stagingState.value = StagingState.READY
        assertThat(sut.canPromoteSecondSensor()).isTrue()
    }

    @Test
    fun `a source without a staging slot never offers the switch`() {
        whenever(activePlugin.activeBgSource).thenReturn(object : BgSource {})

        assertThat(sut.canPromoteSecondSensor()).isFalse()
    }

    @Test
    fun `every refusal reason has its own wording`() {
        whenever(rh.gs(UiR.string.overview_second_sensor_promote_ok)).thenReturn("done")
        whenever(rh.gs(UiR.string.overview_second_sensor_promote_rejected_absent)).thenReturn("absent")
        whenever(rh.gs(UiR.string.overview_second_sensor_promote_rejected_not_settled)).thenReturn("not settled")
        whenever(rh.gs(UiR.string.overview_second_sensor_promote_rejected_no_glucose)).thenReturn("no glucose")
        whenever(rh.gs(UiR.string.overview_second_sensor_promote_rejected_no_recent_glucose)).thenReturn("no recent glucose")
        whenever(rh.gs(UiR.string.overview_second_sensor_promote_rejected_loop_busy)).thenReturn("loop busy")

        val messages = PromotionRejectReason.entries.map { sut.promotionMessage(PromotionResult.Rejected(it)) }

        assertThat(sut.promotionMessage(PromotionResult.Ok)).isEqualTo("done")
        // One wording per reason: a user who is refused must be able to tell why.
        assertThat(messages).containsNoDuplicates()
        assertThat(messages).hasSize(PromotionRejectReason.entries.size)
    }

    private fun stagingLifecycle(ageMs: Long) = CgmSensorLifecycle(
        slot = SensorSlot.STAGING,
        startedAtEpochMs = NOW - ageMs,
        expiresAtEpochMs = null,
        ageMs = ageMs,
        remainingMs = null,
        earlyLife = true,
        endOfLife = false
    )

    /** BG source that reports both a warm-up and a staging slot, so the two extra rows can be built. */
    private class FakeStagingBgSource : BgSource, CgmSensorStatusProvider {

        override val warmupStatus = MutableStateFlow<CgmWarmupStatus?>(null)
        override val lifecycle = MutableStateFlow<CgmSensorLifecycle?>(null)
        override val stagingWarmupStatus = MutableStateFlow<CgmWarmupStatus?>(null)
        override val stagingLifecycle = MutableStateFlow<CgmSensorLifecycle?>(null)
        override val stagingState = MutableStateFlow(StagingState.ABSENT)
        override val stagingEvidence = MutableStateFlow<CgmStagingEvidence?>(null)
        override val stagingSettleRemainingMs = MutableStateFlow<Long?>(null)

        override suspend fun promoteStagingToProduction(allowEarly: Boolean): PromotionResult = PromotionResult.Ok
    }

    companion object {

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val NOW = 1_700_000_000_000L
    }
}
