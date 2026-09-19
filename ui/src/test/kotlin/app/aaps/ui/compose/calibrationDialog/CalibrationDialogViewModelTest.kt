package app.aaps.ui.compose.calibrationDialog

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.calibration.AddEntryResult
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.calibration.CalibrationStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.SensorCalibrationResult
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.sync.XDripBroadcast
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.ui.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import app.aaps.core.ui.R as CoreUiR

@OptIn(ExperimentalCoroutinesApi::class)
internal class CalibrationDialogViewModelTest {

    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var profileFunction: ProfileFunction
    @Mock private lateinit var xDripBroadcast: XDripBroadcast
    @Mock private lateinit var xDripSource: XDripSource
    @Mock private lateinit var uel: UserEntryLogger
    @Mock private lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var activeCalibration: Calibration
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rh: ResourceHelper

    private lateinit var sut: CalibrationDialogViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // refreshPreconditions() is launched on viewModelScope -> deferred by StandardTestDispatcher.
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.fromMgdlToUnits(any(), any())).thenReturn(0.0)
        whenever(activePlugin.activeCalibration).thenReturn(activeCalibration)
        sut = CalibrationDialogViewModel(
            profileUtil, profileFunction, xDripBroadcast, xDripSource, uel, glucoseStatusProvider,
            activePlugin, persistenceLayer, dateUtil, rh
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no action when bg is zero`() {
        assertThat(sut.uiState.value.bg).isEqualTo(0.0)
        assertThat(sut.hasAction()).isFalse()
    }

    @Test
    fun `updateBg sets the value and enables the action`() {
        sut.updateBg(120.0)

        assertThat(sut.uiState.value.bg).isEqualTo(120.0)
        assertThat(sut.hasAction()).isTrue()
    }

    @Test
    fun `confirmAndSave on an accepted first entry tells the user it did not apply yet`() = runTest {
        whenever(activeCalibration.addEntry(any(), any())).thenReturn(AddEntryResult.Accepted)
        whenever(activeCalibration.status()).thenReturn(CalibrationStatus.NeedMoreEntries(1))
        whenever(rh.gs(eq(R.string.cal_saved_need_more_entries), any())).thenReturn("one more entry needed")

        sut.updateBg(120.0)
        sut.buildConfirmationSummary()
        sut.confirmAndSave()
        advanceUntilIdle()

        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isEqualTo("one more entry needed")
    }

    @Test
    fun `confirmAndSave on an accepted entry that already applies has no message`() = runTest {
        whenever(activeCalibration.addEntry(any(), any())).thenReturn(AddEntryResult.Accepted)
        whenever(activeCalibration.status()).thenReturn(CalibrationStatus.Applied)

        sut.updateBg(120.0)
        sut.buildConfirmationSummary()
        sut.confirmAndSave()
        advanceUntilIdle()

        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isNull()
    }

    // ----- large gap between the entered blood value and the sensor value -----

    /**
     * Builds a view model for a mg/dL profile with a known last sensor reading. In mg/dL the
     * display value and the mg/dL value are the same number, so the conversions are identities.
     */
    private fun viewModelWithSensorMgdl(sensorMgdl: Double): CalibrationDialogViewModel {
        val status = mock<GlucoseStatus>()
        whenever(status.glucose).thenReturn(sensorMgdl)
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(status)
        whenever(profileUtil.fromMgdlToUnits(any(), any())).thenAnswer { it.getArgument<Double>(0) }
        whenever(profileUtil.convertToMgdl(any(), any())).thenAnswer { it.getArgument<Double>(0) }
        return CalibrationDialogViewModel(
            profileUtil, profileFunction, xDripBroadcast, xDripSource, uel, glucoseStatusProvider,
            activePlugin, persistenceLayer, dateUtil, rh
        )
    }

    @Test
    fun `warns when blood 209 is entered against a sensor reading of 309`() {
        val viewModel = viewModelWithSensorMgdl(309.0)

        viewModel.updateBg(209.0)

        val warning = viewModel.uiState.value.gapWarning
        assertThat(warning).isNotNull()
        assertThat(warning!!.bloodValue).isEqualTo(209.0)
        assertThat(warning.sensorValue).isEqualTo(309.0)
        // The entry can still be saved.
        assertThat(viewModel.hasAction()).isTrue()
    }

    @Test
    fun `does not warn on a small gap`() {
        val viewModel = viewModelWithSensorMgdl(100.0)

        viewModel.updateBg(90.0)

        assertThat(viewModel.uiState.value.gapWarning).isNull()
    }

    @Test
    fun `warns exactly at the 25 percent boundary and not just below it`() {
        val atBoundary = viewModelWithSensorMgdl(100.0)
        atBoundary.updateBg(75.0) // gap 25 mg/dL = 25 percent of 100, and below the 50 mg/dL rule
        assertThat(atBoundary.uiState.value.gapWarning).isNotNull()

        val belowBoundary = viewModelWithSensorMgdl(100.0)
        belowBoundary.updateBg(76.0) // gap 24 mg/dL = 24 percent of 100
        assertThat(belowBoundary.uiState.value.gapWarning).isNull()
    }

    @Test
    fun `warns exactly at the 50 mgdl boundary and not just below it`() {
        val atBoundary = viewModelWithSensorMgdl(400.0)
        atBoundary.updateBg(350.0) // gap 50 mg/dL, only 12,5 percent of 400
        assertThat(atBoundary.uiState.value.gapWarning).isNotNull()

        val belowBoundary = viewModelWithSensorMgdl(400.0)
        belowBoundary.updateBg(351.0) // gap 49 mg/dL, about 12 percent of 400
        assertThat(belowBoundary.uiState.value.gapWarning).isNull()
    }

    @Test
    fun `does not warn when no sensor reading is known`() {
        val viewModel = viewModelWithSensorMgdl(0.0)

        viewModel.updateBg(209.0)

        assertThat(viewModel.uiState.value.gapWarning).isNull()
    }
    // ----- sources that hand the value to the sensor itself -----

    /**
     * Makes the active source one that keeps its calibration in the sensor. The mg/dL conversion is
     * the identity here, so the value entered is also the value sent.
     */
    private fun sensorSource(result: SensorCalibrationResult): BgSource {
        val source = mock<BgSource>()
        whenever(source.calibratesInSensor()).thenReturn(true)
        whenever(source.calibrateSensor(any(), any())).thenReturn(result)
        whenever(activePlugin.activeBgSource).thenReturn(source)
        whenever(profileUtil.convertToMgdl(any(), any())).thenAnswer { it.getArgument<Double>(0) }
        return source
    }

    /**
     * The summary is built from string templates, so they must answer before confirmAndSave().
     * The arguments can be null here, so they are matched with anyOrNull().
     */
    private fun stubConfirmationStrings() {
        whenever(rh.gs(eq(CoreUiR.string.value_with_unit), anyOrNull(), anyOrNull())).thenReturn("120 mg/dl")
        whenever(rh.gs(eq(CoreUiR.string.confirmation_line), anyOrNull(), anyOrNull())).thenReturn("BG: 120 mg/dl")
    }

    @Test
    fun `value queued in the sensor stores no entry in the app`() = runTest {
        val source = sensorSource(SensorCalibrationResult.Queued)
        stubConfirmationStrings()
        whenever(rh.gs(R.string.cal_sent_to_sensor)).thenReturn("sent to the sensor")

        sut.updateBg(120.0)
        sut.buildConfirmationSummary()
        sut.confirmAndSave()
        advanceUntilIdle()

        verify(source).calibrateSensor(eq(120), any())
        // Never both: the sensor re-bases itself, a line fitted here would correct it twice.
        verify(activeCalibration, never()).addEntry(any(), any())
        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isEqualTo("sent to the sensor")
    }

    @Test
    fun `value refused by the sensor stores no entry and shows the reason`() = runTest {
        sensorSource(SensorCalibrationResult.Refused("no session"))
        stubConfirmationStrings()

        sut.updateBg(120.0)
        sut.buildConfirmationSummary()
        sut.confirmAndSave()
        advanceUntilIdle()

        verify(activeCalibration, never()).addEntry(any(), any())
        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryRejected
        assertThat(effect.message).isEqualTo("no session")
    }

    @Test
    fun `entry is still stored in the app when the source does not calibrate in the sensor`() = runTest {
        val source = mock<BgSource>()
        whenever(source.calibratesInSensor()).thenReturn(false)
        whenever(activePlugin.activeBgSource).thenReturn(source)
        whenever(profileUtil.convertToMgdl(any(), any())).thenAnswer { it.getArgument<Double>(0) }
        whenever(activeCalibration.addEntry(any(), any())).thenReturn(AddEntryResult.Accepted)
        whenever(activeCalibration.status()).thenReturn(CalibrationStatus.Applied)
        stubConfirmationStrings()

        sut.updateBg(120.0)
        sut.buildConfirmationSummary()
        sut.confirmAndSave()
        advanceUntilIdle()

        verify(activeCalibration).addEntry(eq(120.0), any())
        verify(source, never()).calibrateSensor(any(), any())
        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isNull()
    }

    @Test
    fun `mmol value is sent to the sensor as a rounded mgdl value`() = runTest {
        val source = mock<BgSource>()
        whenever(source.calibratesInSensor()).thenReturn(true)
        whenever(source.calibrateSensor(any(), any())).thenReturn(SensorCalibrationResult.Queued)
        whenever(activePlugin.activeBgSource).thenReturn(source)
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MMOL)
        whenever(profileUtil.convertToMgdl(any(), any())).thenAnswer { it.getArgument<Double>(0) * Constants.MMOLL_TO_MGDL }
        stubConfirmationStrings()
        whenever(rh.gs(R.string.cal_sent_to_sensor)).thenReturn("sent to the sensor")
        val viewModel = CalibrationDialogViewModel(
            profileUtil, profileFunction, xDripBroadcast, xDripSource, uel, glucoseStatusProvider,
            activePlugin, persistenceLayer, dateUtil, rh
        )

        viewModel.updateBg(5.6) // 5.6 mmol/L is 100.9 mg/dL
        viewModel.buildConfirmationSummary()
        viewModel.confirmAndSave()
        advanceUntilIdle()

        verify(source).calibrateSensor(eq(101), any())
    }
}
