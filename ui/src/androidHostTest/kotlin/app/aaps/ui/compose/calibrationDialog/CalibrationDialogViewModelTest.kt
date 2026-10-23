package app.aaps.ui.compose.calibrationDialog

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.calibration.AddEntryResult
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.calibration.CalibrationStatus
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.ui.UiStrings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The calibration dialog only shows what the master decides: it sends the fingerstick value, renders the master's
 * confirmation, and commits it. Storing the calibration on this device is the master's job (see `MasterRecordWriter`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CalibrationDialogViewModelTest {

    @Mock private lateinit var profileUtil: ProfileUtil
    @Mock private lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var activeCalibration: Calibration
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rh: TextResolver
    @Mock private lateinit var decimalFormatter: DecimalFormatter
    @Mock private lateinit var batchExecutor: BatchExecutor
    @Mock private lateinit var config: Config

    private lateinit var dispatcher: TestDispatcher
    private lateinit var sut: CalibrationDialogViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // refreshPreconditions() is launched on viewModelScope -> deferred by StandardTestDispatcher.
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.fromMgdlToUnits(any(), any())).thenReturn(0.0)
        whenever(profileUtil.convertToMgdl(any(), any())).thenReturn(120.0)
        whenever(activePlugin.activeCalibration).thenReturn(activeCalibration)
        whenever(dateUtil.now()).thenReturn(1_700_000_000_000L)
        whenever(config.AAPSCLIENT).thenReturn(false)
        whenever(persistenceLayer.observeChanges(TE::class)).thenReturn(emptyFlow())
        whenever(persistenceLayer.observeChanges(CAL::class)).thenReturn(emptyFlow())
        runBlocking { whenever(activeCalibration.checkPreconditions()).thenReturn(AddEntryResult.Accepted) }
        runBlocking { whenever(activeCalibration.status()).thenReturn(CalibrationStatus.Applied) }
        stubResourceStrings()
        sut = CalibrationDialogViewModel(
            profileUtil, glucoseStatusProvider, activePlugin, persistenceLayer, dateUtil, rh, decimalFormatter, batchExecutor, config
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /**
     * [CalibrationDialogViewModel.buildConfirmationSummary] passes `rh.gs(...)` to
     * [app.aaps.core.data.ui.ConfirmationLinesBuilder.line], whose `text` is non-null. An unstubbed
     * mock returns null and the line builder throws. Placeholders cover the overloads this dialog
     * uses; the status tests re-stub [UiStrings.cal_saved_need_more_entries] and [UiStrings.cal_saved_unsafe_fit]
     * so the asserted message still depends on [app.aaps.core.interfaces.calibration.CalibrationStatus].
     */
    private fun stubResourceStrings() {
        whenever(rh.gs(any<TextRef>())).thenReturn("text")
        whenever(rh.gs(any<TextRef>(), anyOrNull())).thenReturn("text")
        whenever(rh.gs(any<TextRef>(), anyOrNull(), anyOrNull())).thenReturn("text")
    }

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
    fun `an accepted first entry tells the user it did not apply yet`() = runTest(dispatcher) {
        prepareAndApplyCalibration()
        whenever(activeCalibration.status()).thenReturn(CalibrationStatus.NeedMoreEntries(1))
        whenever(rh.gs(eq(UiStrings.cal_saved_need_more_entries), any())).thenReturn("one more entry needed")

        sut.updateBg(120.0)
        sut.requestCalibration()
        advanceUntilIdle()
        sut.confirmCalibration()
        advanceUntilIdle()

        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isEqualTo("one more entry needed")
    }

    @Test
    fun `an accepted entry that already applies has no message`() = runTest(dispatcher) {
        prepareAndApplyCalibration()
        whenever(activeCalibration.status()).thenReturn(CalibrationStatus.Applied)

        sut.updateBg(120.0)
        sut.requestCalibration()
        advanceUntilIdle()
        sut.confirmCalibration()
        advanceUntilIdle()

        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isNull()
    }

    @Test
    fun `an accepted entry with an unsafe fit tells the user it was not applied`() = runTest(dispatcher) {
        prepareAndApplyCalibration()
        whenever(activeCalibration.status()).thenReturn(CalibrationStatus.UnsafeFit)
        whenever(rh.gs(UiStrings.cal_saved_unsafe_fit)).thenReturn("unsafe fit")

        sut.updateBg(120.0)
        sut.requestCalibration()
        advanceUntilIdle()
        sut.confirmCalibration()
        advanceUntilIdle()

        val effect = sut.sideEffect.replayCache.last() as CalibrationDialogViewModel.SideEffect.EntryAccepted
        assertThat(effect.message).isEqualTo("unsafe fit")
    }

    @Test
    fun `the calibration goes to the master and its confirmation is shown`() = runTest(dispatcher) {
        val lines = listOf(ConfirmationLine(ConfirmationRole.PRIMARY, "Calibration: 120 mg/dl"))
        whenever(batchExecutor.prepare(any(), eq(Sources.CalibrationDialog), any())).thenReturn(ActionProgress.Prepared(7L, lines))
        sut.updateBg(120.0)

        sut.requestCalibration()
        advanceUntilIdle()

        val actions = argumentCaptor<List<BatchAction>>()
        verify(batchExecutor).prepare(actions.capture(), eq(Sources.CalibrationDialog), any())
        assertThat(actions.firstValue).containsExactly(BatchAction.Calibration(120.0))
        assertThat(sut.confirmation.value).isEqualTo(CalibrationDialogViewModel.Confirmation(7L, lines))
    }

    @Test
    fun `confirming commits the master's prepared calibration and closes the dialog`() = runTest(dispatcher) {
        whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(ActionProgress.Prepared(7L, emptyList()))
        whenever(batchExecutor.commit(eq(7L), eq(Sources.CalibrationDialog), any(), any())).thenReturn(ActionProgress.Applied)
        sut.updateBg(120.0)
        sut.requestCalibration()
        advanceUntilIdle()

        sut.confirmCalibration()
        advanceUntilIdle()

        verify(batchExecutor).commit(eq(7L), eq(Sources.CalibrationDialog), any(), any())
        assertThat(sut.confirmation.value).isNull()
        assertThat(sut.sideEffect.replayCache).containsExactly(CalibrationDialogViewModel.SideEffect.EntryAccepted(null))
    }

    @Test
    fun `a refusal from the master is shown on the master`() = runTest(dispatcher) {
        whenever(config.AAPSCLIENT).thenReturn(false)
        whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(ActionProgress.Rejected(FailureReason.ExecutionFailed, "BG is changing too fast"))
        sut.updateBg(120.0)

        sut.requestCalibration()
        advanceUntilIdle()

        assertThat(sut.confirmation.value).isNull()
        assertThat(sut.sideEffect.replayCache).containsExactly(CalibrationDialogViewModel.SideEffect.EntryRejected("BG is changing too fast"))
    }

    @Test
    fun `cancelling the confirmation commits nothing`() = runTest(dispatcher) {
        whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(ActionProgress.Prepared(7L, emptyList()))
        sut.updateBg(120.0)
        sut.requestCalibration()
        advanceUntilIdle()

        sut.dismissCalibration()
        sut.confirmCalibration()
        advanceUntilIdle()

        verify(batchExecutor, never()).commit(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `a sensor change goes to the master as a therapy event`() = runTest(dispatcher) {
        whenever(dateUtil.now()).thenReturn(1_000L)
        whenever(batchExecutor.prepare(any(), eq(Sources.SensorInsert), any())).thenReturn(ActionProgress.Prepared(9L, emptyList()))
        whenever(batchExecutor.commit(eq(9L), eq(Sources.SensorInsert), any(), any())).thenReturn(ActionProgress.Applied)

        sut.markSensorChangeNow()
        advanceUntilIdle()

        val actions = argumentCaptor<List<BatchAction>>()
        verify(batchExecutor).prepare(actions.capture(), eq(Sources.SensorInsert), any())
        assertThat(actions.firstValue).containsExactly(BatchAction.TherapyEvent(teType = TE.Type.SENSOR_CHANGE, timestamp = 1_000L, source = Sources.SensorInsert))
        verify(batchExecutor).commit(eq(9L), eq(Sources.SensorInsert), any(), any())
    }

    // ----- large gap between the entered blood value and the sensor value -----
    // Provenance: dev_OAPSAIMI `CalibrationDialogViewModelTest` (commit c653fc4485). The cases that
    // send the value to the sensor itself were not ported, that feature is not on this branch.

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
            profileUtil, glucoseStatusProvider, activePlugin, persistenceLayer, dateUtil, rh, decimalFormatter, batchExecutor, config
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
        atBoundary.updateBg(350.0) // gap 50 mg/dL, only 12.5 percent of 400
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

    @Test
    fun `the warning goes away when the value is corrected`() {
        val viewModel = viewModelWithSensorMgdl(309.0)
        viewModel.updateBg(209.0)
        assertThat(viewModel.uiState.value.gapWarning).isNotNull()

        viewModel.updateBg(300.0)

        assertThat(viewModel.uiState.value.gapWarning).isNull()
    }

    @Test
    fun `in mmol the gap is measured in mgdl and both values are shown in mmol`() {
        val status = mock<GlucoseStatus>()
        whenever(status.glucose).thenReturn(180.0)
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(status)
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MMOL)
        whenever(profileUtil.fromMgdlToUnits(any(), any())).thenAnswer { it.getArgument<Double>(0) / Constants.MMOLL_TO_MGDL }
        whenever(profileUtil.convertToMgdl(any(), any())).thenAnswer { it.getArgument<Double>(0) * Constants.MMOLL_TO_MGDL }
        val viewModel = CalibrationDialogViewModel(
            profileUtil, glucoseStatusProvider, activePlugin, persistenceLayer, dateUtil, rh, decimalFormatter, batchExecutor, config
        )

        viewModel.updateBg(6.0) // about 108 mg/dL against a sensor value of 180 mg/dL

        val warning = viewModel.uiState.value.gapWarning
        assertThat(warning).isNotNull()
        assertThat(warning!!.bloodValue).isEqualTo(6.0)
        assertThat(warning.sensorValue).isWithin(0.01).of(180.0 / Constants.MMOLL_TO_MGDL)
    }

    private suspend fun prepareAndApplyCalibration() {
        whenever(batchExecutor.prepare(any(), eq(Sources.CalibrationDialog), any()))
            .thenReturn(ActionProgress.Prepared(7L, emptyList()))
        whenever(batchExecutor.commit(eq(7L), eq(Sources.CalibrationDialog), any(), any()))
            .thenReturn(ActionProgress.Applied)
    }
}
